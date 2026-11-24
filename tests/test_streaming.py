import asyncio
import json

import pytest
from fastapi import HTTPException
from backend.nova_api.streaming import ProviderEventStream, open_provider_stream


class FakeResponse:
    status_code=200
    headers={"content-type":"text/event-stream"}
    def __init__(self,lines):self.lines=lines;self.closed=False
    async def aiter_lines(self):
        for line in self.lines:yield line
    async def aclose(self):self.closed=True


class FakeClient:
    def __init__(self,*args,**kwargs):self.is_closed=False;self.request=None;self.response=None
    def build_request(self,method,url,headers,json):
        self.request=(method,url,headers,json);return self.request
    async def send(self,request,stream=False):return self.response
    async def aclose(self):self.is_closed=True


def test_openai_upstream_sse_parser_extracts_real_protocol_fields():
    response=FakeResponse([
        'data: {"model":"served","choices":[{"delta":{"content":"hi"},"finish_reason":null}]}',
        'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}',
        'data: [DONE]',
    ])
    stream=ProviderEventStream(FakeClient(),response,"openai_compatible","requested")
    items=asyncio.run(_collect(stream.events()))
    assert [item["text"] for item in items if item["type"]=="text"]==["hi"]
    assert stream.model=="served" and stream.finish_reason=="stop"
    assert items[-1]=={"type":"finish","reason":"stop"}


def test_anthropic_upstream_sse_parser_extracts_start_delta_usage_and_finish():
    response=FakeResponse([
        'event: message_start','data: {"type":"message_start","message":{"model":"claude-real-id","usage":{"input_tokens":7}}}',
        'event: content_block_delta','data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"chunk"}}',
        'event: message_delta','data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}',
        'event: message_stop','data: {"type":"message_stop"}',
    ])
    stream=ProviderEventStream(FakeClient(),response,"anthropic","requested")
    items=asyncio.run(_collect(stream.events()))
    assert items[0]=={"type":"start","input_tokens":7}
    assert [item["text"] for item in items if item["type"]=="text"]==["chunk"]
    assert stream.model=="claude-real-id" and stream.input_tokens==7 and stream.output_tokens==3
    assert items[-1]=={"type":"finish","reason":"end_turn"}


async def _collect(iterator):return [item async for item in iterator]


def test_open_provider_stream_uses_native_anthropic_request_and_closes_on_error(monkeypatch):
    import backend.nova_api.streaming as streaming
    clients=[]
    class MockClient(FakeClient):
        def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.response=FakeResponse([]);clients.append(self)
    monkeypatch.setattr(streaming.httpx,"AsyncClient",MockClient)
    monkeypatch.setattr(streaming,"get_settings",lambda:type("Cfg",(),{"request_timeout_seconds":9})())
    import backend.nova_api.providers as providers
    monkeypatch.setattr(providers,"get_settings",lambda:type("Cfg",(),{"request_timeout_seconds":9,"gateway_allowed_hosts":"gateway.example.test"})())
    upstream=asyncio.run(open_provider_stream(provider="anthropic_compatible",secret="server-only-secret",endpoint="https://gateway.example.test/v1",model="model-x",messages=[{"role":"system","content":"Be safe"},{"role":"user","content":"hello"}],temperature=0.3,max_tokens=80))
    method,url,headers,body=clients[0].request
    assert method=="POST" and url=="https://gateway.example.test/v1/messages"
    assert headers["x-api-key"]=="server-only-secret" and body["stream"] is True and body["system"]=="Be safe"
    assert body["messages"]==[{"role":"user","content":"hello"}]
    asyncio.run(upstream.close())
    assert clients[0].is_closed


def test_upstream_stream_error_does_not_echo_provider_message_or_secret():
    secret="test-secret-must-not-leak"
    response=FakeResponse(['event: error',f'data: {{"type":"error","error":{{"message":"bad key {secret}"}}}}'])
    stream=ProviderEventStream(FakeClient(),response,"anthropic","model")
    with pytest.raises(HTTPException) as caught:asyncio.run(_collect(stream.events()))
    assert caught.value.status_code==502 and secret not in str(caught.value.detail)


def test_open_provider_stream_uses_openai_native_chat_completions_shape(monkeypatch):
    import backend.nova_api.streaming as streaming
    import backend.nova_api.providers as providers
    clients=[]
    class MockClient(FakeClient):
        def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.response=FakeResponse([]);clients.append(self)
    monkeypatch.setattr(streaming.httpx,"AsyncClient",MockClient)
    monkeypatch.setattr(streaming,"get_settings",lambda:type("Cfg",(),{"request_timeout_seconds":9})())
    monkeypatch.setattr(providers,"get_settings",lambda:type("Cfg",(),{"request_timeout_seconds":9,"gateway_allowed_hosts":"gateway.example.test"})())
    upstream=asyncio.run(open_provider_stream(provider="openai_compatible",secret="restricted-token",endpoint="https://gateway.example.test/v1",model="model-x",messages=[{"role":"user","content":"hi"}],temperature=0.2,max_tokens=40))
    method,url,headers,body=clients[0].request
    assert method=="POST" and url=="https://gateway.example.test/v1/chat/completions"
    assert headers["Authorization"]=="Bearer restricted-token" and body["stream"] is True
    assert body["stream_options"]=={"include_usage":True} and body["max_tokens"]==40
    asyncio.run(upstream.close())
