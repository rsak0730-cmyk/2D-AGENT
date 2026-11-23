"""Owner-scoped persisted office layout and collision-safe movement APIs."""
from collections import deque
from datetime import datetime, timezone
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from office_engine.navigation import Furniture, MoveOrder, MovementEngine, validate_furniture_layout
from .auth import require_scope
from .db import get_db
from .models import Agent, OfficeAgentPosition, OfficeLayout, OfficeMove, ApiKey
from .office_schemas import OfficeLayoutUpdate, OfficeMoveRequest
from .workforce import log_event

router = APIRouter()
ROOM_NAMES = [
    ("head-office", "Head Office"), ("development", "Development"), ("design", "Design"), ("research", "Research"),
    ("security", "Security"), ("testing", "Testing"), ("writing", "Writing"), ("meeting", "Meeting Room"),
    ("server-room", "Server Room"), ("reception", "Reception"), ("break-room", "Break Room"),
]
ROLE_ROOM = {"manager":"head-office", "android":"development", "web":"development", "game":"development", "backend":"development", "ui-ux":"design", "research":"research", "database":"research", "security":"security", "qa":"testing", "documentation":"writing"}


def _default_layout(owner_id: str) -> OfficeLayout:
    rooms, furniture = [], []
    for index, (room_id, name) in enumerate(ROOM_NAMES):
        col, row = index % 4, index // 4
        x, y = col * 10, row * 8
        room = {"id": room_id, "name": name, "x": x, "y": y, "width": 10, "height": 6, "entrance": {"x": x + 4, "y": y + 5}}
        rooms.append(room)
        if room_id == "meeting":
            furniture.append({"id":"meeting-table", "kind":"meeting_table", "x":x+3, "y":y+2, "width":3, "height":2, "rotation":0})
        elif room_id == "server-room":
            furniture.append({"id":"server-rack", "kind":"server_rack", "x":x+2, "y":y+1, "width":2, "height":1, "rotation":0})
        elif room_id == "break-room":
            furniture.append({"id":"coffee-machine", "kind":"coffee_machine", "x":x+2, "y":y+1, "width":1, "height":1, "rotation":0})
        else:
            furniture.append({"id":f"{room_id}-desk", "kind":"desk", "x":x+2, "y":y+1, "width":2, "height":1, "rotation":0})
    return OfficeLayout(owner_key_id=owner_id, width=40, height=24, rooms=rooms, furniture=furniture)


def _furniture(rows: list[dict]) -> list[Furniture]:
    return [Furniture(item["id"], item["kind"], item["x"], item["y"], item.get("width",1), item.get("height",1), item.get("rotation",0)) for item in rows]


def _layout(db: Session, owner: ApiKey) -> OfficeLayout:
    row = db.query(OfficeLayout).filter(OfficeLayout.owner_key_id == owner.id).first()
    if row is None:
        row = _default_layout(owner.id)
        db.add(row); db.flush()
    return row


def _positions(db: Session, owner: ApiKey, layout: OfficeLayout, blocked: set[tuple[int,int]]) -> list[OfficeAgentPosition]:
    rows = db.query(OfficeAgentPosition).filter(OfficeAgentPosition.owner_key_id == owner.id).all()
    by_agent = {row.agent_id: row for row in rows}
    agents = db.query(Agent).filter(Agent.owner_key_id == owner.id).order_by(Agent.created_at.asc()).all()
    occupied = {(row.x,row.y) for row in rows if row.agent_id in {agent.id for agent in agents}}
    room_by_id = {room["id"]: room for room in layout.rooms}
    for agent in agents:
        if agent.id in by_agent:
            continue
        home = room_by_id.get(ROLE_ROOM.get(agent.identity, "development"), layout.rooms[0])
        candidates = [(x,y) for y in range(home["y"]+1, home["y"]+home["height"]-1) for x in range(home["x"]+1, home["x"]+home["width"]-1)]
        candidates += [(x,y) for y in range(layout.height) for x in range(layout.width)]
        point = next((cell for cell in candidates if cell not in blocked and cell not in occupied), None)
        if point is None:
            raise HTTPException(409, "No unoccupied walkable office cell is available for this employee")
        row = OfficeAgentPosition(owner_key_id=owner.id, agent_id=agent.id, x=point[0], y=point[1])
        db.add(row); db.flush(); rows.append(row); by_agent[agent.id] = row; occupied.add(point)
    return [by_agent[agent.id] for agent in agents]


def _world(db: Session, owner: ApiKey, layout: OfficeLayout) -> tuple[MovementEngine, dict[str, OfficeMove], list[OfficeMove]]:
    entrances = [(room["entrance"]["x"], room["entrance"]["y"]) for room in layout.rooms]
    blocked = validate_furniture_layout(layout.width, layout.height, _furniture(layout.furniture), entrances)
    positions = _positions(db, owner, layout, blocked)
    engine = MovementEngine(layout.width, layout.height, blocked, {p.agent_id:(p.x,p.y) for p in positions})
    moves = db.query(OfficeMove).filter(OfficeMove.owner_key_id == owner.id, OfficeMove.state.in_(["walking","queued"])).order_by(OfficeMove.created_at.asc()).all()
    active_by_agent, queued = {}, []
    for move in moves:
        order = MoveOrder(move.agent_id, tuple(move.destination), deque(tuple(cell) for cell in (move.route or [])), move.state)
        if move.state == "walking":
            engine.active[move.agent_id] = order; active_by_agent[move.agent_id] = move
        else:
            engine.pending.append(order); queued.append(move)
    return engine, active_by_agent, queued


def _sync_moves(engine: MovementEngine, active_by_agent: dict[str, OfficeMove], queued: list[OfficeMove]):
    all_moves = {**active_by_agent, **{move.agent_id:move for move in queued}}
    for agent_id, order in engine.active.items():
        move = all_moves.get(agent_id)
        if move:
            move.state = "walking"; move.route = list(order.route)
    active_ids = set(engine.active)
    for move in queued:
        if move.agent_id not in active_ids and any(order.agent_id == move.agent_id for order in engine.pending):
            move.state = "queued"; move.route = []


def _agent_views(db: Session, owner: ApiKey, layout: OfficeLayout, positions: list[OfficeAgentPosition], moves: list[OfficeMove]):
    agents = {a.id:a for a in db.query(Agent).filter(Agent.owner_key_id == owner.id).all()}
    move_by_agent = {m.agent_id:m for m in moves}
    result = []
    for pos in positions:
        agent = agents[pos.agent_id]
        move = move_by_agent.get(pos.agent_id)
        result.append({"id":agent.id, "identity":agent.identity, "name":agent.name, "job_title":agent.job_title, "provider":agent.provider, "model_id":agent.model_id, "status":agent.status,
                       "position":{"x":pos.x,"y":pos.y}, "movement":{"state":move.state if move else "idle", "destination":{"x":move.destination[0],"y":move.destination[1]} if move else None,
                       "route":[{"x":cell[0],"y":cell[1]} for cell in (move.route or [])] if move else [], "move_id":move.id if move else None}})
    return result


def schedule_agent_workstation(db: Session, owner: ApiKey, agent_id: str, task_id: str | None = None) -> dict:
    """Route a real task-starting agent to an unoccupied cell next to its role's saved desk."""
    agent = db.query(Agent).filter(Agent.id == agent_id, Agent.owner_key_id == owner.id).first()
    if agent is None:
        return {"scheduled": False, "reason": "agent_not_found"}
    layout = _layout(db, owner)
    active_move = db.query(OfficeMove).filter(OfficeMove.owner_key_id == owner.id, OfficeMove.agent_id == agent.id, OfficeMove.state.in_(["walking", "queued"])).first()
    if active_move:
        return {"scheduled": False, "reason": "agent_already_moving"}
    room_id = ROLE_ROOM.get(agent.identity, "development")
    room = next((item for item in layout.rooms if item["id"] == room_id), layout.rooms[0])
    blocked = validate_furniture_layout(layout.width, layout.height, _furniture(layout.furniture), [(r["entrance"]["x"], r["entrance"]["y"]) for r in layout.rooms])
    positions = _positions(db, owner, layout, blocked)
    occupied = {(position.x, position.y) for position in positions if position.agent_id != agent.id}
    desks = [item for item in layout.furniture if item["kind"] == "desk" and room["x"] <= item["x"] < room["x"] + room["width"] and room["y"] <= item["y"] < room["y"] + room["height"]]
    furniture_cells = {(cell[0], cell[1]) for item in desks for cell in Furniture(item["id"], item["kind"], item["x"], item["y"], item.get("width", 1), item.get("height", 1), item.get("rotation", 0)).cells}
    candidates = [(x, y) for y in range(room["y"], room["y"] + room["height"]) for x in range(room["x"], room["x"] + room["width"]) if (x, y) not in blocked and (x, y) not in occupied]
    if not candidates:
        return {"scheduled": False, "reason": "no_free_workstation_cell"}
    destination = min(candidates, key=lambda cell: (min((abs(cell[0] - fx) + abs(cell[1] - fy) for fx, fy in furniture_cells), default=abs(cell[0] - room["entrance"]["x"]) + abs(cell[1] - room["entrance"]["y"])), cell[1], cell[0]))
    engine, active, queued = _world(db, owner, layout)
    try:
        result = engine.request_move(agent.id, destination)
    except ValueError as error:
        return {"scheduled": False, "reason": str(error)}
    if result["state"] == "arrived":
        db.commit()
        return {"scheduled": False, "reason": "already_at_workstation", "position": {"x": destination[0], "y": destination[1]}}
    route = list(engine.active[agent.id].route) if agent.id in engine.active else []
    move = OfficeMove(owner_key_id=owner.id, agent_id=agent.id, destination=list(destination), route=route, state=result["state"])
    db.add(move); db.flush()
    if result["state"] == "walking": active[agent.id] = move
    else: queued.append(move)
    _sync_moves(engine, active, queued)
    db.commit()
    log_event(db, owner.id, "employee.movement.workstation_requested", task_id=task_id, agent_id=agent.id, payload={"move_id":move.id,"room_id":room["id"],"destination":{"x":destination[0],"y":destination[1]},"state":move.state})
    return {"scheduled": True, "move_id": move.id, "state": move.state, "destination": {"x": destination[0], "y": destination[1]}}


def _state(db: Session, owner: ApiKey) -> dict:
    layout = _layout(db, owner)
    entrances = [(room["entrance"]["x"], room["entrance"]["y"]) for room in layout.rooms]
    blocked = validate_furniture_layout(layout.width, layout.height, _furniture(layout.furniture), entrances)
    positions = _positions(db, owner, layout, blocked)
    moves = db.query(OfficeMove).filter(OfficeMove.owner_key_id == owner.id, OfficeMove.state.in_(["walking","queued"])).order_by(OfficeMove.created_at.asc()).all()
    db.commit()
    return {"layout":{"width":layout.width,"height":layout.height,"rooms":layout.rooms,"furniture":layout.furniture,"updated_at":layout.updated_at.isoformat() if layout.updated_at else None},
            "agents":_agent_views(db,owner,layout,positions,moves)}


@router.get("/api/v1/office/state")
def get_office_state(db: Session = Depends(get_db), owner: ApiKey = Depends(require_scope("agents:read"))):
    return _state(db, owner)


@router.put("/api/v1/office/layout")
def save_office_layout(body: OfficeLayoutUpdate, db: Session = Depends(get_db), owner: ApiKey = Depends(require_scope("office:write"))):
    rooms, furniture = [room.model_dump() for room in body.rooms], [item.model_dump() for item in body.furniture]
    entrances = [(room["entrance"]["x"],room["entrance"]["y"]) for room in rooms]
    try:
        blocked = validate_furniture_layout(body.width, body.height, _furniture(furniture), entrances)
    except ValueError as error:
        raise HTTPException(422, str(error))
    room_cells = {(x,y) for room in rooms for x in range(room["x"],room["x"]+room["width"]) for y in range(room["y"],room["y"]+room["height"])}
    if any(cell not in room_cells for item in _furniture(furniture) for cell in item.cells):
        raise HTTPException(422, "Furniture must be placed inside a room")
    row = _layout(db,owner)
    current = db.query(OfficeAgentPosition).filter(OfficeAgentPosition.owner_key_id==owner.id).all()
    for pos in current:
        if pos.x >= body.width or pos.y >= body.height or (pos.x,pos.y) in blocked:
            raise HTTPException(409, "Layout would remove or block a saved employee position; move employees first")
        if not any(room["x"] <= pos.x < room["x"]+room["width"] and room["y"] <= pos.y < room["y"]+room["height"] for room in rooms):
            raise HTTPException(409, "Layout would remove a room containing a saved employee position")
    active = db.query(OfficeMove).filter(OfficeMove.owner_key_id==owner.id, OfficeMove.state.in_(["walking","queued"])).count()
    if active:
        raise HTTPException(409, "Finish or cancel employee movement before replacing the office layout")
    row.width,row.height,row.rooms,row.furniture=body.width,body.height,rooms,furniture
    db.commit(); db.refresh(row)
    log_event(db,owner.id,"office.layout.updated",payload={"rooms":len(rooms),"furniture":len(furniture),"width":row.width,"height":row.height})
    return _state(db,owner)


@router.post("/api/v1/office/agents/{agent_id}/move")
def move_employee(agent_id: str, body: OfficeMoveRequest, db: Session = Depends(get_db), owner: ApiKey = Depends(require_scope("office:write"))):
    if not db.query(Agent).filter(Agent.id==agent_id,Agent.owner_key_id==owner.id).first():
        raise HTTPException(404,"Employee not found")
    layout = _layout(db,owner)
    try:
        blocked = validate_furniture_layout(layout.width,layout.height,_furniture(layout.furniture),[(r["entrance"]["x"],r["entrance"]["y"]) for r in layout.rooms])
        positions = _positions(db,owner,layout,blocked)
        engine, active, queued = _world(db,owner,layout)
        result = engine.request_move(agent_id,(body.x,body.y))
    except ValueError as error:
        raise HTTPException(409,str(error))
    if result["state"] == "arrived":
        db.commit()
        return {"agent_id":agent_id,**result}
    route = list(engine.active[agent_id].route) if agent_id in engine.active else []
    move = OfficeMove(owner_key_id=owner.id,agent_id=agent_id,destination=[body.x,body.y],route=route,state=result["state"])
    db.add(move); db.flush()
    if result["state"] == "walking": active[agent_id]=move
    else: queued.append(move)
    _sync_moves(engine,active,queued)
    db.commit()
    log_event(db,owner.id,"employee.movement.started" if move.state=="walking" else "employee.movement.queued",agent_id=agent_id,payload={"move_id":move.id,"destination":{"x":body.x,"y":body.y}})
    return {"move_id":move.id,"agent_id":agent_id,"state":move.state,"route":[{"x":x,"y":y} for x,y in result["route"]]}


@router.post("/api/v1/office/agents/{agent_id}/step")
def advance_employee(agent_id: str, db: Session = Depends(get_db), owner: ApiKey = Depends(require_scope("office:write"))):
    layout = _layout(db,owner)
    try:
        engine, active, queued = _world(db,owner,layout)
        move = active.get(agent_id)
        if move is None:
            raise HTTPException(409,"Employee has no active movement")
        outcome=engine.advance(agent_id)
    except ValueError as error:
        raise HTTPException(409,str(error))
    for pos in db.query(OfficeAgentPosition).filter(OfficeAgentPosition.owner_key_id==owner.id).all():
        if pos.agent_id in engine.positions:
            pos.x,pos.y=engine.positions[pos.agent_id]
    if outcome["state"]=="arrived":
        move.state="completed"; move.route=[]
    _sync_moves(engine,active,queued)
    db.commit()
    log_event(db,owner.id,"employee.movement.step",agent_id=agent_id,payload={"position":{"x":outcome["to"][0],"y":outcome["to"][1]},"state":outcome["state"]})
    return {"agent_id":agent_id,"position":{"x":outcome["to"][0],"y":outcome["to"][1]},"state":outcome["state"],"route":[{"x":x,"y":y} for x,y in (active[agent_id].route if agent_id in active else [])]}


@router.post("/api/v1/office/agents/{agent_id}/cancel-move")
def cancel_employee_move(agent_id: str, db: Session = Depends(get_db), owner: ApiKey = Depends(require_scope("office:write"))):
    if not db.query(Agent).filter(Agent.id==agent_id,Agent.owner_key_id==owner.id).first():
        raise HTTPException(404,"Employee not found")
    layout=_layout(db,owner)
    try:
        engine,active,queued=_world(db,owner,layout)
        result=engine.cancel(agent_id)
    except ValueError as error:
        raise HTTPException(409,str(error))
    for move in db.query(OfficeMove).filter(OfficeMove.owner_key_id==owner.id,OfficeMove.agent_id==agent_id,OfficeMove.state.in_(["walking","queued"])).all():
        move.state="cancelled"; move.route=[]
    active.pop(agent_id,None)
    _sync_moves(engine,active,[move for move in queued if move.agent_id != agent_id])
    db.commit()
    if result["state"]=="cancelled":
        log_event(db,owner.id,"employee.movement.cancelled",agent_id=agent_id,payload={"position":{"x":result["position"][0],"y":result["position"][1]}})
    return {"agent_id":agent_id,"state":result["state"],"position":{"x":result["position"][0],"y":result["position"][1]}}
