from backend.nova_api import main
from conftest import auth_key, client


def test_mobile_provider_key_submission_is_rejected_without_persisting_secret():
    owner = auth_key(["providers:read", "providers:write"])
    submitted_secret = "provider-key-secret-for-test"
    result = client.put("/api/v1/settings/provider", headers=owner, json={
        "provider": "arena", "model_id": "model-x", "api_key": submitted_secret,
    })
    assert result.status_code == 403
    assert submitted_secret not in result.text
    assert "cannot be submitted by an application client" in result.json()["detail"]
    config = client.get("/api/v1/settings/provider", headers=owner)
    assert config.status_code == 200 and config.json()["configured"] is False


def test_direct_provider_connection_key_submission_is_rejected():
    owner = auth_key(["providers:read", "providers:write"])
    secret = "synthetic-groq-key-not-for-client"
    result = client.post("/api/v1/providers/connections", headers=owner, json={
        "provider": "groq", "api_key": secret, "model_ids": ["model-x"],
    })
    assert result.status_code == 403
    assert secret not in result.text
    assert "runtime secret manager" in result.json()["detail"]


def test_backend_runtime_anthropic_provider_test_uses_native_anthropic_adapter(monkeypatch):
    seen = {}
    monkeypatch.setattr(main, "get_settings", lambda: type("Settings", (), {
        "provider_api_key": "synthetic-backend-secret", "provider": "anthropic",
        "model": "claude-model-x", "provider_base_url": "https://unused.example.test/v1",
    })())
    async def fake_models(api_key, base_url=None, provider="arena", account_id=None):
        seen.update(api_key=api_key, base_url=base_url, provider=provider)
        return ["claude-model-x"]
    monkeypatch.setattr(main, "list_models", fake_models)
    owner = auth_key(["providers:write"])
    result = client.post("/api/v1/settings/provider/test", headers=owner)
    assert result.status_code == 200 and result.json()["model_ids"] == ["claude-model-x"]
    assert seen == {"api_key": "synthetic-backend-secret", "base_url": None, "provider": "anthropic"}
    assert "synthetic-backend-secret" not in result.text


def test_provider_credential_validation_errors_do_not_echo_submitted_values():
    owner = auth_key(["providers:write"])
    result = client.post("/api/v1/providers/connections", headers=owner, json={
        "provider": "openai_compatible", "api_key": "short", "endpoint": "https://gateway.example.org/v1",
    })
    assert result.status_code == 422
    assert "short" not in result.text
    assert "submitted values are not echoed" in result.json()["detail"]


def test_mobile_provider_setup_needs_backend_runtime_secret():
    owner = auth_key(["providers:read", "providers:write"])
    result = client.put("/api/v1/settings/provider", headers=owner, json={
        "provider": "arena", "model_id": "model-x",
    })
    assert result.status_code == 422
    assert "do not paste provider keys into the app" in result.json()["detail"]
