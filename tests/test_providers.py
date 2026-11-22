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

def test_gemini_uses_verified_openai_compatible_endpoint(monkeypatch):
    calls = []
    transport = httpx.MockTransport(lambda request: (calls.append(request) or httpx.Response(200, json={"model":"gemini-test", "choices":[{"message":{"content":"live adapter response"}}], "usage":{"prompt_tokens":4,"completion_tokens":2}})))
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", config)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))
    result = asyncio.run(providers.complete_detailed("question", "gemini-exact-model", api_key="synthetic-secret", provider="gemini"))
    assert result == ("live adapter response", "gemini-test", 4, 2)
    assert str(calls[0].url) == "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    assert calls[0].headers["authorization"] == "Bearer synthetic-secret"

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
