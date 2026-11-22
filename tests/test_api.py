from datetime import datetime, timezone
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from backend.nova_api.db import Base, get_db
from backend.nova_api.main import app
from backend.nova_api.auth import digest
from backend.nova_api.models import ApiKey

engine = create_engine("sqlite://", connect_args={"check_same_thread":False}, poolclass=StaticPool)
TestingSession = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
Base.metadata.create_all(engine)
def override_db():
    db = TestingSession()
    try: yield db
    finally: db.close()
app.dependency_overrides[get_db] = override_db
client = TestClient(app)

def auth_key(scopes):
    raw = "test_" + str(datetime.now().timestamp())
    db = TestingSession(); row = ApiKey(name="test", key_hash=digest(raw), scopes=scopes); db.add(row); db.commit(); db.close()
    return {"Authorization": f"Bearer {raw}"}

def test_health_does_not_require_provider():
    assert client.get("/health").status_code == 200

def test_scope_enforced_and_agents_crud():
    denied = client.get("/api/v1/agents", headers=auth_key(["tasks:read"]))
    assert denied.status_code == 403
    headers = auth_key(["agents:write", "agents:read"])
    created = client.post("/api/v1/agents", headers=headers, json={"name":"NOVA API Tester", "provider":"arena", "model_id":"coding-router-preview"})
    assert created.status_code == 200, created.text
    agent_id = created.json()["id"]
    assert any(x["id"] == agent_id for x in client.get("/api/v1/agents", headers=headers).json())
    assert client.delete(f"/api/v1/agents/{agent_id}", headers=headers).status_code == 200

def test_tasks_checkpoint_and_persistence():
    headers = auth_key(["tasks:write", "tasks:read"])
    created = client.post("/api/v1/tasks", headers=headers, json={"title":"Write tests"})
    assert created.status_code == 200
    task_id = created.json()["id"]
    updated = client.patch(f"/api/v1/tasks/{task_id}/checkpoint", headers=headers, json={"state":"PAUSED", "checkpoint":{"step":2}})
    assert updated.status_code == 200 and updated.json()["checkpoint"] == {"step":2}
    assert client.get(f"/api/v1/tasks/{task_id}", headers=headers).json()["state"] == "PAUSED"

def test_chat_fails_closed_without_credentials(monkeypatch):
    from backend import nova_api
    async def missing(*args, **kwargs):
        from fastapi import HTTPException
        raise HTTPException(503, "No AI provider key configured")
    monkeypatch.setattr(nova_api.main, "complete", missing)
    response = client.post("/api/v1/assistant/chat", headers=auth_key(["assistant:write"]), json={"message":"hello"})
    assert response.status_code == 503
