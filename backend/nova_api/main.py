import asyncio
import hashlib
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from uuid import uuid4
from fastapi import Depends, FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from sqlalchemy import or_
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.orm import Session
from .auth import digest, new_secret, require_admin, require_scope
from .config import get_settings
from .db import Base, engine, get_db
from .credentials import decrypt_secret, rotate_secret
from .models import ActivityEvent, Agent, ApiKey, Conversation, ConversationMessage, Meeting, MeetingInvitation, ProviderConnection, ProviderCredential, ResourceSetting, Task, TaskAssignment, UsageRecord
from .providers import list_models
from .schemas import AgentCreate, AgentUpdate, AgentView, ChatRequest, ChatResponse, CheckpointUpdate, ConversationDetail, ConversationView, KeyCreate, KeyView, ProviderConfigurationView, ProviderConnectionTest, ProviderCredentialUpdate, TaskCreate, TaskView
from .routing import invoke_owner
from .workforce import router as workforce_router, set_publisher
from .office import router as office_router
from .compat import router as compatibility_router
from .collaboration import router as collaboration_router
from .event_bus import PostgresEventBus

TASK_STATES = {"RECEIVED", "ANALYZING", "PLANNING", "RECRUITING", "ASSIGNED", "RUNNING", "WAITING_FOR_TOOL", "WAITING_FOR_API", "WAITING_FOR_APPROVAL", "PAUSED", "FAILED", "TESTING", "COMPLETED", "CANCELLED"}
# A socket is scoped to exactly one authenticated owner; never broadcast across tenants.
clients: dict[WebSocket, str] = {}

def initialize():
    Base.metadata.create_all(bind=engine)

event_bus=PostgresEventBus(get_settings().database_url)

@asynccontextmanager
async def lifespan(app: FastAPI):
    initialize()
    event_listener=asyncio.create_task(event_bus.listen(publish)) if event_bus.enabled else None
    try:
        yield
    finally:
        if event_listener:
            event_listener.cancel()
            try: await event_listener
            except asyncio.CancelledError: pass

app = FastAPI(title="NOVA API", version="0.1.0", description="First-party NOVA API", lifespan=lifespan)
# Process-local abuse guard. Production deployments should add a shared gateway limit.
_hits: dict[str, deque] = defaultdict(deque)
@app.middleware("http")
async def request_limit(request, call_next):
    if request.url.path != "/health":
        client_host = request.client.host if request.client else "unknown"
        authorization = request.headers.get("authorization", "")
        # Avoid one client behind a NAT exhausting every other owner's budget. Hash
        # bearer material before using it as an in-memory key; never log/store tokens.
        credential_tag = hashlib.sha256(authorization.encode("utf-8")).hexdigest() if authorization else "anonymous"
        identity = f"{client_host}:{credential_tag}"
        now = time.monotonic(); hits = _hits[identity]
        while hits and now - hits[0] > 60: hits.popleft()
        if len(hits) >= 120:
            return JSONResponse({"detail":"Rate limit exceeded"}, status_code=429, headers={"Retry-After":"60"})
        hits.append(now)
    return await call_next(request)

@app.exception_handler(RequestValidationError)
async def compatibility_validation_error(request: Request, exc: RequestValidationError):
    if request.url.path == "/v1/messages":
        from .compat import anthropic_error
        return anthropic_error(422, "Anthropic Messages request failed schema validation", "req_" + uuid4().hex)
    if request.url.path in {"/v1/chat/completions", "/v1/models"}:
        from .compat import openai_error
        return openai_error(422, "OpenAI-compatible request failed schema validation", "req_" + uuid4().hex)
    if request.url.path.startswith("/api/v1/providers/connections") or request.url.path == "/api/v1/settings/provider":
        return JSONResponse(status_code=422, content={"detail": "Provider credential request failed validation; submitted values are not echoed"})
    return JSONResponse(status_code=422, content={"detail": "Request validation failed"})

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
    allowed = {"assistant:read", "assistant:write", "providers:read", "providers:write", "tasks:read", "tasks:write", "agents:read", "agents:write", "office:read", "office:write", "events:read"}
    if not set(scopes) <= allowed:
        raise HTTPException(422, "One or more API scopes are unsupported")
    if body.expires_at and (body.expires_at.tzinfo is None or body.expires_at.utcoffset() is None):
        raise HTTPException(422, "Expiry must include a timezone")
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
    row.revoked = True
    db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == row.id).delete(synchronize_session=False)
    db.commit(); return {"revoked": True, "id": row.id}

@app.post("/api/v1/admin/credentials/rotate", dependencies=[Depends(require_admin)])
def rotate_stored_credentials(db: Session = Depends(get_db)):
    """Rotate encrypted backend credential ciphertexts to the configured primary key."""
    connections = db.query(ProviderConnection).all()
    legacy = db.query(ProviderCredential).all()
    for row in [*connections, *legacy]:
        row.encrypted_api_key = rotate_secret(row.encrypted_api_key)
    db.commit()
    return {"rotated_provider_connections": len(connections), "rotated_legacy_credentials": len(legacy)}

@app.get("/api/v1/settings/provider", response_model=ProviderConfigurationView)
def get_provider_configuration(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("providers:read"))):
    stored = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == key.id).first()
    if stored:
        return ProviderConfigurationView(provider=stored.provider, model_id=stored.model_id, configured=True, key_source="mobile_encrypted")
    cfg = get_settings()
    if cfg.provider_api_key:
        return ProviderConfigurationView(provider=cfg.provider, model_id=cfg.model, configured=True, key_source="backend_environment")
    return ProviderConfigurationView(provider="arena", model_id=cfg.model, configured=False, key_source="not_configured")

@app.put("/api/v1/settings/provider", response_model=ProviderConfigurationView)
def save_provider_configuration(body: ProviderCredentialUpdate, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("providers:write"))):
    row = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == key.id).first()
    raw = body.api_key.strip() if body.api_key is not None else ""
    if raw:
        raise HTTPException(403, "Provider API keys cannot be submitted by an application client; configure runtime secrets on the backend or use a restricted compatible-gateway token")
    if row:
        row.model_id = body.model_id; row.provider = body.provider
    elif not get_settings().provider_api_key:
        raise HTTPException(422, "No backend runtime provider is configured; do not paste provider keys into the app")
    db.commit()
    if row:
        return ProviderConfigurationView(provider=row.provider, model_id=row.model_id, configured=True, key_source="mobile_encrypted")
    cfg = get_settings()
    return ProviderConfigurationView(provider=cfg.provider, model_id=body.model_id, configured=True, key_source="backend_environment")

@app.post("/api/v1/settings/provider/test", response_model=ProviderConnectionTest)
async def test_provider_configuration(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("providers:write"))):
    row = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == key.id).first()
    if row:
        secret, provider = decrypt_secret(row.encrypted_api_key), row.provider
    else:
        cfg = get_settings(); secret, provider = cfg.provider_api_key, cfg.provider
    if not secret:
        raise HTTPException(422, "Save an AI provider key before testing the connection")
    base_url = get_settings().provider_base_url if provider == "arena" else None
    model_ids = await list_models(secret, base_url=base_url, provider=provider)
    return ProviderConnectionTest(provider=provider, ok=True, models_available=len(model_ids), model_ids=model_ids)

@app.delete("/api/v1/settings/provider")
def clear_provider_configuration(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("providers:write"))):
    row = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == key.id).first()
    if row:
        db.delete(row); db.commit()
    return {"configured":bool(get_settings().provider_api_key), "key_source":"backend_environment" if get_settings().provider_api_key else "not_configured"}

@app.post("/api/v1/agents", response_model=AgentView)
def create_agent(body: AgentCreate, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:write"))):
    if db.query(Agent).filter(Agent.owner_key_id==key.id, Agent.identity==body.identity).first(): raise HTTPException(409,"An agent with this role identity already exists")
    row = Agent(owner_key_id=key.id, **body.model_dump()); db.add(row); db.commit(); db.refresh(row); return row

@app.get("/api/v1/agents", response_model=list[AgentView])
def list_agents(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:read"))):
    return db.query(Agent).filter(Agent.owner_key_id == key.id).order_by(Agent.created_at.asc()).all()

@app.put("/api/v1/agents/{agent_id}", response_model=AgentView)
def update_agent(agent_id: str, body: AgentUpdate, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:write"))):
    row = db.query(Agent).filter(Agent.id == agent_id, Agent.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Agent not found")
    duplicate = db.query(Agent).filter(Agent.owner_key_id==key.id, Agent.identity==body.identity, Agent.id!=row.id).first()
    if duplicate: raise HTTPException(409,"An agent with this role identity already exists")
    for field, value in body.model_dump().items(): setattr(row, field, value)
    db.commit(); db.refresh(row)
    db.add(ActivityEvent(owner_key_id=key.id, agent_id=row.id, event_type="agent.updated", payload={"identity":row.identity})); db.commit()
    return row

@app.post("/api/v1/agents/{agent_id}/pause", response_model=AgentView)
def pause_agent(agent_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:write"))):
    row = db.query(Agent).filter(Agent.id == agent_id, Agent.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Agent not found")
    if row.status == "working": raise HTTPException(409, "A worker in an active provider call cannot be paused safely; request stop or wait for it to finish")
    if row.status != "paused":
        row.status = "paused"; db.commit(); db.refresh(row)
        db.add(ActivityEvent(owner_key_id=key.id, agent_id=row.id, event_type="agent.paused", payload={"identity":row.identity})); db.commit()
    return row

@app.post("/api/v1/agents/{agent_id}/resume", response_model=AgentView)
def resume_agent(agent_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:write"))):
    row = db.query(Agent).filter(Agent.id == agent_id, Agent.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Agent not found")
    if row.status == "paused":
        row.status = "idle"; db.commit(); db.refresh(row)
        db.add(ActivityEvent(owner_key_id=key.id, agent_id=row.id, event_type="agent.resumed", payload={"identity":row.identity})); db.commit()
    return row

@app.post("/api/v1/agents/{agent_id}/stop")
def stop_agent_work(agent_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:write"))):
    row = db.query(Agent).filter(Agent.id == agent_id, Agent.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Agent not found")
    active = (db.query(Task).join(TaskAssignment, TaskAssignment.task_id == Task.id)
              .filter(Task.owner_key_id == key.id, TaskAssignment.agent_id == row.id, TaskAssignment.status == "RUNNING").all())
    task_ids=[]
    for task in active:
        checkpoint=dict(task.checkpoint or {});checkpoint["cancel_requested"]=True;task.checkpoint=checkpoint;task_ids.append(task.id)
    db.commit()
    db.add(ActivityEvent(owner_key_id=key.id, agent_id=row.id, event_type="agent.stop_requested", payload={"task_ids":task_ids,"cooperative":bool(task_ids)}));db.commit()
    return {"agent_id":row.id,"task_ids":task_ids,"cooperative":bool(task_ids),"message":"Running task cancellation is cooperative and checked between provider calls; a stop affects the entire task and its parallel assignments." if task_ids else "No running task assignment was found."}

@app.delete("/api/v1/agents/{agent_id}")
def remove_agent(agent_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("agents:write"))):
    row = db.query(Agent).filter(Agent.id == agent_id, Agent.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Agent not found")
    db.delete(row); db.commit(); return {"deleted": True, "id": agent_id}

@app.post("/api/v1/tasks", response_model=TaskView)
def create_task(body: TaskCreate, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("tasks:write"))):
    if body.dependencies:
        prerequisites = db.query(Task).filter(Task.owner_key_id == key.id, Task.id.in_(body.dependencies)).all()
        if len(prerequisites) != len(body.dependencies):
            raise HTTPException(422, "Every dependency must identify a task owned by this NOVA key")
        if any(task.state in {"FAILED", "CANCELLED"} for task in prerequisites):
            raise HTTPException(409, "A failed or cancelled task cannot be a dependency")
    row = Task(owner_key_id=key.id, **body.model_dump()); db.add(row); db.commit(); db.refresh(row)
    db.add(ActivityEvent(owner_key_id=key.id, task_id=row.id, event_type="task.created", payload={"state":row.state,"priority":row.priority})); db.commit()
    return row

@app.get("/api/v1/tasks", response_model=list[TaskView])
def list_tasks(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("tasks:read"))):
    return db.query(Task).filter(Task.owner_key_id == key.id).order_by(Task.priority.asc(), Task.created_at.desc()).limit(200).all()

@app.get("/api/v1/tasks/{task_id}", response_model=TaskView)
def get_task(task_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("tasks:read"))):
    row = db.query(Task).filter(Task.id == task_id, Task.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Task not found")
    return row

@app.patch("/api/v1/tasks/{task_id}/checkpoint", response_model=TaskView)
async def update_checkpoint(task_id: str, body: CheckpointUpdate, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("tasks:write"))):
    row = db.query(Task).filter(Task.id == task_id, Task.owner_key_id == key.id).first()
    if row is None: raise HTTPException(404, "Task not found")
    row.state = body.state; row.checkpoint = body.checkpoint
    if body.assigned_agent_id is not None:
        agent = db.query(Agent).filter(Agent.id == body.assigned_agent_id, Agent.owner_key_id == key.id).first()
        if agent is None: raise HTTPException(422, "Assigned agent does not exist")
        row.assigned_agent_id = body.assigned_agent_id
    db.commit(); db.refresh(row)
    db.add(ActivityEvent(owner_key_id=key.id, task_id=row.id, agent_id=row.assigned_agent_id, event_type="task.updated", payload={"state":row.state})); db.commit()
    await publish({"owner_key_id":key.id, "type":"task.updated", "task_id":row.id, "state":row.state, "updated_at":row.updated_at.isoformat()})
    return row

@app.post("/api/v1/assistant/chat", response_model=ChatResponse)
async def chat(body: ChatRequest, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("assistant:write"))):
    if body.conversation_id:
        conversation = db.query(Conversation).filter(Conversation.id == body.conversation_id, Conversation.owner_key_id == key.id).first()
        if conversation is None: raise HTTPException(404, "Conversation not found")
    else:
        conversation = Conversation(owner_key_id=key.id, title=body.message[:157] + ("..." if len(body.message) > 157 else ""))
        db.add(conversation); db.commit(); db.refresh(conversation)
    previous = (db.query(ConversationMessage).filter(ConversationMessage.conversation_id == conversation.id, ConversationMessage.status == "complete").order_by(ConversationMessage.created_at.desc()).limit(20).all())
    history = [{"role": item.role, "content": item.content} for item in reversed(previous) if item.role in {"user", "assistant"}]
    db.add(ConversationMessage(conversation_id=conversation.id, role="user", content=body.message, status="complete"))
    conversation.updated_at = datetime.now(timezone.utc)
    db.commit()
    text, provider_name, model = await invoke_owner(db, key, body.message, body.model, history)
    db.add(ConversationMessage(conversation_id=conversation.id, role="assistant", content=text, model=model, status="complete"))
    conversation.updated_at = datetime.now(timezone.utc)
    db.commit()
    return ChatResponse(response=text, provider=provider_name, model=model, conversation_id=conversation.id)

@app.get("/api/v1/assistant/conversations", response_model=list[ConversationView])
def list_conversations(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("assistant:read"))):
    return db.query(Conversation).filter(Conversation.owner_key_id == key.id).order_by(Conversation.updated_at.desc()).limit(100).all()

@app.get("/api/v1/assistant/conversations/search", response_model=list[ConversationView])
def search_conversations(q: str = Query(min_length=1, max_length=160), db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("assistant:read"))):
    matching_messages = db.query(ConversationMessage.conversation_id).filter(ConversationMessage.content.ilike(f"%{q}%"))
    return db.query(Conversation).filter(Conversation.owner_key_id == key.id).filter((Conversation.title.ilike(f"%{q}%")) | Conversation.id.in_(matching_messages)).order_by(Conversation.updated_at.desc()).limit(50).all()

@app.get("/api/v1/assistant/conversations/{conversation_id}", response_model=ConversationDetail)
def get_conversation(conversation_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("assistant:read"))):
    conversation = db.query(Conversation).filter(Conversation.id == conversation_id, Conversation.owner_key_id == key.id).first()
    if conversation is None: raise HTTPException(404, "Conversation not found")
    messages = db.query(ConversationMessage).filter(ConversationMessage.conversation_id == conversation.id).order_by(ConversationMessage.created_at.asc()).all()
    return {"id":conversation.id, "title":conversation.title, "created_at":conversation.created_at, "updated_at":conversation.updated_at, "messages":messages}

@app.delete("/api/v1/assistant/conversations/{conversation_id}")
def delete_conversation(conversation_id: str, db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("assistant:write"))):
    conversation = db.query(Conversation).filter(Conversation.id == conversation_id, Conversation.owner_key_id == key.id).first()
    if conversation is None: raise HTTPException(404, "Conversation not found")
    db.query(ConversationMessage).filter(ConversationMessage.conversation_id == conversation.id).delete(synchronize_session=False)
    db.delete(conversation); db.commit(); return {"deleted":True, "id":conversation_id}

async def publish(event: dict):
    owner_id = event.get("owner_key_id")
    if not owner_id:
        return
    event_id=event.get("event_id")
    if isinstance(event_id,str) and event_bus.was_seen_locally(event_id):
        return
    if isinstance(event_id,str): event_bus.mark_local(event_id)
    public_event = {name: value for name, value in event.items() if name != "owner_key_id"}
    gone = []
    for ws, subscriber_owner in list(clients.items()):
        if subscriber_owner != owner_id:
            continue
        try:
            await ws.send_json(public_event)
        except Exception:
            gone.append(ws)
    for ws in gone:
        clients.pop(ws, None)

@app.websocket("/api/v1/events")
async def events(ws: WebSocket, db: Session = Depends(get_db)):
    token = ws.headers.get("authorization", "")
    if not token.startswith("Bearer "):
        await ws.close(code=4401); return
    key = db.query(ApiKey).filter(ApiKey.key_hash == digest(token[7:]), ApiKey.revoked.is_(False)).first()
    if not key or "events:read" not in key.scopes or (key.expires_at and key.expires_at.replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc)):
        await ws.close(code=4403); return
    await ws.accept()
    clients[ws] = key.id
    try:
        # Send authoritative current state on connect/reconnect, followed by a bounded event tail.
        agents = db.query(Agent).filter(Agent.owner_key_id == key.id).order_by(Agent.created_at.asc()).all()
        tasks = db.query(Task).filter(Task.owner_key_id == key.id).order_by(Task.updated_at.desc()).limit(200).all()
        providers = db.query(ProviderConnection).filter(ProviderConnection.owner_key_id == key.id).all()
        meetings = db.query(Meeting).filter(Meeting.owner_key_id == key.id).order_by(Meeting.created_at.desc()).limit(100).all()
        recent = db.query(ActivityEvent).filter(ActivityEvent.owner_key_id == key.id).order_by(ActivityEvent.created_at.desc()).limit(100).all()
        await ws.send_json({
            "type": "snapshot",
            "agents": [{"id": a.id, "identity": a.identity, "name": a.name, "job_title": a.job_title, "provider": a.provider, "model_id": a.model_id, "status": a.status} for a in agents],
            "tasks": [{"id": t.id, "title": t.title, "state": t.state, "assigned_agent_id": t.assigned_agent_id, "updated_at": t.updated_at.isoformat()} for t in tasks],
            "providers": [{"provider": p.provider, "status": p.status, "enabled": p.enabled, "last_checked_at": p.last_checked_at.isoformat() if p.last_checked_at else None} for p in providers],
            "meetings": [{"id": m.id,"title":m.title,"status":m.status,"scheduled_at":m.scheduled_at.isoformat() if m.scheduled_at else None,"updated_at":m.completed_at.isoformat() if m.completed_at else m.started_at.isoformat() if m.started_at else m.created_at.isoformat()} for m in meetings],
            "events": [{"event_id": e.id, "type": e.event_type, "task_id": e.task_id, "agent_id": e.agent_id, "created_at": e.created_at.isoformat(), "payload": e.payload} for e in reversed(recent)],
        })
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        clients.pop(ws, None)

set_publisher(publish)
app.include_router(workforce_router)
app.include_router(office_router)
app.include_router(compatibility_router)
app.include_router(collaboration_router)
