from types import SimpleNamespace
from backend.nova_api import credentials, main
from conftest import TestingSession, auth_key, client
from backend.nova_api.auth import digest
from backend.nova_api.models import ApiKey, ProviderCredential


def test_mobile_provider_key_is_encrypted_isolated_and_used(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    owner = auth_key(["providers:read", "providers:write", "assistant:write"])
    key = owner["Authorization"].split(" ", 1)[1]
    saved = client.put("/api/v1/settings/provider", headers=owner, json={"provider":"arena", "model_id":"coding-router-preview", "api_key":"arena-secret-for-test"})
    assert saved.status_code == 200, saved.text
    assert saved.json()["configured"] is True and saved.json()["key_source"] == "mobile_encrypted"
    db = TestingSession(); api_key = db.query(ApiKey).filter_by(key_hash=digest(key)).first()
    stored = db.query(ProviderCredential).filter_by(owner_key_id=api_key.id).first(); db.close()
    assert stored.encrypted_api_key != "arena-secret-for-test"
    assert credentials.decrypt_secret(stored.encrypted_api_key) == "arena-secret-for-test"

    calls = {}
    async def fake_models(api_key):
        calls["test_key"] = api_key
        return ["coding-router-preview", "model-z"]
    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None):
        calls.update({"chat_key":api_key, "model":model})
        return "reply from provider", model
    monkeypatch.setattr(main, "list_models", fake_models)
    monkeypatch.setattr(main, "complete", fake_complete)
    tested = client.post("/api/v1/settings/provider/test", headers=owner)
    assert tested.status_code == 200 and tested.json()["models_available"] == 2
    answer = client.post("/api/v1/assistant/chat", headers=owner, json={"message":"hello"})
    assert answer.status_code == 200 and answer.json()["response"] == "reply from provider"
    assert calls["test_key"] == "arena-secret-for-test" and calls["chat_key"] == "arena-secret-for-test"
    assert calls["model"] == "coding-router-preview"

    stranger = auth_key(["providers:read", "providers:write"])
    other_config = client.get("/api/v1/settings/provider", headers=stranger)
    assert other_config.status_code == 200 and other_config.json()["configured"] is False
    cleared = client.delete("/api/v1/settings/provider", headers=owner)
    assert cleared.status_code == 200
    assert client.get("/api/v1/settings/provider", headers=owner).json()["configured"] is False


def test_provider_configuration_requires_encryption_key(monkeypatch):
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=""))
    owner = auth_key(["providers:write"])
    result = client.put("/api/v1/settings/provider", headers=owner, json={"api_key":"provider-key-secret", "model_id":"model-x"})
    assert result.status_code == 503
