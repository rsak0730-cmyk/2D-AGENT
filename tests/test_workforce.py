import asyncio
import json
import pytest
from types import SimpleNamespace
from fastapi import HTTPException
from backend.nova_api import credentials, providers, routing, workforce
from backend.nova_api.models import ActivityEvent, Agent, AgentMemory, AgentMessage, ApiKey, ProviderConnection, ResourceSetting, Task, TaskAssignment, UsageRecord
from backend.nova_api.auth import digest
from backend.nova_api.workforce import TaskCancelled, check_task_cancelled, run_worker_tool_requests
from conftest import TestingSession, auth_key, client


def save_mock_gateway(monkeypatch, headers, model_id):
    gateway_config = SimpleNamespace(gateway_allowed_hosts="gateway.example.test", provider_base_url="https://arena.test/v1")
    monkeypatch.setattr(workforce, "get_settings", lambda: gateway_config)
    monkeypatch.setattr(providers, "get_settings", lambda: SimpleNamespace(**gateway_config.__dict__, request_timeout_seconds=5, max_completion_tokens=128, max_prompt_characters=60000))
    response = client.post("/api/v1/providers/connections", headers=headers, json={
        "provider": "openai_compatible", "api_key": "restricted-gateway-token-test",
        "endpoint": "https://gateway.example.test/v1", "model_ids": [model_id],
    })
    assert response.status_code == 200, response.text
    return response.json()


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
    gateway_config = SimpleNamespace(gateway_allowed_hosts="litellm.example.test", provider_base_url="https://arena.test/v1")
    monkeypatch.setattr(workforce, "get_settings", lambda: gateway_config)
    monkeypatch.setattr(providers, "get_settings", lambda: SimpleNamespace(**gateway_config.__dict__, request_timeout_seconds=5, max_completion_tokens=128, max_prompt_characters=60000))
    owner = auth_key(["providers:read", "providers:write"])
    response = client.post("/api/v1/providers/connections", headers=owner, json={"provider":"openai_compatible", "api_key":"restricted-gateway-test-token", "endpoint":"https://litellm.example.test/v1", "model_ids":["model-groq"], "priority":1})
    assert response.status_code == 200, response.text
    saved = response.json()
    db = TestingSession(); row = db.get(ProviderConnection, saved["id"])
    assert row.encrypted_api_key != "restricted-gateway-test-token"
    assert credentials.decrypt_secret(row.encrypted_api_key) == "restricted-gateway-test-token"
    db.close()

    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None, provider="arena", **kwargs):
        if provider == "openai_compatible": raise HTTPException(429, "rate limited")
        return "actual fallback response", "model-groq", 11, 7
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    monkeypatch.setattr(routing, "get_settings", lambda: SimpleNamespace(provider_api_key="environment-key", provider="arena", model="model-groq", provider_base_url="https://arena.test/v1"))
    db = TestingSession(); api = db.query(ApiKey).filter_by(key_hash=digest(owner["Authorization"].split()[1])).first()
    result = __import__("asyncio").run(routing.invoke_owner(db, api, "hello", "model-groq"))
    db.close()
    assert result == ("actual fallback response", "arena", "model-groq")
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
    connection = save_mock_gateway(monkeypatch, owner, "test-live-model")
    office_state = client.get("/api/v1/office/state", headers=owner).json()
    rooms = {room["id"]: room for room in office_state["layout"]["rooms"]}
    from backend.nova_api.models import OfficeAgentPosition
    positions_db = TestingSession()
    for worker_identity, room_id in (("security", "security"), ("qa", "testing")):
        worker = next(item for item in seeded.json() if item["identity"] == worker_identity)
        pos = positions_db.query(OfficeAgentPosition).filter_by(agent_id=worker["id"]).one()
        entrance = rooms[room_id]["entrance"]
        pos.x, pos.y = entrance["x"], entrance["y"]
    positions_db.commit(); positions_db.close()
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
    from backend.nova_api.models import OfficeAgentPosition, OfficeMove
    worker_ids = {assignment.agent_id for assignment in assignments}
    assert all(db.query(OfficeAgentPosition).filter_by(agent_id=agent_id).first() for agent_id in worker_ids)
    assert db.query(ActivityEvent).filter(ActivityEvent.task_id == task_result.json()["id"], ActivityEvent.event_type == "employee.movement.workstation_requested").count() == 2
    assert db.query(OfficeMove).filter(OfficeMove.agent_id.in_(worker_ids)).count() <= 2
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
    save_mock_gateway(monkeypatch, owner, "test-live-model")
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
    from datetime import datetime, timezone
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    owner = auth_key(["agents:read", "agents:write", "tasks:read", "tasks:write", "providers:read", "providers:write"])
    client.post("/api/v1/agents/seed", headers=owner)
    db = TestingSession()
    for agent in db.query(Agent).all(): agent.model_id = "test-live-model"
    db.commit(); db.close()
    save_mock_gateway(monkeypatch, owner, "test-live-model")
    task = client.post("/api/v1/tasks", headers=owner, json={"title":"task without provider plan"}).json()
    async def fake_complete(*args, **kwargs): return "not a JSON plan", "test-live-model", None, None
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    response = client.post(f"/api/v1/tasks/{task['id']}/execute", headers=owner)
    assert response.status_code == 502
    db = TestingSession(); stored = db.get(Task, task["id"])
    assert stored.state == "FAILED" and "invalid delegation plan" in stored.error_detail
    assert stored.next_retry_at is not None and stored.next_retry_at.replace(tzinfo=timezone.utc) > datetime.now(timezone.utc)
    assert db.query(TaskAssignment).filter_by(task_id=task["id"]).count() == 0
    db.close()
    retry=client.post(f"/api/v1/tasks/{task['id']}/execute",headers=owner)
    assert retry.status_code==429 and int(retry.headers["Retry-After"])>=1


def test_pending_task_can_be_cancelled_and_cannot_be_cancelled_twice():
    headers = auth_key(["tasks:read", "tasks:write"])
    created = client.post("/api/v1/tasks", headers=headers, json={"title":"cancel me"})
    task_id = created.json()["id"]
    other_owner = auth_key(["tasks:read", "tasks:write"])
    assert client.post(f"/api/v1/tasks/{task_id}/cancel", headers=other_owner).status_code == 404
    cancelled = client.post(f"/api/v1/tasks/{task_id}/cancel", headers=headers)
    assert cancelled.status_code == 200 and cancelled.json()["state"] == "CANCELLED"
    assert cancelled.json()["checkpoint"]["cancel_requested"] is True
    assert client.post(f"/api/v1/tasks/{task_id}/cancel", headers=headers).status_code == 409
    assert client.post(f"/api/v1/tasks/{task_id}/execute", headers=headers).status_code == 409


def test_running_task_observes_owner_cancellation_flag_between_provider_calls():
    headers = auth_key(["tasks:read", "tasks:write"])
    created = client.post("/api/v1/tasks", headers=headers, json={"title":"cancel during execution"})
    task_id = created.json()["id"]
    first = TestingSession(); task = first.get(Task, task_id)
    second = TestingSession(); persisted = second.get(Task, task_id)
    task.state = "RUNNING"; first.commit()
    persisted.checkpoint = {"cancel_requested": True}; second.commit()
    try:
        with pytest.raises(TaskCancelled):
            check_task_cancelled(first, task)
        response = client.post(f"/api/v1/tasks/{task_id}/cancel", headers=headers)
        assert response.status_code == 200 and response.json()["state"] == "RUNNING"
    finally:
        first.close(); second.close()


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


def test_direct_agent_chat_uses_agent_route_and_persists_owner_scoped_history(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    owner = auth_key(["agents:read", "agents:write", "providers:read", "providers:write", "events:read"])
    seeded = client.post("/api/v1/agents/seed", headers=owner)
    assert seeded.status_code == 200
    agent = next(row for row in seeded.json() if row["identity"] == "research")
    db = TestingSession(); saved_agent = db.get(Agent, agent["id"]); saved_agent.model_id = "direct-chat-model"; db.commit(); db.close()
    save_mock_gateway(monkeypatch, owner, "direct-chat-model")
    calls = []
    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None, provider="arena", system=None, **kwargs):
        calls.append({"message":message,"model":model,"history":history,"provider":provider,"system":system})
        return f"Provider reply {len(calls)}", model, 9, 3
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)

    first = client.post(f"/api/v1/agents/{agent['id']}/chat", headers=owner, json={"message":"Summarize the research notes"})
    assert first.status_code == 200, first.text
    first_body = first.json()
    assert first_body["response"] == "Provider reply 1" and first_body["provider"] == "openai_compatible"
    second = client.post(f"/api/v1/agents/{agent['id']}/chat", headers=owner, json={"message":"What was my request?", "conversation_id":first_body["conversation_id"]})
    assert second.status_code == 200, second.text
    assert calls[0]["model"] == "direct-chat-model" and calls[0]["provider"] == "openai_compatible"
    assert [item["role"] for item in calls[1]["history"]] == ["user", "assistant"]
    assert calls[1]["history"][0]["content"] == "Summarize the research notes"
    history = client.get(f"/api/v1/agents/{agent['id']}/chat", headers=owner).json()
    assert history["conversation_id"] == first_body["conversation_id"]
    assert [item["role"] for item in history["messages"]] == ["user", "assistant", "user", "assistant"]
    assert history["messages"][1]["provider"] == "openai_compatible"

    stranger = auth_key(["agents:read", "agents:write"])
    assert client.get(f"/api/v1/agents/{agent['id']}/chat", headers=stranger).status_code == 404
    assert client.post(f"/api/v1/agents/{agent['id']}/chat", headers=stranger, json={"message":"cross-owner", "conversation_id":first_body["conversation_id"]}).status_code == 404
    assert client.get(f"/api/v1/agents/{agent['id']}/chat?conversation_id={first_body['conversation_id']}", headers=stranger).status_code == 404


def test_workforce_analytics_reports_only_owner_records_and_measured_values():
    from datetime import datetime, timedelta, timezone
    headers = auth_key(["agents:read", "agents:write"])
    own_agents = client.post("/api/v1/agents/seed", headers=headers).json()
    agent = next(item for item in own_agents if item["identity"] == "qa")
    other_headers = auth_key(["agents:read", "agents:write"])
    other_agents = client.post("/api/v1/agents/seed", headers=other_headers).json()
    other_agent = next(item for item in other_agents if item["identity"] == "qa")
    now = datetime.now(timezone.utc)
    db = TestingSession()
    completed_task = Task(owner_key_id=db.query(ApiKey).filter_by(key_hash=digest(headers["Authorization"].split()[1])).one().id, title="completed")
    failed_task = Task(owner_key_id=completed_task.owner_key_id, title="failed")
    outsider_task = Task(owner_key_id=db.query(ApiKey).filter_by(key_hash=digest(other_headers["Authorization"].split()[1])).one().id, title="other")
    db.add_all([completed_task, failed_task, outsider_task]); db.flush()
    db.add_all([
        TaskAssignment(task_id=completed_task.id, agent_id=agent["id"], subtask_key="a", title="test work", instructions="", status="COMPLETED", started_at=now-timedelta(seconds=28), completed_at=now),
        TaskAssignment(task_id=failed_task.id, agent_id=agent["id"], subtask_key="b", title="failed work", instructions="", status="FAILED", started_at=now-timedelta(seconds=2), completed_at=now),
        TaskAssignment(task_id=outsider_task.id, agent_id=other_agent["id"], subtask_key="x", title="private work", instructions="", status="COMPLETED", started_at=now-timedelta(days=1), completed_at=now),
        UsageRecord(owner_key_id=completed_task.owner_key_id, agent_id=agent["id"], provider="openai_compatible", model_id="m", input_tokens=12, output_tokens=4, status="success"),
        UsageRecord(owner_key_id=completed_task.owner_key_id, agent_id=agent["id"], provider="openai_compatible", model_id="m", status="failed"),
        ActivityEvent(owner_key_id=completed_task.owner_key_id, agent_id=agent["id"], event_type="task.completed", payload={}),
    ])
    db.commit(); db.close()
    response = client.get("/api/v1/analytics/workforce", headers=headers)
    assert response.status_code == 200, response.text
    analytics = next(row for row in response.json()["agents"] if row["agent_id"] == agent["id"])
    assert analytics["assignments_recorded"] == 2
    assert analytics["completed_assignments"] == 1 and analytics["failed_assignments"] == 1
    assert analytics["average_completed_assignment_seconds"] == 28.0
    assert analytics["provider_requests_recorded"] == 2
    assert analytics["provider_requests_succeeded"] == 1 and analytics["provider_requests_failed"] == 1
    assert analytics["reported_input_tokens"] == 12 and analytics["reported_output_tokens"] == 4
    assert analytics["last_recorded_event"]["type"] == "task.completed"
    assert all(row["agent_id"] != other_agent["id"] for row in response.json()["agents"])
    assert "No inferred productivity score" in response.json()["method_note"]


def test_task_plan_executes_independent_assignments_in_parallel_then_waits_for_dependencies(monkeypatch):
    from cryptography.fernet import Fernet
    encryption_key = Fernet.generate_key().decode()
    monkeypatch.setattr(credentials, "get_settings", lambda: SimpleNamespace(credential_encryption_key=encryption_key))
    scopes = ["agents:read", "agents:write", "tasks:read", "tasks:write", "providers:read", "providers:write", "events:read"]
    owner = auth_key(scopes)
    seeded = client.post("/api/v1/agents/seed", headers=owner).json()
    db = TestingSession()
    for agent in db.query(Agent).all(): agent.model_id = "parallel-model"
    db.add(ResourceSetting(owner_key_id=db.query(ApiKey).filter_by(key_hash=digest(owner["Authorization"].split()[1])).one().id, max_concurrency=2, max_active_tasks=3))
    db.commit(); db.close()
    save_mock_gateway(monkeypatch, owner, "parallel-model")
    task = client.post("/api/v1/tasks", headers=owner, json={"title":"Parallel dependency test"}).json()
    gate = asyncio.Event()
    calls=[]; running=0; peak=0
    plan={"subtasks":[
        {"key":"security-review","agent":"security","title":"Review security","instructions":"Check auth"},
        {"key":"qa-review","agent":"qa","title":"Review tests","instructions":"Check tests"},
        {"key":"final-check","agent":"backend","title":"Integrate reviews","instructions":"Use both reports","depends_on":["security-review","qa-review"]},
    ]}
    async def fake_complete(message, model=None, history=None, api_key=None, base_url=None, provider="arena", system=None, **kwargs):
        nonlocal running, peak
        if "Return JSON only" in message:
            return json.dumps(plan), model, 2, 1
        if "Review these actual worker reports" in message:
            return "Final report contains real worker outputs.", model, 4, 2
        calls.append(message); running += 1; peak=max(peak,running)
        if len(calls) >= 2: gate.set()
        try:
            await asyncio.wait_for(gate.wait(), timeout=1)
            await asyncio.sleep(0.005)
            if "Integrate reviews" in message:
                assert "security-review" in message and "qa-review" in message
                assert "report from security" in message and "report from qa" in message
            identity = "security" if "Check auth" in message else "qa" if "Check tests" in message else "backend"
            return f"report from {identity}", model, 3, 1
        finally: running -= 1
    monkeypatch.setattr(routing, "complete_detailed", fake_complete)
    executed=client.post(f"/api/v1/tasks/{task['id']}/execute",headers=owner)
    assert executed.status_code==200,executed.text
    assert executed.json()["state"]=="COMPLETED"
    assert peak==2, f"expected two simultaneous provider calls, got peak {peak}"
    assert executed.json()["checkpoint"]["progress_percent"]==100
    db=TestingSession(); assignments=db.query(TaskAssignment).filter_by(task_id=task["id"]).all()
    by_key={item.subtask_key:item for item in assignments}
    assert by_key["final-check"].depends_on==["security-review","qa-review"]
    assert all(item.status=="COMPLETED" and item.lease_owner is None for item in assignments)
    events=db.query(ActivityEvent).filter_by(task_id=task["id"],event_type="task.progress").all()
    assert len(events)>=4
    db.close()


def test_task_dependencies_are_owner_scoped_and_gate_execution():
    headers=auth_key(["tasks:read","tasks:write"])
    parent=client.post("/api/v1/tasks",headers=headers,json={"title":"prerequisite"}).json()
    outsider=auth_key(["tasks:read","tasks:write"])
    assert client.post("/api/v1/tasks",headers=outsider,json={"title":"cannot depend across owners","dependencies":[parent["id"]]}).status_code==422
    child=client.post("/api/v1/tasks",headers=headers,json={"title":"dependent","dependencies":[parent["id"]]})
    assert child.status_code==200,child.text
    blocked=client.post(f"/api/v1/tasks/{child.json()['id']}/execute",headers=headers)
    assert blocked.status_code==409 and "dependencies" in blocked.json()["detail"]
    db=TestingSession(); source=db.get(Task,parent["id"]);source.state="COMPLETED";db.commit();db.close()
    after_parent=client.post(f"/api/v1/tasks/{child.json()['id']}/execute",headers=headers)
    assert after_parent.status_code==409 and "Seed the Head Manager" in after_parent.json()["detail"]  # dependency passed; workforce is deliberately absent


def test_expired_task_lease_recovers_and_reassigns_only_unfinished_work(monkeypatch):
    from datetime import datetime, timedelta, timezone
    from cryptography.fernet import Fernet
    encryption_key=Fernet.generate_key().decode()
    monkeypatch.setattr(credentials,"get_settings",lambda:SimpleNamespace(credential_encryption_key=encryption_key))
    owner=auth_key(["agents:read","agents:write","tasks:read","tasks:write","providers:read","providers:write","events:read"])
    roster=client.post("/api/v1/agents/seed",headers=owner).json()
    db=TestingSession()
    for agent in db.query(Agent).all():agent.model_id="recovery-model"
    owner_id=db.query(ApiKey).filter_by(key_hash=digest(owner["Authorization"].split()[1])).one().id
    manager=db.query(Agent).filter_by(identity="manager",owner_key_id=owner_id).one()
    old_worker=db.query(Agent).filter_by(identity="security",owner_key_id=owner_id).one()
    old_worker.status="working"
    task=Task(owner_key_id=db.query(ApiKey).filter_by(key_hash=digest(owner["Authorization"].split()[1])).one().id,title="recover me",state="RUNNING",attempt_count=1,max_attempts=3,lease_owner="dead-worker",lease_expires_at=datetime.now(timezone.utc)-timedelta(minutes=2),checkpoint={"stage":"workers_running","progress_percent":40})
    db.add(task);db.flush()
    old_assignment=TaskAssignment(task_id=task.id,agent_id=old_worker.id,subtask_key="old-attempt-work",title="unfinished prior attempt",instructions="",status="RUNNING",lease_owner="dead-worker:old-attempt-work",lease_expires_at=task.lease_expires_at)
    done_worker=db.query(Agent).filter_by(identity="web",owner_key_id=owner_id).one()
    done_assignment=TaskAssignment(task_id=task.id,agent_id=done_worker.id,subtask_key="already-done",title="Done before crash",instructions="Keep this completed work",depends_on=[],status="COMPLETED",result="Preserved result from completed worker",completed_at=datetime.now(timezone.utc)-timedelta(minutes=3))
    db.add_all([old_assignment,done_assignment]);db.commit();task_id=task.id;old_assignment_id=old_assignment.id;done_assignment_id=done_assignment.id;db.close()
    save_mock_gateway(monkeypatch,owner,"recovery-model")
    prompts=[]
    async def fake_complete(message,model=None,history=None,api_key=None,base_url=None,provider="arena",system=None,**kwargs):
        prompts.append(message)
        if "Return JSON only" in message:
            return json.dumps({"subtasks":[{"key":"already-done","agent":"web","title":"Done before crash", "instructions":"Keep this completed work"},{"key":"retry-work","agent":"qa","title":"retry", "instructions":"Finish the interrupted work"}]}),model,2,1
        if "Review these actual worker reports" in message:return "Recovered with a new worker result.",model,3,2
        return "QA reassigned result",model,4,1
    monkeypatch.setattr(routing,"complete_detailed",fake_complete)
    response=client.post(f"/api/v1/tasks/{task_id}/execute",headers=owner)
    assert response.status_code==200,response.text
    assert response.json()["state"]=="COMPLETED" and response.json()["attempt_count"]==2
    db=TestingSession();old=db.get(TaskAssignment,old_assignment_id);preserved=db.get(TaskAssignment,done_assignment_id)
    fresh=db.query(TaskAssignment).filter_by(task_id=task_id,subtask_key="retry-work").one()
    assert old.status=="FAILED" and old.error_detail.startswith("Worker lease expired")
    assert preserved.status=="COMPLETED" and preserved.result=="Preserved result from completed worker"
    assert fresh.status=="COMPLETED" and fresh.agent_id!=old_worker.id
    assert db.query(ActivityEvent).filter_by(task_id=task_id,event_type="task.lease_expired").count()==1
    assert "Finish the interrupted work" in prompts[-2]
    assert "Preserved result from completed worker" in prompts[-1]
    assert db.query(ActivityEvent).filter_by(task_id=task_id,event_type="task.assignment_reused").count()==1
    db.close()


def test_separate_database_queue_worker_dispatches_a_ready_task(monkeypatch):
    from cryptography.fernet import Fernet
    from backend.nova_api.queue_worker import run_worker_once
    encryption_key=Fernet.generate_key().decode()
    monkeypatch.setattr(credentials,"get_settings",lambda:SimpleNamespace(credential_encryption_key=encryption_key))
    headers=auth_key(["agents:read","agents:write","tasks:read","tasks:write","providers:read","providers:write","events:read"])
    seeded=client.post("/api/v1/agents/seed",headers=headers).json()
    db=TestingSession();owner_id=db.query(ApiKey).filter_by(key_hash=digest(headers["Authorization"].split()[1])).one().id
    for agent in db.query(Agent).filter_by(owner_key_id=owner_id).all():agent.model_id="queue-model"
    db.commit();db.close()
    save_mock_gateway(monkeypatch,headers,"queue-model")
    prerequisite=client.post("/api/v1/tasks",headers=headers,json={"title":"queue prerequisite"}).json()
    task=client.post("/api/v1/tasks",headers=headers,json={"title":"worker queue test","dependencies":[prerequisite["id"]]}).json()
    async def fake_complete(message,model=None,history=None,api_key=None,base_url=None,provider="arena",system=None,**kwargs):
        if "Return JSON only" in message:return json.dumps({"subtasks":[{"key":"queued-work","agent":"qa","title":"Execute queued work","instructions":"Do the real assigned work"}]}),model,2,1
        if "Review these actual worker reports" in message:return "Queue worker synthesis",model,3,1
        return "Queue worker assignment result",model,4,2
    monkeypatch.setattr(routing,"complete_detailed",fake_complete)
    assert asyncio.run(run_worker_once(TestingSession, owner_id))==1
    assert client.get(f"/api/v1/tasks/{task['id']}",headers=headers).json()["state"]=="RECEIVED"
    assert client.get(f"/api/v1/tasks/{prerequisite['id']}",headers=headers).json()["state"]=="COMPLETED"
    assert asyncio.run(run_worker_once(TestingSession, owner_id))==1
    assert asyncio.run(run_worker_once(TestingSession, owner_id))==0
    stored=client.get(f"/api/v1/tasks/{task['id']}",headers=headers)
    assert stored.status_code==200 and stored.json()["state"]=="COMPLETED"
    assert stored.json()["result"]=="Queue worker synthesis"


def test_agent_pause_resume_and_stop_controls_are_owner_scoped():
    owner=auth_key(["agents:read","agents:write"])
    agents=client.post("/api/v1/agents/seed",headers=owner).json()
    worker=next(agent for agent in agents if agent["identity"]=="qa")
    outsider=auth_key(["agents:write"])
    assert client.post(f"/api/v1/agents/{worker['id']}/pause",headers=outsider).status_code==404
    paused=client.post(f"/api/v1/agents/{worker['id']}/pause",headers=owner)
    assert paused.status_code==200 and paused.json()["status"]=="paused"
    assert client.post(f"/api/v1/agents/{worker['id']}/pause",headers=owner).status_code==200
    resumed=client.post(f"/api/v1/agents/{worker['id']}/resume",headers=owner)
    assert resumed.status_code==200 and resumed.json()["status"]=="idle"
    stopped=client.post(f"/api/v1/agents/{worker['id']}/stop",headers=owner)
    assert stopped.status_code==200 and stopped.json()["task_ids"]==[] and stopped.json()["cooperative"] is False
