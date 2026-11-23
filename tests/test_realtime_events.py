import asyncio

from backend.nova_api import main
from backend.nova_api.auth import digest
from backend.nova_api.models import ActivityEvent, Agent, ApiKey, Meeting
from conftest import TestingSession, auth_key, client


def _key_id(headers):
    raw = headers["Authorization"].split(" ", 1)[1]
    db = TestingSession()
    try:
        return db.query(ApiKey).filter_by(key_hash=digest(raw)).one().id
    finally:
        db.close()


def test_websocket_reconnect_snapshot_contains_only_authenticated_owners_state():
    owner_a = auth_key(["events:read", "agents:read"])
    owner_b = auth_key(["events:read", "agents:read"])
    owner_a_id, owner_b_id = _key_id(owner_a), _key_id(owner_b)
    db = TestingSession()
    try:
        db.add_all([
            Agent(owner_key_id=owner_a_id, identity="a-role", name="A worker", job_title="Engineer"),
            Agent(owner_key_id=owner_b_id, identity="b-role", name="B worker", job_title="Engineer"),
            ActivityEvent(owner_key_id=owner_a_id, event_type="a.private", payload={"note": "owner-a-only"}),
            ActivityEvent(owner_key_id=owner_b_id, event_type="b.private", payload={"note": "owner-b-only"}),
            Meeting(owner_key_id=owner_a_id,title="A meeting",agenda="Discuss A",project_context="private A",status="scheduled"),
            Meeting(owner_key_id=owner_b_id,title="B meeting",agenda="Discuss B",project_context="private B",status="scheduled"),
        ])
        db.commit()
    finally:
        db.close()

    with client.websocket_connect("/api/v1/events", headers=owner_a) as socket:
        snapshot = socket.receive_json()
    assert snapshot["type"] == "snapshot"
    assert [agent["identity"] for agent in snapshot["agents"]] == ["a-role"]
    assert {event["type"] for event in snapshot["events"]} == {"a.private"}
    assert [meeting["title"] for meeting in snapshot["meetings"]] == ["A meeting"]
    assert "owner-b-only" not in str(snapshot) and "private B" not in str(snapshot)


def test_live_event_publisher_is_owner_scoped_and_never_serializes_owner_id():
    class RecordingSocket:
        def __init__(self):
            self.events = []

        async def send_json(self, event):
            self.events.append(event)

    first, second = RecordingSocket(), RecordingSocket()
    original = main.clients
    main.clients = {first: "owner-a", second: "owner-b"}
    try:
        asyncio.run(main.publish({"owner_key_id": "owner-a", "type": "agent.started", "agent_id": "agent-a"}))
    finally:
        main.clients = original
    assert first.events == [{"type": "agent.started", "agent_id": "agent-a"}]
    assert second.events == []
    assert "owner_key_id" not in first.events[0]


def test_shared_event_bus_is_disabled_for_local_sqlite_and_dedupes_event_ids():
    from backend.nova_api.event_bus import PostgresEventBus
    bus=PostgresEventBus("sqlite:///./local.db")
    assert bus.enabled is False
    assert bus.was_seen_locally("event-1") is False
    bus.mark_local("event-1")
    assert bus.was_seen_locally("event-1") is True
