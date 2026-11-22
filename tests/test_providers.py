import asyncio
from types import SimpleNamespace
import httpx
import pytest
from backend.nova_api import providers

def config():
    return SimpleNamespace(provider_api_key="secret-test", provider_base_url="https://provider.test/v1", model="model-x", request_timeout_seconds=5)

def install_transport(monkeypatch, response):
    transport = httpx.MockTransport(lambda request: response)
    original_client = httpx.AsyncClient
    monkeypatch.setattr(providers, "get_settings", config)
    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: original_client(transport=transport, **kwargs))

def test_success_returns_actual_provider_completion(monkeypatch):
    install_transport(monkeypatch, httpx.Response(200, json={"model":"served-model", "choices":[{"message":{"content":"real completion"}}]}))
    text, model = asyncio.run(providers.complete("question"))
    assert (text, model) == ("real completion", "served-model")

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
