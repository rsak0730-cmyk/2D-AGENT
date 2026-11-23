import asyncio
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from backend.nova_api import compat, routing
from backend.nova_api.models import ApiKey
from backend.nova_api.auth import digest
from conftest import TestingSession, auth_key, client


def test_openai_compat_completion_uses_owner_key_model_and_system_context(monkeypatch):
    seen = {}

    async def fake_invoke(db, owner, message, model, **kwargs):
        seen.update(message=message, model=model, owner=owner.id, **kwargs)
        return "gateway reply", "openai_compatible", "served-model", 12, 5, True, "stop"

    monkeypatch.setattr(compat, "invoke_owner_detailed", fake_invoke)
    monkeypatch.setattr(compat, "get_settings", lambda: SimpleNamespace(max_completion_tokens=512))
    owner_headers = auth_key(["assistant:read", "assistant:write"])
    token = owner_headers["Authorization"]
    response = client.post("/v1/chat/completions", headers=owner_headers, json={
        "model": "requested-model",
        "messages": [
            {"role": "system", "content": "Be concise."},
            {"role": "user", "content": "earlier"},
            {"role": "assistant", "content": "prior answer"},
            {"role": "user", "content": "latest question"},
        ],
        "temperature": 0.4,
        "max_tokens": 128,
    })
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["object"] == "chat.completion"
    assert body["model"] == "served-model"
    assert body["choices"][0]["message"]["content"] == "gateway reply"
    assert body["usage"] == {"prompt_tokens": 12, "completion_tokens": 5, "total_tokens": 17}
    assert response.headers["x-request-id"].startswith("chatcmpl-")
    assert response.headers["x-nova-provider"] == "openai_compatible"
    assert response.headers["x-nova-fallback"] == "true"
    assert seen["message"] == "latest question"
    assert seen["model"] == "requested-model"
    assert seen["system"] == "Be concise."
    assert seen["history"] == [{"role": "user", "content": "earlier"}, {"role": "assistant", "content": "prior answer"}]
    assert seen["max_tokens"] == 128
    assert token not in response.text


def test_anthropic_messages_accepts_x_api_key_and_returns_anthropic_shape(monkeypatch):
    seen = {}

    async def fake_invoke(db, owner, message, model, **kwargs):
        seen.update(message=message, model=model, **kwargs)
        return "Claude-compatible answer", "openai_compatible", "model-actual", 8, 7, False, "length"

    monkeypatch.setattr(compat, "invoke_owner_detailed", fake_invoke)
    monkeypatch.setattr(compat, "get_settings", lambda: SimpleNamespace(max_completion_tokens=2048))
    headers = auth_key(["assistant:write"])
    token = headers["Authorization"].split(" ", 1)[1]
    response = client.post("/v1/messages", headers={"x-api-key": token, "anthropic-version": "2023-06-01"}, json={
        "model": "claude-alias",
        "max_tokens": 300,
        "system": [{"type": "text", "text": "Follow these rules."}],
        "messages": [{"role": "user", "content": [{"type": "text", "text": "question"}]}],
    })
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["type"] == "message" and body["role"] == "assistant"
    assert response.headers["request-id"].startswith("msg_")
    assert body["model"] == "model-actual"
    assert body["content"] == [{"type": "text", "text": "Claude-compatible answer"}]
    assert body["stop_reason"] == "max_tokens"
    assert body["usage"] == {"input_tokens": 8, "output_tokens": 7}
    assert seen["message"] == "question" and seen["system"] == "Follow these rules."
    assert seen["max_tokens"] == 300


def test_compat_routes_reject_invalid_credentials_multimodal_and_missing_stream_providers():
    response = client.post("/v1/chat/completions", headers={"Authorization": "Bearer invalid-key"}, json={
        "model": "model-x", "messages": [{"role": "user", "content": "hi"}],
    })
    assert response.status_code == 401 and "error" in response.json()
    assert "invalid-key" not in response.text

    owner = auth_key(["assistant:write"])
    unsupported = client.post("/v1/messages", headers={"x-api-key": owner["Authorization"].split()[1]}, json={
        "model": "model-x", "max_tokens": 20,
        "messages": [{"role": "user", "content": [{"type": "image", "source": {}}]}],
    })
    assert unsupported.status_code == 422 and unsupported.json()["type"] == "error"
    assert "multimodal" in unsupported.json()["error"]["message"]

    streaming = client.post("/v1/chat/completions", headers=owner, json={
        "model": "model-x", "messages": [{"role": "user", "content": "hi"}], "stream": True,
    })
    assert streaming.status_code == 503
    assert "provider connection" in streaming.json()["error"]["message"]

    malformed = client.post("/v1/messages", headers=owner, json={
        "model": "model-x", "max_tokens": 20, "messages": [{"role": "user", "content": "hi"}],
        "api_key": "never-echo-this-secret",
    })
    assert malformed.status_code == 422 and malformed.json()["type"] == "error"
    assert "never-echo-this-secret" not in malformed.text


def test_openai_compatible_gateway_connection_requires_allowlisted_https_and_hides_token(monkeypatch):
    from cryptography.fernet import Fernet
    from backend.nova_api import credentials, workforce, providers
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    gateway_settings = SimpleNamespace(gateway_allowed_hosts="bifrost.example.test", provider_base_url="https://arena.example.test/v1")
    monkeypatch.setattr(workforce, "get_settings", lambda: gateway_settings)
    monkeypatch.setattr(providers, "get_settings", lambda: SimpleNamespace(**gateway_settings.__dict__, request_timeout_seconds=5, max_completion_tokens=100, max_prompt_characters=10000))
    headers = auth_key(["providers:read", "providers:write"])
    saved = client.post("/api/v1/providers/connections", headers=headers, json={
        "provider": "openai_compatible", "api_key": "restricted-token-example",
        "endpoint": "https://bifrost.example.test/v1", "model_ids": ["org/model-a"],
    })
    assert saved.status_code == 200, saved.text
    assert "restricted-token-example" not in saved.text and "encrypted_api_key" not in saved.text
    assert saved.json()["provider"] == "openai_compatible"
    rejected = client.post("/api/v1/providers/connections", headers=headers, json={
        "provider": "openai_compatible", "api_key": "another-restricted-token",
        "endpoint": "https://attacker.example.test/v1",
    })
    assert rejected.status_code == 422


def test_application_key_cannot_use_gateway_admin_encryption_rotation_endpoint(monkeypatch):
    from backend.nova_api import auth
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(admin_token="synthetic-admin-token"))
    owner = auth_key(["assistant:read", "assistant:write", "providers:write"])
    response = client.post("/api/v1/admin/credentials/rotate", headers=owner)
    assert response.status_code == 401


def test_revoked_application_key_is_rejected_by_compatibility_endpoints():
    headers = auth_key(["assistant:read", "assistant:write"])
    raw = headers["Authorization"].split(" ", 1)[1]
    db = TestingSession()
    try:
        row = db.query(ApiKey).filter_by(key_hash=digest(raw)).first()
        row.revoked = True
        db.commit()
    finally:
        db.close()
    response = client.post("/v1/chat/completions", headers=headers, json={
        "model": "model-x", "messages": [{"role": "user", "content": "hi"}],
    })
    assert response.status_code == 401
    assert raw not in response.text


def test_requested_model_is_not_silently_replaced_by_a_different_environment_model(monkeypatch):
    owner_headers = auth_key(["assistant:write"])
    db = TestingSession()
    token = owner_headers["Authorization"].split(" ", 1)[1]
    owner = db.query(ApiKey).filter_by(key_hash=digest(token)).first()
    monkeypatch.setattr(routing, "get_settings", lambda: SimpleNamespace(
        provider_api_key="synthetic-secret", provider="arena", model="different-available-model",
        provider_base_url="https://gateway.example.test/v1",
    ))
    try:
        with pytest.raises(HTTPException) as error:
            asyncio.run(routing.invoke_owner_detailed(db, owner, "question", "requested-model"))
        assert error.value.status_code == 503
        assert "compatible model" in error.value.detail
    finally:
        db.close()


def test_model_catalog_lists_configured_models_without_exposing_secrets(monkeypatch):
    monkeypatch.setattr(compat, "get_settings", lambda: SimpleNamespace(provider_api_key="runtime-secret", model="runtime-model", provider="arena"))
    owner = auth_key(["assistant:read", "assistant:write"])
    response = client.get("/v1/models", headers=owner)
    assert response.status_code == 200
    ids = {item["id"] for item in response.json()["data"]}
    assert ids == {"runtime-model"}
    assert "runtime-secret" not in response.text
    assert "encrypted_api_key" not in response.text
    assert "api_key" not in response.text


def test_openai_stream_translates_real_text_and_finish_events(monkeypatch):
    from backend.nova_api import compat_stream
    monkeypatch.setattr(compat_stream,"SessionLocal",TestingSession)
    class FakeStream:
        model="served-stream-model";input_tokens=11;output_tokens=4;finish_reason="stop"
        closed=False
        async def events(self):
            yield {"type":"text","text":"hello "}
            yield {"type":"text","text":"world"}
            yield {"type":"finish","reason":"stop"}
        async def close(self): self.closed=True
    upstream=FakeStream()
    async def open_stream(db,owner,model,messages,temperature,max_tokens):
        assert messages[-1]=={"role":"user","content":"say hello"}
        return upstream,"openai_compatible",False
    monkeypatch.setattr(compat,"open_owner_stream",open_stream)
    headers=auth_key(["assistant:write"])
    secret=headers["Authorization"].split()[1]
    response=client.post("/v1/chat/completions",headers=headers,json={"model":"requested-model","stream":True,"messages":[{"role":"user","content":"say hello"}]})
    assert response.status_code==200 and response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["x-nova-provider"]=="openai_compatible"
    chunks=[line[6:] for line in response.text.splitlines() if line.startswith("data: ")]
    parsed=[__import__("json").loads(chunk) for chunk in chunks if chunk!="[DONE]"]
    assert [item["choices"][0]["delta"].get("content") for item in parsed if item["choices"] and item["choices"][0]["delta"].get("content")] == ["hello ","world"]
    assert next(item for item in parsed if item["choices"] and item["choices"][0]["finish_reason"] is not None)["choices"][0]["finish_reason"]=="stop"
    assert parsed[-1]["usage"]=={"prompt_tokens":11,"completion_tokens":4,"total_tokens":15}
    assert chunks[-1]=="[DONE]" and secret not in response.text and upstream.closed


def test_anthropic_stream_emits_ordered_messages_events_and_stops(monkeypatch):
    from backend.nova_api import compat_stream
    monkeypatch.setattr(compat_stream,"SessionLocal",TestingSession)
    class FakeStream:
        model="claude-stream";input_tokens=9;output_tokens=2;finish_reason="end_turn"
        closed=False
        async def events(self):
            yield {"type":"start","input_tokens":9}
            yield {"type":"text","text":"answer"}
            yield {"type":"finish","reason":"end_turn"}
        async def close(self): self.closed=True
    upstream=FakeStream()
    async def open_stream(db,owner,model,messages,temperature,max_tokens):return upstream,"anthropic",False
    monkeypatch.setattr(compat,"open_owner_stream",open_stream)
    token=auth_key(["assistant:write"])["Authorization"].split()[1]
    response=client.post("/v1/messages",headers={"x-api-key":token},json={"model":"claude-stream","max_tokens":20,"stream":True,"messages":[{"role":"user","content":"question"}]})
    assert response.status_code==200
    events=[line[7:] for line in response.text.splitlines() if line.startswith("event: ")]
    assert events==["message_start","content_block_start","content_block_delta","content_block_stop","message_delta","message_stop"]
    assert '"text":"answer"' in response.text and '"stop_reason":"end_turn"' in response.text
    assert upstream.closed and token not in response.text


def test_stream_interruption_emits_protocol_error_closes_upstream_and_records_failure(monkeypatch):
    from backend.nova_api import compat_stream
    monkeypatch.setattr(compat_stream,"SessionLocal",TestingSession)
    class BrokenStream:
        model="model-x";input_tokens=None;output_tokens=None;finish_reason=None;closed=False
        async def events(self):
            yield {"type":"text","text":"partial"}
            import httpx
            raise httpx.ReadError("transport dropped")
        async def close(self):self.closed=True
    upstream=BrokenStream()
    async def open_stream(db,owner,model,messages,temperature,max_tokens):return upstream,"openai",False
    monkeypatch.setattr(compat,"open_owner_stream",open_stream)
    headers=auth_key(["assistant:write"])
    response=client.post("/v1/chat/completions",headers=headers,json={"model":"model-x","stream":True,"messages":[{"role":"user","content":"hi"}]})
    assert response.status_code==200 and '"content":"partial"' in response.text
    assert '"code":502' in response.text and "interrupted" in response.text
    assert upstream.closed
    # A failed/interrupted stream is recorded as failed, with no fabricated completion.
    from backend.nova_api.auth import digest
    from backend.nova_api.models import ApiKey,UsageRecord
    token=headers["Authorization"].split()[1];db=TestingSession()
    try:
        key=db.query(ApiKey).filter_by(key_hash=digest(token)).one()
        usage=db.query(UsageRecord).filter_by(owner_key_id=key.id,provider="openai",model_id="model-x").order_by(UsageRecord.created_at.desc()).first()
        assert usage is not None and usage.status=="failed" and usage.error_kind=="stream_connection_error"
    finally:db.close()


def test_downstream_cancel_closes_native_stream_and_records_cancellation(monkeypatch):
    import asyncio
    from backend.nova_api import compat_stream
    monkeypatch.setattr(compat_stream,"SessionLocal",TestingSession)
    from backend.nova_api.compat_stream import openai_stream
    from backend.nova_api.streaming import ProviderEventStream
    class FakeResponse:
        async def aiter_lines(self):
            yield 'data: {"choices":[{"delta":{"content":"first"},"finish_reason":null}]}'
            await asyncio.sleep(5)
        async def aclose(self):self.closed=True
    class FakeClient:
        is_closed=False
        async def aclose(self):self.is_closed=True
    upstream=ProviderEventStream(FakeClient(),FakeResponse(),"openai","model-x")
    headers=auth_key(["assistant:write"])
    token=headers["Authorization"].split()[1]
    from backend.nova_api.auth import digest
    from backend.nova_api.models import ApiKey,UsageRecord
    db=TestingSession();owner_id=db.query(ApiKey).filter_by(key_hash=digest(token)).one().id;db.close()
    async def cancel_after_first_chunk():
        generator=openai_stream(upstream,owner_id,"openai",False,"model-x","req-test")
        first=await generator.__anext__()
        await generator.aclose()
        return first
    first=asyncio.run(cancel_after_first_chunk())
    assert "role" in first and upstream.closed
    db=TestingSession()
    try:
        usage=db.query(UsageRecord).filter_by(owner_key_id=owner_id,provider="openai",model_id="model-x").order_by(UsageRecord.created_at.desc()).first()
        assert usage is not None and usage.status=="failed" and usage.error_kind=="stream_cancelled"
    finally:db.close()
