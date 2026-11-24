from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from backend.nova_api import auth
from conftest import TestingSession, client
from backend.nova_api.models import ApiKey

def test_admin_create_and_revoke_key(monkeypatch):
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(admin_token="test-admin-token"))
    headers = {"Authorization":"Bearer test-admin-token"}
    created = client.post("/api/v1/admin/api-keys", headers=headers, json={"name":"phone", "scopes":["assistant:read", "assistant:write"]})
    assert created.status_code == 200, created.text
    body = created.json(); raw = body["key"]
    db = TestingSession(); stored = db.get(ApiKey, body["id"]); assert stored.key_hash != raw and len(stored.key_hash) == 64; db.close()
    assert client.get("/api/v1/assistant/conversations", headers={"Authorization":f"Bearer {raw}"}).status_code == 200
    revoked = client.delete(f"/api/v1/admin/api-keys/{body['id']}", headers=headers)
    assert revoked.status_code == 200
    assert client.get("/api/v1/assistant/conversations", headers={"Authorization":f"Bearer {raw}"}).status_code == 401

def test_key_expiry_requires_timezone(monkeypatch):
    monkeypatch.setattr(auth, "get_settings", lambda: SimpleNamespace(admin_token="test-admin-token"))
    response = client.post("/api/v1/admin/api-keys", headers={"Authorization":"Bearer test-admin-token"}, json={"name":"naive", "scopes":["tasks:read"], "expires_at":(datetime.now(timezone.utc)+timedelta(days=1)).replace(tzinfo=None).isoformat()})
    assert response.status_code == 422
