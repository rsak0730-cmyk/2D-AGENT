from conftest import TestingSession, auth_key, client
from backend.nova_api.auth import digest
from backend.nova_api.models import Agent, ApiKey, OfficeAgentPosition, OfficeLayout, OfficeMove


def _owner_id(headers):
    raw=headers["Authorization"].split(" ",1)[1]
    db=TestingSession()
    try: return db.query(ApiKey).filter_by(key_hash=digest(raw)).one().id
    finally: db.close()


def test_office_state_is_persisted_owner_scoped_and_expandable():
    owner=auth_key(["agents:read","agents:write","office:read","office:write"])
    other=auth_key(["agents:read","agents:write","office:read","office:write"])
    seeded=client.post("/api/v1/agents/seed",headers=owner)
    assert seeded.status_code==200
    response=client.get("/api/v1/office/state",headers=owner)
    assert response.status_code==200,response.text
    state=response.json()
    assert len(state["layout"]["rooms"])==12
    assert len(state["agents"])==11
    assert len({(item["position"]["x"],item["position"]["y"]) for item in state["agents"]})==11
    assert {room["name"] for room in state["layout"]["rooms"]} >= {"Head Office","Development","Design","Research","Security","Testing","Writing","Meeting Room","Server Room","Reception","Break Room","Memory Core"}
    assert any(item["kind"] == "memory_core" for item in state["layout"]["furniture"])
    assert client.get("/api/v1/office/state",headers=other).json()["agents"]==[]
    db=TestingSession()
    try:
        owner_id=_owner_id(owner)
        assert db.query(OfficeLayout).filter_by(owner_key_id=owner_id).count()==1
        assert db.query(OfficeAgentPosition).filter_by(owner_key_id=owner_id).count()==11
    finally: db.close()


def test_office_layout_rejects_furniture_overlap_and_keeps_entrances_walkable():
    owner=auth_key(["agents:read","agents:write","office:read","office:write"])
    client.post("/api/v1/agents/seed",headers=owner)
    state=client.get("/api/v1/office/state",headers=owner).json()
    layout=state["layout"]
    room=layout["rooms"][0]
    entrance=room["entrance"]
    bad={"width":layout["width"],"height":layout["height"],"rooms":layout["rooms"],"furniture":[{"id":"blocks-door","kind":"desk","x":entrance["x"],"y":entrance["y"],"width":1,"height":1,"rotation":0}]}
    rejected=client.put("/api/v1/office/layout",headers=owner,json=bad)
    assert rejected.status_code==422
    valid={"width":layout["width"],"height":layout["height"],"rooms":layout["rooms"],"furniture":layout["furniture"]}
    saved=client.put("/api/v1/office/layout",headers=owner,json=valid)
    assert saved.status_code==200,saved.text
    assert len(saved.json()["layout"]["rooms"])==12


def test_office_move_steps_persist_position_and_emit_owner_event():
    owner=auth_key(["agents:read","agents:write","office:read","office:write","events:read"])
    client.post("/api/v1/agents/seed",headers=owner)
    state=client.get("/api/v1/office/state",headers=owner).json()
    manager=next(agent for agent in state["agents"] if agent["identity"]=="manager")
    target={"x":8,"y":4}
    requested=client.post(f"/api/v1/office/agents/{manager['id']}/move",headers=owner,json=target)
    assert requested.status_code==200,requested.text
    info=requested.json()
    assert info["state"]=="walking" and info["route"][-1]==target
    for _ in range(len(info["route"])-1):
        step=client.post(f"/api/v1/office/agents/{manager['id']}/step",headers=owner)
        assert step.status_code==200,step.text
    refreshed=client.get("/api/v1/office/state",headers=owner).json()
    moved=next(agent for agent in refreshed["agents"] if agent["id"]==manager["id"])
    assert moved["position"]==target and moved["movement"]["state"]=="idle"
    events=client.get("/api/v1/events/recent",headers=owner).json()
    assert any(event["event_type"]=="employee.movement.step" for event in events)
    db=TestingSession()
    try:
        row=db.query(OfficeAgentPosition).filter_by(agent_id=manager["id"]).one()
        assert (row.x,row.y)==(8,4)
        assert db.query(OfficeMove).filter_by(agent_id=manager["id"],state="completed").count()==1
    finally: db.close()


def test_office_mutations_require_office_write_scope():
    owner=auth_key(["agents:read","agents:write","office:read"])
    client.post("/api/v1/agents/seed",headers=owner)
    state=client.get("/api/v1/office/state",headers=owner)
    assert state.status_code==200
    agent_id=state.json()["agents"][0]["id"]
    response=client.post(f"/api/v1/office/agents/{agent_id}/move",headers=owner,json={"x":1,"y":1})
    assert response.status_code==403


def test_legacy_default_office_get_adds_memory_core_without_moving_workers():
    owner=auth_key(["agents:read","agents:write","office:read","office:write"])
    seeded=client.post("/api/v1/agents/seed",headers=owner).json()
    client.get("/api/v1/office/state",headers=owner)
    database_agent=next(agent for agent in seeded if agent["identity"]=="database")
    db=TestingSession()
    try:
        layout=db.query(OfficeLayout).filter_by(owner_key_id=_owner_id(owner)).one()
        layout.rooms=[room for room in layout.rooms if room["id"]!="memory-core"]
        layout.furniture=[item for item in layout.furniture if item["kind"]!="memory_core"]
        position=db.query(OfficeAgentPosition).filter_by(agent_id=database_agent["id"]).one()
        position.x,position.y=31,1
        db.commit()
    finally: db.close()
    refreshed=client.get("/api/v1/office/state",headers=owner).json()
    assert any(room["id"]=="memory-core" for room in refreshed["layout"]["rooms"])
    assert any(item["kind"]=="memory_core" for item in refreshed["layout"]["furniture"])
    saved_position=next(agent["position"] for agent in refreshed["agents"] if agent["id"]==database_agent["id"])
    assert saved_position=={"x":31,"y":1}


def test_customized_office_layout_is_not_auto_rewritten_for_memory_core():
    owner=auth_key(["agents:read","agents:write","office:read"])
    client.post("/api/v1/agents/seed",headers=owner)
    client.get("/api/v1/office/state",headers=owner)
    db=TestingSession()
    try:
        layout=db.query(OfficeLayout).filter_by(owner_key_id=_owner_id(owner)).one()
        layout.rooms=[{**room,"name":"Custom Head Office"} if room["id"]=="head-office" else room for room in layout.rooms if room["id"]!="memory-core"]
        layout.furniture=[item for item in layout.furniture if item["kind"]!="memory_core"]
        db.commit()
    finally: db.close()
    refreshed=client.get("/api/v1/office/state",headers=owner).json()
    assert len(refreshed["layout"]["rooms"])==11
    assert refreshed["layout"]["rooms"][0]["name"]=="Custom Head Office"
    assert not any(room["id"]=="memory-core" for room in refreshed["layout"]["rooms"])
