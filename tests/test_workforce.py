import asyncio
import json
from types import SimpleNamespace
from fastapi import HTTPException
from backend.nova_api import credentials, routing
from backend.nova_api.models import ActivityEvent, Agent, AgentMemory, AgentMessage, ApiKey, ProviderConnection, ResourceSetting, Task, TaskAssignment, UsageRecord
from backend.nova_api.auth import digest
from backend.nova_api.workforce import run_worker_tool_requests
from conftest import TestingSession, auth_key, client


def test_roster_is_idempotent_and_isolated():
    owner = auth_key(["agents:read", "agents:write"])
    seeded = client.post("/api/v1/agents/seed", headers=owner)
    assert seeded.status_code == 200, seeded.text
    body = seeded.json()
    assert len(body) == 11 and {x["identity"] for x in body} == {"manager", "android", "web", "game", "ui-ux", "backend", "database", "security", "qa", "research", "documentation"}
    assert client.post("/api/v1/agents/seed", headers=owner).json() == body
    duplicate = client.post("/api/v1/agents", headers=owner, json={"identity":"manager", "name":"Second Manager"})
    assert duplicate.status_code == 409
    stranger = auth_key(["agents:read"])
    assert client.get("/api/v1/agents", headers=stranger).json() == []
    assert client.put(f"/api/v1/agents/{body[0]['id']}", headers=stranger, json={}).status_code == 403


def test_memory_is_scoped_and_validated():
    owner = auth_key(["agents:read", "agents:write"])
    agent = client.post("/api/v1/agents/seed", headers=owner).json()[0]
    saved = client.post("/api/v1/memories", headers=owner, json={"scope":"agent", "agent_id":agent["id"], "content":"Project convention: use UTC", "keywords":["time"], "importance":0.9})
    assert saved.status_code == 200, saved.text
    assert client.get("/api/v1/memories?q=UTC", headers=owner).json()[0]["id"] == saved.json()["id"]
    outsider = auth_key(["agents:read"])
    assert client.get("/api/v1/memories", headers=outsider).json() == []
    assert client.post("/api/v1/memories", headers=owner, json={"scope":"project", "content":"missing project"}).status_code == 422


def test_provider_connection_encrypted_and_fallback_is_bounded(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    owner = auth_key(["providers:read", "providers:write"])
    response = client.post("/api/v1/providers/connections", headers=owner, json={"provider":"groq", "api_key":"groq-secret-test-key", "model_ids":["model-groq"], "priority":1})
    assert response.status_code == 200, response.text
    saved = response.json()
    db = TestingSession(); row = db.get(ProviderConnection, saved["id"])
    assert row.encrypted_api_key != "groq-secret-test-key"
    assert credentials.decrypt_secret(row.encrypted_api_key) == "groq-secret-test-key"
    db.close()

    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None, provider="arena", **kwargs):
        if provider == "groq": raise HTTPException(429, "rate limited")
        return "actual fallback response", "fallback-model", 11, 7
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    monkeypatch.setattr(routing, "get_settings", lambda: SimpleNamespace(provider_api_key="environment-key", provider="arena", model="fallback-model", provider_base_url="https://arena.test/v1"))
    db = TestingSession(); api = db.query(ApiKey).filter_by(key_hash=digest(owner["Authorization"].split()[1])).first()
    result = __import__("asyncio").run(routing.invoke_owner(db, api, "hello", "model-groq"))
    db.close()
    assert result == ("actual fallback response", "arena", "fallback-model")
    assert client.delete(f"/api/v1/providers/connections/{saved['id']}", headers=owner).json()["deleted"] is True


def test_gateway_connection_persists_endpoint_and_never_returns_token(monkeypatch):
    from cryptography.fernet import Fernet
    from backend.nova_api import workforce, providers
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    monkeypatch.setattr(workforce, "get_settings", lambda: SimpleNamespace(gateway_allowed_hosts="gateway.example.test", provider_base_url="https://arena.test/v1"))
    monkeypatch.setattr(providers, "get_settings", lambda: SimpleNamespace(gateway_allowed_hosts="gateway.example.test", provider_base_url="https://arena.test/v1", request_timeout_seconds=5, max_completion_tokens=100, max_prompt_characters=10000))
    owner = auth_key(["providers:read", "providers:write"])
    response = client.post("/api/v1/providers/connections", headers=owner, json={"provider":"claude_gateway", "api_key":"gateway-secret-token", "endpoint":"https://gateway.example.test/v1"})
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["provider"] == "claude_gateway" and body["account_id"] is None
    assert "gateway-secret-token" not in response.text
    db = TestingSession(); row = db.get(ProviderConnection, body["id"])
    assert row.endpoint == "https://gateway.example.test/v1"
    assert credentials.decrypt_secret(row.encrypted_api_key) == "gateway-secret-token"
    db.close()
    rejected = client.post("/api/v1/providers/connections", headers=owner, json={"provider":"claude_gateway", "api_key":"another-secret-token", "endpoint":"http://127.0.0.1:8090/v1"})
    assert rejected.status_code == 422


def test_manager_delegates_to_real_workers_and_persists_results(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    scopes = ["agents:read", "agents:write", "tasks:read", "tasks:write", "providers:read", "providers:write", "events:read"]
    owner = auth_key(scopes)
    seeded = client.post("/api/v1/agents/seed", headers=owner)
    assert seeded.status_code == 200
    db = TestingSession()
    for agent in db.query(Agent).all(): agent.model_id = "test-live-model"
    db.commit(); db.close()
    connection = client.post("/api/v1/providers/connections", headers=owner, json={"provider":"groq", "api_key":"synthetic-provider-secret", "model_ids":["test-live-model"]})
    assert connection.status_code == 200, connection.text
    task_result = client.post("/api/v1/tasks", headers=owner, json={"title":"Review API security", "description":"Inspect auth behavior", "max_attempts":2})
    assert task_result.status_code == 200, task_result.text
    calls = []
    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None, provider="arena", system=None, **kwargs):
        calls.append((message, model, provider, system))
        if "Return JSON only" in message:
            return json.dumps({"subtasks":[{"agent":"security", "title":"Review auth", "instructions":"Identify risks"},{"agent":"qa", "title":"Suggest tests", "instructions":"List test gaps"}]}), model, 20, 2
        if "Review these actual worker reports" in message:
            return "Manager reviewed the two recorded reports.", model, 30, 10
        return f"real mocked provider report {len(calls)}", model, 12, 8
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    executed = client.post(f"/api/v1/tasks/{task_result.json()['id']}/execute", headers=owner)
    assert executed.status_code == 200, executed.text
    assert executed.json()["state"] == "COMPLETED"
    assert executed.json()["result"] == "Manager reviewed the two recorded reports."
    assert len(calls) == 4
    db = TestingSession()
    assignments = db.query(TaskAssignment).filter_by(task_id=task_result.json()["id"]).all()
    messages = db.query(AgentMessage).filter_by(task_id=task_result.json()["id"]).all()
    assert len(assignments) == 2 and all(x.status == "COMPLETED" and x.result for x in assignments)
    assert {x.message_type for x in messages} == {"delegation_plan", "worker_result", "final_decision"}
    usage = db.query(UsageRecord).filter_by(task_id=task_result.json()["id"], status="success").all()
    assert len(usage) == 4 and sum(x.input_tokens for x in usage) > 0
    db.close()


def test_worker_executes_only_its_explicitly_permitted_safe_tool(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    scopes = ["agents:read", "agents:write", "tasks:read", "tasks:write", "providers:read", "providers:write", "events:read"]
    owner = auth_key(scopes)
    client.post("/api/v1/agents/seed", headers=owner)
    db = TestingSession()
    for agent in db.query(Agent).all():
        agent.model_id = "test-live-model"
        if agent.identity == "qa": agent.tool_permissions = ["math.calculate"]
    db.commit(); db.close()
    client.post("/api/v1/providers/connections", headers=owner, json={"provider":"groq", "api_key":"synthetic-provider-secret", "model_ids":["test-live-model"]})
    task = client.post("/api/v1/tasks", headers=owner, json={"title":"Calculate project estimate"}).json()
    calls = []
    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None, provider="arena", system=None, **kwargs):
        calls.append(message)
        if "Return JSON only" in message:
            return json.dumps({"subtasks":[{"agent":"qa", "title":"Calculate estimate", "instructions":"Calculate 6 times 7"}]}), model, 2, 1
        if "Optional safe tools explicitly enabled" in message:
            return json.dumps({"tool_calls":[{"name":"math.calculate","arguments":{"expression":"6 * 7"}}]}), model, 3, 1
        if "Tool results:" in message:
            assert '"result": 42' in message
            return "The permitted calculator returned 42.", model, 4, 2
        if "Review these actual worker reports" in message:
            return "QA reported the calculator result 42.", model, 5, 2
        raise AssertionError("Unexpected provider prompt")
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    result = client.post(f"/api/v1/tasks/{task['id']}/execute", headers=owner)
    assert result.status_code == 200, result.text
    assert result.json()["state"] == "COMPLETED"
    assert result.json()["result"] == "QA reported the calculator result 42."
    assert len(calls) == 4
    db = TestingSession()
    messages = db.query(AgentMessage).filter_by(task_id=task["id"], message_type="tool_result").all()
    assert len(messages) == 1 and messages[0].content == {"tool":"math.calculate", "result":{"expression":"6 * 7", "result":42}}
    assert db.query(ActivityEvent).filter_by(task_id=task["id"], event_type="tool.completed").count() == 1
    db.close()


def test_worker_tool_permission_denial_is_audited_and_fails_closed():
    scopes = ["agents:read", "agents:write", "tasks:read", "tasks:write", "events:read"]
    headers = auth_key(scopes)
    client.post("/api/v1/agents/seed", headers=headers)
    task_body = client.post("/api/v1/tasks", headers=headers, json={"title":"attempt unapproved tool"}).json()
    db = TestingSession()
    from backend.nova_api.auth import digest
    owner = db.query(ApiKey).filter_by(key_hash=digest(headers["Authorization"].split()[1])).first()
    agent = db.query(Agent).filter_by(owner_key_id=owner.id, identity="qa").first()
    task = db.get(Task, task_body["id"])
    try:
        asyncio.run(run_worker_tool_requests(
            db, owner, agent, task, "try tool", json.dumps({"tool_calls":[{"name":"math.calculate","arguments":{"expression":"2+2"}}]}), "mock", "model"
        ))
        assert False, "a worker without permission must not execute the tool"
    except HTTPException as error:
        assert error.status_code == 403
    assert db.query(ActivityEvent).filter_by(task_id=task.id, event_type="tool.denied").count() == 1
    assert db.query(AgentMessage).filter_by(task_id=task.id, message_type="tool_result").count() == 0
    db.close()


def test_manager_plan_failure_is_not_reported_as_success(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    owner = auth_key(["agents:read", "agents:write", "tasks:read", "tasks:write", "providers:read", "providers:write"])
    client.post("/api/v1/agents/seed", headers=owner)
    db = TestingSession()
    for agent in db.query(Agent).all(): agent.model_id = "test-live-model"
    db.commit(); db.close()
    client.post("/api/v1/providers/connections", headers=owner, json={"provider":"groq", "api_key":"synthetic-provider-secret", "model_ids":["test-live-model"]})
    task = client.post("/api/v1/tasks", headers=owner, json={"title":"task without provider plan"}).json()
    async def fake_complete(*args, **kwargs): return "not a JSON plan", "test-live-model", None, None
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    response = client.post(f"/api/v1/tasks/{task['id']}/execute", headers=owner)
    assert response.status_code == 502
    db = TestingSession(); stored = db.get(Task, task["id"])
    assert stored.state == "FAILED" and "invalid delegation plan" in stored.error_detail
    assert db.query(TaskAssignment).filter_by(task_id=task["id"]).count() == 0
    db.close()


def test_owner_request_budget_blocks_before_provider_call():
    owner = auth_key(["tasks:read", "tasks:write"])
    db = TestingSession()
    api = db.query(ApiKey).filter_by(key_hash=digest(owner["Authorization"].split()[1])).first()
    db.add(ResourceSetting(owner_key_id=api.id, daily_request_budget=1))
    db.add(UsageRecord(owner_key_id=api.id, provider="groq", model_id="model-x", status="failed"))
    db.commit()
    try:
        __import__("asyncio").run(routing.invoke_owner(db, api, "blocked"))
        assert False, "budget should block before provider selection"
    except HTTPException as error:
        assert error.status_code == 429 and "budget" in error.detail
    finally:
        db.close()
