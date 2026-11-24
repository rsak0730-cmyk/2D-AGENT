import asyncio
from types import SimpleNamespace
import httpx
import pytest
from backend.nova_api import providers

def config():
    return SimpleNamespace(provider_api_key="secret-test", provider_base_url="https://provider.test/v1", model="model-x", request_timeout_seconds=5, max_completion_tokens=2048, max_prompt_characters=60000)

def install_transport(monkeypatch, response):
    transport = httpx.MockTransport(lambda request: response)
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", config)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))

def test_success_returns_actual_provider_completion(monkeypatch):
    install_transport(monkeypatch, httpx.Response(200, json={"model":"served-model", "choices":[{"message":{"content":"real completion"}}]}))
    text, model = asyncio.run(providers.complete("question"))
    assert (text, model) == ("real completion", "served-model")

def test_provider_model_discovery(monkeypatch):
    install_transport(monkeypatch, httpx.Response(200, json={"data":[{"id":"coding-router-preview"},{"id":"model-exact"}]}))
    assert asyncio.run(providers.list_models("secret-test")) == ["coding-router-preview", "model-exact"]

def test_429_respects_retry_after_without_reflecting_body(monkeypatch):
    from fastapi import HTTPException
    install_transport(monkeypatch, httpx.Response(429, headers={"Retry-After":"17"}, text="private upstream detail"))
    with pytest.raises(HTTPException) as error: asyncio.run(providers.complete("question"))
    assert error.value.status_code == 429
    assert error.value.headers["Retry-After"] == "17"
    assert "private upstream" not in error.value.detail

def test_402_is_not_retried_as_transient(monkeypatch):
    from fastapi import HTTPException
    install_transport(monkeypatch, httpx.Response(402, text="balance details"))
    with pytest.raises(HTTPException) as error: asyncio.run(providers.complete("question"))
    assert error.value.status_code == 402
    assert "balance details" not in error.value.detail

def test_gemini_native_model_discovery_and_generate_content_adapter(monkeypatch):
    calls = []
    def respond(request):
        calls.append(request)
        if request.method == "GET":
            return httpx.Response(200, json={"models":[
                {"name":"models/gemini-2.5-flash", "supportedGenerationMethods":["generateContent"]},
                {"name":"models/embedding-only", "supportedGenerationMethods":["embedContent"]},
            ]})
        return httpx.Response(200, json={"modelVersion":"gemini-2.5-flash", "candidates":[{"content":{"parts":[{"text":"native "},{"text":"Gemini answer"}]},"finishReason":"STOP"}], "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3}})
    transport = httpx.MockTransport(respond)
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", config)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    assert asyncio.run(providers.list_models("synthetic-secret", provider="gemini")) == ["gemini-2.5-flash"]
    result = asyncio.run(providers.complete_detailed(
        "current", "gemini-2.5-flash", history=[{"role":"user","content":"earlier"},{"role":"assistant","content":"prior reply"}],
        api_key="synthetic-secret", provider="gemini", system="Be concise.", max_tokens=24, include_finish_reason=True,
    ))
    assert result == ("native Gemini answer", "gemini-2.5-flash", 5, 3, "STOP")
    assert [str(call.url) for call in calls] == [
        "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
    ]
    assert all(call.headers["x-goog-api-key"] == "synthetic-secret" for call in calls)
    assert all("synthetic-secret" not in str(call.url) for call in calls)
    import json
    body = json.loads(calls[-1].content)
    assert body["systemInstruction"]["parts"][0]["text"] == "Be concise."
    assert [item["role"] for item in body["contents"]] == ["user", "model", "user"]
    assert body["generationConfig"]["maxOutputTokens"] == 24

def test_prompt_and_completion_are_bounded(monkeypatch):
    calls = []
    transport = httpx.MockTransport(lambda request: (calls.append(request) or httpx.Response(200, json={"model":"served","choices":[{"message":{"content":"ok"}}]})))
    original_client = httpx.AsyncClient
    settings = SimpleNamespace(provider_api_key="secret-test", provider_base_url="https://provider.test/v1", model="model-x", request_timeout_seconds=5, max_completion_tokens=123, max_prompt_characters=1200)
    monkeypatch.setattr(providers, "get_settings", lambda: settings)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    history = [{"role":"user","content":"old"*200},{"role":"assistant","content":"middle"*100},{"role":"assistant","content":"latest"*100}]
    asyncio.run(providers.complete_detailed("current", "model-x", history=history, api_key="secret", context_length=300))
    body = calls[0].read().decode()
    assert "oldoldold" not in body
    assert '"max_tokens":123' in body
    from fastapi import HTTPException
    with pytest.raises(HTTPException) as error:
        asyncio.run(providers.complete_detailed("x"*1300, "model-x", api_key="secret"))
    assert error.value.status_code == 413

def test_cloudflare_native_chat_and_account_endpoint(monkeypatch):
    calls = []
    transport = httpx.MockTransport(lambda request: (calls.append(request) or httpx.Response(200, json={"success":True,"result":{"response":"native Cloudflare completion"}})))
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", config)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    result = asyncio.run(providers.complete_detailed("question", "@cf/example/model", api_key="synthetic-secret", provider="cloudflare", account_id="acct-123", base_url="https://api.cloudflare.com/client/v4/accounts/acct-123"))
    assert result == ("native Cloudflare completion", "@cf/example/model", None, None)
    assert str(calls[0].url) == "https://api.cloudflare.com/client/v4/accounts/acct-123/ai/run/@cf/example/model"


def test_claude_code_gateway_openai_compat_protocol_and_allow_list(monkeypatch):
    calls = []
    transport = httpx.MockTransport(lambda request: (calls.append(request) or httpx.Response(200, json={"data":[{"id":"upstream/model-a"}]} if request.method == "GET" else {"model":"upstream/model-a", "choices":[{"message":{"content":"gateway response"}}], "usage":{"prompt_tokens":3,"completion_tokens":4}})))
    original_client = httpx.AsyncClient
    settings = SimpleNamespace(provider_api_key="", provider_base_url="https://provider.test/v1", model="model-x", request_timeout_seconds=5, max_completion_tokens=128, max_prompt_characters=60000, gateway_allowed_hosts="gateway.example.test")
    monkeypatch.setattr(providers, "get_settings", lambda: settings)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    endpoint = "https://gateway.example.test/v1"
    assert asyncio.run(providers.list_models("gateway-token", endpoint, "claude_gateway")) == ["upstream/model-a"]
    result = asyncio.run(providers.complete_detailed("hello", "upstream/model-a", api_key="gateway-token", base_url=endpoint, provider="claude_gateway"))
    assert result == ("gateway response", "upstream/model-a", 3, 4)
    assert [str(call.url) for call in calls] == ["https://gateway.example.test/v1/models", "https://gateway.example.test/v1/chat/completions"]
    assert all(call.headers["authorization"] == "Bearer gateway-token" for call in calls)


def test_native_openai_adapter_uses_official_openai_compatible_protocol(monkeypatch):
    calls = []
    transport = httpx.MockTransport(lambda request: (calls.append(request) or httpx.Response(
        200, json={"data":[{"id":"gpt-test"}]} if request.method == "GET" else {
            "model":"gpt-test", "choices":[{"message":{"content":"OpenAI response"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":4,"completion_tokens":2},
        },
    )))
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", config)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    assert asyncio.run(providers.list_models("openai-runtime-key", provider="openai")) == ["gpt-test"]
    result = asyncio.run(providers.complete_detailed("hello", "gpt-test", api_key="openai-runtime-key", provider="openai"))
    assert result == ("OpenAI response", "gpt-test", 4, 2)
    assert [str(request.url) for request in calls] == ["https://api.openai.com/v1/models", "https://api.openai.com/v1/chat/completions"]
    assert all(request.headers["authorization"] == "Bearer openai-runtime-key" for request in calls)


def test_native_anthropic_adapter_uses_messages_protocol_and_versioned_auth(monkeypatch):
    calls = []
    def respond(request):
        calls.append(request)
        if request.method == "GET":
            return httpx.Response(200, json={"data":[{"id":"claude-model-1"}]})
        return httpx.Response(200, json={
            "id":"msg_mock", "model":"claude-model-1", "content":[{"type":"text","text":"Anthropic response"}],
            "stop_reason":"max_tokens", "usage":{"input_tokens":9,"output_tokens":4},
        })
    transport = httpx.MockTransport(respond)
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", lambda: SimpleNamespace(provider_api_key="", provider_base_url="", model="", request_timeout_seconds=5, max_completion_tokens=128, max_prompt_characters=10000, gateway_allowed_hosts=""))
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    assert asyncio.run(providers.list_models("anthropic-runtime-key", provider="anthropic")) == ["claude-model-1"]
    result = asyncio.run(providers.complete_detailed(
        "latest question", "claude-model-1", history=[{"role":"user","content":"earlier"},{"role":"assistant","content":"answer"}],
        api_key="anthropic-runtime-key", provider="anthropic", system="Be precise.", max_tokens=20, include_finish_reason=True,
    ))
    assert result == ("Anthropic response", "claude-model-1", 9, 4, "max_tokens")
    assert str(calls[0].url) == "https://api.anthropic.com/v1/models"
    assert str(calls[1].url) == "https://api.anthropic.com/v1/messages"
    assert calls[0].headers["x-api-key"] == "anthropic-runtime-key"
    assert calls[0].headers["anthropic-version"] == "2023-06-01"
    body = __import__("json").loads(calls[1].content)
    assert body["system"] == "Be precise." and body["max_tokens"] == 20
    assert [message["role"] for message in body["messages"]] == ["user", "assistant", "user"]


def test_anthropic_compatible_gateway_uses_operator_url_and_x_api_key(monkeypatch):
    calls = []
    def respond(request):
        calls.append(request)
        return httpx.Response(200, json={
            "data":[{"id":"provider/model"}]} if request.method == "GET" else {
            "id":"msg_gw", "model":"provider/model", "content":[{"type":"text","text":"gateway answer"}],
            "stop_reason":"end_turn", "usage":{"input_tokens":2,"output_tokens":3},
        })
    transport = httpx.MockTransport(respond)
    original_client = httpx.AsyncClient
    settings = SimpleNamespace(provider_api_key="", provider_base_url="", model="", request_timeout_seconds=5, max_completion_tokens=128, max_prompt_characters=60000, gateway_allowed_hosts="anthropic-gw.example.test")
    monkeypatch.setattr(providers, "get_settings", lambda: settings)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    endpoint = "https://anthropic-gw.example.test/v1"
    assert asyncio.run(providers.list_models("restricted-token", endpoint, "anthropic_compatible")) == ["provider/model"]
    result = asyncio.run(providers.complete_detailed("hello", "provider/model", api_key="restricted-token", base_url=endpoint, provider="anthropic_compatible", include_finish_reason=True))
    assert result == ("gateway answer", "provider/model", 2, 3, "end_turn")
    assert [str(call.url) for call in calls] == [endpoint + "/models", endpoint + "/messages"]
    assert all(call.headers["x-api-key"] == "restricted-token" for call in calls)


def test_generic_openai_compatible_gateway_uses_configured_allowlisted_base(monkeypatch):
    calls = []
    transport = httpx.MockTransport(lambda request: (calls.append(request) or httpx.Response(
        200,
        json={"data":[{"id":"generic/model-1"}]} if request.method == "GET" else {
            "model":"generic/model-1", "choices":[{"message":{"content":"custom gateway response"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":6,"completion_tokens":3},
        },
    )))
    original_client = httpx.AsyncClient
    settings = SimpleNamespace(provider_api_key="", provider_base_url="https://provider.test/v1", model="model-x", request_timeout_seconds=5, max_completion_tokens=128, max_prompt_characters=60000, gateway_allowed_hosts="litellm.example.test")
    monkeypatch.setattr(providers, "get_settings", lambda: settings)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    endpoint = "https://litellm.example.test/v1"
    assert asyncio.run(providers.list_models("restricted-gateway-token", endpoint, "openai_compatible")) == ["generic/model-1"]
    result = asyncio.run(providers.complete_detailed("hello", "generic/model-1", api_key="restricted-gateway-token", base_url=endpoint, provider="openai_compatible", max_tokens=32, include_finish_reason=True))
    assert result == ("custom gateway response", "generic/model-1", 6, 3, "stop")
    assert [str(call.url) for call in calls] == ["https://litellm.example.test/v1/models", "https://litellm.example.test/v1/chat/completions"]
    assert all(call.headers["authorization"] == "Bearer restricted-gateway-token" for call in calls)
    assert '"max_tokens":32' in calls[-1].content.decode()


def test_gateway_rejects_unlisted_http_and_non_v1_urls(monkeypatch):
    settings = SimpleNamespace(gateway_allowed_hosts="gateway.example.test")
    monkeypatch.setattr(providers, "get_settings", lambda: settings)
    from fastapi import HTTPException
    for endpoint in ("http://gateway.example.test/v1", "https://other.example.test/v1", "https://gateway.example.test/admin", "https://gateway.example.test:8443/v1"):
        with pytest.raises(HTTPException) as error:
            providers._base("claude_gateway", endpoint)
        assert error.value.status_code == 422
