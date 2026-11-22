import asyncio
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from uuid import uuid4
from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.orm import Session
from .auth import digest, new_secret, require_admin, require_scope
from .config import get_settings
from .db import Base, engine, get_db
from .models import Agent, ApiKey, Task
from .providers import complete
from .schemas import AgentCreate, AgentView, ChatRequest, ChatResponse, CheckpointUpdate, KeyCreate, KeyView, TaskCreate, TaskView

TASK_STATES = {"RECEIVED", "ANALYZING", "PLANNING", "RECRUITING", "ASSIGNED", "RUNNING", "WAITING_FOR_TOOL", "WAITING_FOR_API", "WAITING_FOR_APPROVAL", "PAUSED", "FAILED", "TESTING", "COMPLETED", "CANCELLED"}
clients: set[WebSocket] = set()

def initialize():
    Base.metadata.create_all(bind=engine)

@asynccontextmanager
async def lifespan(app: FastAPI):
    initialize()
    yield

app = FastAPI(title="NOVA API", version="0.1.0", description="First-party NOVA API", lifespan=lifespan)
# Process-local abuse guard. Production deployments should add a shared gateway limit.
_hits: dict[str, deque] = defaultdict(deque)
@app.middleware("http")
async def request_limit(request, call_next):
    if request.url.path != "/health":
        identity = request.client.host if request.client else "unknown"
        now = time.monotonic(); hits = _hits[identity]
        while hits and now - hits[0] > 60: hits.popleft()
        if len(hits) >= 120:
            return JSONResponse({"detail":"Rate limit exceeded"}, status_code=429, headers={"Retry-After":"60"})
        hits.append(now)
    return await call_next(request)

@app.get("/health")
def health(db: Session = Depends(get_db)):
    cfg = get_settings()
    try:
        db.execute(text("SELECT 1"))
    except Exception:
        raise HTTPException(503, "Database health check failed")
    return {"status": "ok", "provider_configured": bool(cfg.provider_api_key), "database": "connected"}

@app.post("/api/v1/admin/api-keys", dependencies=[Depends(require_admin)], response_model=dict)
def create_api_key(body: KeyCreate, db: Session = Depends(get_db)):
    scopes = sorted(set(body.scopes))
    allowed = {"assistant:read", "assistant:write", "tasks:read", "tasks:write", "agents:read", "agents:write", "office:read", "events:read"}
    if not set(scopes) <= allowed:
        raise HTTPException(422, "One or more API scopes are unsupported")
    if body.expires_at and body.expires_at <= datetime.now(timezone.utc):
        raise HTTPException(422, "Expiry must be in the future")
    raw = new_secret(); row = ApiKey(name=body.name, key_hash=digest(raw), scopes=scopes, expires_at=body.expires_at)
    db.add(row); db.commit(); db.refresh(row)
    return {"id": row.id, "name": row.name, "scopes": row.scopes, "expires_at": row.expires_at, "key": raw, "warning": "This key is shown once. Store it securely."}

@app.get("/api/v1/admin/api-keys", dependencies=[Depends(require_admin)], response_model=list[KeyView])
def list_api_keys(db: Session = Depends(get_db)):
    return db.query(ApiKey).order_by(ApiKey.created_at.desc()).all()

@app.delete("/api/v1/admin/api-keys/{key_id}", dependencies=[Depends(require_admin)])
def revoke_api_key(key_id: str, db: Session = Depends(get_db)):
    row = db.get(ApiKey, key_id)
    if row is None: raise HTTPException(404, "API key not found")
    row.revoked = True; db.commit(); return {"revoked": True, "id": row.id}

@app.post("/api/v1/agents", response_model=AgentView)
def create_agent(body: AgentCreate, db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("agents:write"))):
    row = Agent(**body.model_dump()); db.add(row); db.commit(); db.refresh(row); return row

@app.get("/api/v1/agents", response_model=list[AgentView])
def list_agents(db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("agents:read"))):
    return db.query(Agent).order_by(Agent.created_at.desc()).all()

@app.delete("/api/v1/agents/{agent_id}")
def remove_agent(agent_id: str, db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("agents:write"))):
    row = db.get(Agent, agent_id)
    if row is None: raise HTTPException(404, "Agent not found")
    db.delete(row); db.commit(); return {"deleted": True, "id": agent_id}

@app.post("/api/v1/tasks", response_model=TaskView)
def create_task(body: TaskCreate, db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("tasks:write"))):
    row = Task(**body.model_dump()); db.add(row); db.commit(); db.refresh(row); return row

@app.get("/api/v1/tasks", response_model=list[TaskView])
def list_tasks(db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("tasks:read"))):
    return db.query(Task).order_by(Task.created_at.desc()).all()

@app.get("/api/v1/tasks/{task_id}", response_model=TaskView)
def get_task(task_id: str, db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("tasks:read"))):
    row = db.get(Task, task_id)
    if row is None: raise HTTPException(404, "Task not found")
    return row

@app.patch("/api/v1/tasks/{task_id}/checkpoint", response_model=TaskView)
async def update_checkpoint(task_id: str, body: CheckpointUpdate, db: Session = Depends(get_db), _: ApiKey = Depends(require_scope("tasks:write"))):
    row = db.get(Task, task_id)
    if row is None: raise HTTPException(404, "Task not found")
    row.state = body.state; row.checkpoint = body.checkpoint
    if body.assigned_agent_id is not None:
        if db.get(Agent, body.assigned_agent_id) is None: raise HTTPException(422, "Assigned agent does not exist")
        row.assigned_agent_id = body.assigned_agent_id
    db.commit(); db.refresh(row)
    await publish({"type":"task.updated", "task_id":row.id, "state":row.state, "updated_at":row.updated_at.isoformat()})
    return row

@app.post("/api/v1/assistant/chat", response_model=ChatResponse)
async def chat(body: ChatRequest, _: ApiKey = Depends(require_scope("assistant:write"))):
    text, model = await complete(body.message, body.model)
    return ChatResponse(response=text, provider=get_settings().provider, model=model)

async def publish(event: dict):
    gone = []
    for ws in clients:
        try: await ws.send_json(event)
        except Exception: gone.append(ws)
    for ws in gone: clients.discard(ws)

@app.websocket("/api/v1/events")
async def events(ws: WebSocket, db: Session = Depends(get_db)):
    token = ws.headers.get("authorization", "")
    if not token.startswith("Bearer "):
        await ws.close(code=4401); return
    key = db.query(ApiKey).filter(ApiKey.key_hash == digest(token[7:]), ApiKey.revoked.is_(False)).first()
    if not key or "events:read" not in key.scopes or (key.expires_at and key.expires_at.replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc)):
        await ws.close(code=4403); return
    await ws.accept(); clients.add(ws)
    try:
        while True: await ws.receive_text()
    except WebSocketDisconnect:
        clients.discard(ws)
