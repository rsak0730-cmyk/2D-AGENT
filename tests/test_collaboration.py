import asyncio
from datetime import datetime, timedelta, timezone

from backend.nova_api import collaboration
from backend.nova_api.auth import digest
from backend.nova_api.models import Agent, AgentMessage, ApiKey, Meeting, Task
from conftest import TestingSession, auth_key, client


def _owner_id(headers):
    raw=headers["Authorization"].split()[1]
    db=TestingSession()
    try:return db.query(ApiKey).filter_by(key_hash=digest(raw)).one().id
    finally:db.close()


def test_meeting_invites_schedule_participation_shared_context_and_history(monkeypatch):
    owner=auth_key(["agents:read","agents:write","tasks:read","tasks:write"])
    roster=client.post("/api/v1/agents/seed",headers=owner).json()
    selected=[a for a in roster if a["identity"] in {"web","qa"}]
    task=client.post("/api/v1/tasks",headers=owner,json={"title":"Implement feature"}).json()
    scheduled=(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()
    created=client.post("/api/v1/meetings",headers=owner,json={"title":"Feature kickoff","agenda":"Review the implementation plan","participant_ids":[a["id"] for a in selected],"scheduled_at":scheduled,"project_context":"Project codename: ORBIT","task_id":task["id"]})
    assert created.status_code==200,created.text
    meeting=created.json()
    assert meeting["status"]=="scheduled" and len(meeting["invitations"])==2
    future_start=client.post(f"/api/v1/meetings/{meeting['id']}/start",headers=owner)
    assert future_start.status_code==409 and "future" in future_start.json()["detail"]
    db=TestingSession();row=db.get(Meeting,meeting["id"]);row.scheduled_at=datetime.now(timezone.utc)-timedelta(minutes=1);db.commit();db.close()
    # An explicit participant response is persisted; declined agents do not receive a provider request.
    declined=selected[0]
    response=client.post(f"/api/v1/meetings/{meeting['id']}/invitations/{declined['id']}/response",headers=owner,json={"accepted":False})
    assert response.status_code==200
    seen=[]
    async def fake_invoke(db,owner,agent,prompt,task_id,system_override=None):
        seen.append((agent.identity,prompt,task_id))
        return f"{agent.identity} actual meeting report","mocked-test-provider","meeting-model"
    monkeypatch.setattr(collaboration,"invoke_agent",fake_invoke)
    completed=client.post(f"/api/v1/meetings/{meeting['id']}/start",headers=owner)
    assert completed.status_code==200,completed.text
    body=completed.json()
    assert body["status"]=="completed"
    assert len(seen)==1 and seen[0][0]==selected[1]["identity"]
    assert "Project codename: ORBIT" in seen[0][1] and seen[0][2]==task["id"]
    assert any(message["content"]==f"{selected[1]['identity']} actual meeting report" for message in body["messages"])
    assert body["invitations"][0]["status"] in {"declined","completed"}
    assert client.post(f"/api/v1/meetings/{meeting['id']}/start",headers=owner).status_code==409
    outsider=auth_key(["agents:read"])
    assert client.get(f"/api/v1/meetings/{meeting['id']}",headers=outsider).status_code==404
    assert client.get("/api/v1/meetings",headers=outsider).json()==[]


def test_meeting_invites_are_owner_scoped_and_task_handoffs_attach_shared_context():
    owner=auth_key(["agents:read","agents:write","tasks:read","tasks:write"])
    roster=client.post("/api/v1/agents/seed",headers=owner).json()
    participant=next(agent for agent in roster if agent["identity"]=="backend")
    outsider=auth_key(["agents:read","agents:write"])
    outsider_agent=client.post("/api/v1/agents/seed",headers=outsider).json()[0]
    task=client.post("/api/v1/tasks",headers=owner,json={"title":"queued handoff"}).json()
    meeting=client.post("/api/v1/meetings",headers=owner,json={"title":"Handoff sync","agenda":"Assign follow-up","participant_ids":[participant["id"]]}).json()
    denied=client.post(f"/api/v1/meetings/{meeting['id']}/invitations",headers=owner,json={"agent_ids":[outsider_agent["id"]]})
    assert denied.status_code==404
    handoff=client.post(f"/api/v1/meetings/{meeting['id']}/handoffs",headers=owner,json={"task_id":task["id"],"agent_id":participant["id"],"instructions":"Check API pagination and add regression tests"})
    assert handoff.status_code==200 and handoff.json()["status"]=="queued_context_attached"
    db=TestingSession();stored=db.get(Task,task["id"]);message=db.query(AgentMessage).filter_by(task_id=task["id"],message_type="meeting_handoff").one()
    assert "Check API pagination" in stored.description and message.recipient_agent_id==participant["id"]
    db.close()


def test_queue_worker_starts_due_scheduled_meeting_once(monkeypatch):
    from backend.nova_api.queue_worker import run_scheduled_meetings_once
    owner=auth_key(["agents:read","agents:write"])
    roster=client.post("/api/v1/agents/seed",headers=owner).json()
    participant=next(agent for agent in roster if agent["identity"]=="qa")
    due=datetime.now(timezone.utc)-timedelta(minutes=1)
    created=client.post("/api/v1/meetings",headers=owner,json={"title":"Scheduled QA review","agenda":"Review test risks","participant_ids":[participant["id"]],"scheduled_at":due.isoformat()})
    assert created.status_code==200,created.text
    async def fake_invoke(db,owner,agent,prompt,task_id,system_override=None):
        return "Scheduled worker report","mock-provider","scheduled-model"
    monkeypatch.setattr("backend.nova_api.collaboration.invoke_agent",fake_invoke)
    assert asyncio.run(run_scheduled_meetings_once(TestingSession))==1
    assert asyncio.run(run_scheduled_meetings_once(TestingSession))==0
    assert client.get(f"/api/v1/meetings/{created.json()['id']}",headers=owner).json()["status"]=="completed"
