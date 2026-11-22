import asyncio
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from uuid import uuid4
from fastapi import Depends, FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.orm import Session
from .auth import digest, new_secret, require_admin, require_scope
from .config import get_settings
from .db import Base, engine, get_db
from .credentials import decrypt_secret, encrypt_secret
from .models import Agent, ApiKey, Conversation, ConversationMessage, ProviderCredential, Task
from .providers import complete, list_models
from .schemas import AgentCreate, AgentView, ChatRequest, ChatResponse, CheckpointUpdate, ConversationDetail, ConversationView, KeyCreate, KeyView, ProviderConfigurationView, ProviderConnectionTest, ProviderCredentialUpdate, TaskCreate, TaskView

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
    allowed = {"assistant:read", "assistant:write", "providers:read", "providers:write", "tasks:read", "tasks:write", "agents:read", "agents:write", "office:read", "events:read"}
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
    if body.api_key is not None and (len(raw) < 8 or len(raw) > 4096):
        raise HTTPException(422, "Provider key length is invalid")
    if raw:
        encrypted = encrypt_secret(raw)
        if row is None:
            row = ProviderCredential(owner_key_id=key.id, provider=body.provider, model_id=body.model_id, encrypted_api_key=encrypted)
            db.add(row)
        else:
            row.provider = body.provider; row.model_id = body.model_id; row.encrypted_api_key = encrypted
    elif row:
        row.model_id = body.model_id; row.provider = body.provider
    elif not get_settings().provider_api_key:
        raise HTTPException(422, "Enter a provider API key before saving this configuration")
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
    model_ids = await list_models(secret)
    return ProviderConnectionTest(provider=provider, ok=True, models_available=len(model_ids), model_ids=model_ids)

@app.delete("/api/v1/settings/provider")
def clear_provider_configuration(db: Session = Depends(get_db), key: ApiKey = Depends(require_scope("providers:write"))):
    row = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == key.id).first()
    if row:
        db.delete(row); db.commit()
    return {"configured":bool(get_settings().provider_api_key), "key_source":"backend_environment" if get_settings().provider_api_key else "not_configured"}

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
    stored_provider = db.query(ProviderCredential).filter(ProviderCredential.owner_key_id == key.id).first()
    if stored_provider:
        provider_key = decrypt_secret(stored_provider.encrypted_api_key)
        provider_name, selected_model = stored_provider.provider, body.model or stored_provider.model_id
    else:
        provider_key = get_settings().provider_api_key or None
        provider_name, selected_model = get_settings().provider, body.model or get_settings().model
    text, model = await complete(body.message, selected_model, history, api_key=provider_key)
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
