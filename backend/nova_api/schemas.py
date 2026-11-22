from datetime import datetime
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator

class KeyCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    scopes: list[str] = Field(min_length=1)
    expires_at: datetime | None = None

class KeyView(BaseModel):
    id: str
    name: str
    scopes: list[str]
    expires_at: datetime | None
    revoked: bool

class ProviderCredentialUpdate(BaseModel):
    provider: Literal["arena"] = "arena"
    model_id: str = Field(default="coding-router-preview", min_length=1, max_length=160)
    api_key: str | None = None

class ProviderConnectionCreate(BaseModel):
    provider: Literal["arena", "groq", "gemini", "openrouter", "cloudflare", "claude_gateway"]
    api_key: str = Field(min_length=8, max_length=4096)
    model_ids: list[str] = Field(default_factory=list, max_length=20)
    account_id: str | None = Field(default=None, max_length=160)
    endpoint: str | None = Field(default=None, max_length=500)
    priority: int = Field(default=100, ge=1, le=10000)
    daily_request_limit: int | None = Field(default=None, ge=1, le=10000000)
    enabled: bool = True

    @field_validator("model_ids")
    @classmethod
    def validate_model_ids(cls, values):
        if any(not item.strip() or len(item) > 160 for item in values):
            raise ValueError("Model IDs must be non-empty and at most 160 characters")
        return list(dict.fromkeys(item.strip() for item in values))

    @field_validator("account_id")
    @classmethod
    def require_cloudflare_account(cls, value, info):
        if info.data.get("provider") == "cloudflare" and (not value or not value.strip()):
            raise ValueError("Cloudflare account ID is required")
        return value.strip() if value else value

    @field_validator("endpoint")
    @classmethod
    def gateway_requires_endpoint(cls, value, info):
        if info.data.get("provider") == "claude_gateway" and (not value or not value.strip()):
            raise ValueError("Claude Code Gateway base URL is required")
        return value.strip().rstrip("/") if value else value

class ProviderConnectionView(BaseModel):
    id: str
    provider: str
    models: list[str]
    priority: int
    daily_request_limit: int | None
    enabled: bool
    status: str
    last_error: str | None
    last_checked_at: datetime | None
    account_id: str | None

class ProviderConfigurationView(BaseModel):
    provider: str
    model_id: str
    configured: bool
    key_source: Literal["mobile_encrypted", "backend_environment", "not_configured"]

class ProviderConnectionTest(BaseModel):
    provider: str
    ok: bool
    models_available: int
    model_ids: list[str]

class AgentCreate(BaseModel):
    identity: str = Field(default="worker", min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=100)
    job_title: str = Field(default="Assistant", max_length=100)
    provider: Literal["arena", "groq", "gemini", "openrouter", "cloudflare", "claude_gateway"] = "arena"
    model_id: str = Field(default="coding-router-preview", max_length=160)
    specialties: list[str] = Field(default_factory=list, max_length=20)
    instructions: str = Field(default="", max_length=8000)
    temperature: float = Field(default=0.2, ge=0, le=2)
    context_length: int = Field(default=8192, ge=256, le=1000000)
    tool_permissions: list[str] = Field(default_factory=list, max_length=20)
    memory_enabled: bool = True
    priority: int = Field(default=50, ge=1, le=100)
    critical: bool = False
    max_task_duration_seconds: int = Field(default=300, ge=1, le=3600)

class AgentUpdate(BaseModel):
    identity: str = Field(min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=100)
    job_title: str = Field(max_length=100)
    provider: Literal["arena", "groq", "gemini", "openrouter", "cloudflare", "claude_gateway"]
    model_id: str = Field(max_length=160)
    specialties: list[str] = Field(max_length=20)
    instructions: str = Field(max_length=8000)
    temperature: float = Field(ge=0, le=2)
    context_length: int = Field(ge=256, le=1000000)
    tool_permissions: list[str] = Field(max_length=20)
    memory_enabled: bool
    priority: int = Field(ge=1, le=100)
    critical: bool
    max_task_duration_seconds: int = Field(ge=1, le=3600)

class AgentView(AgentCreate):
    model_config = ConfigDict(from_attributes=True)
    id: str
    status: str
    created_at: datetime

class TaskCreate(BaseModel):
    title: str = Field(min_length=1, max_length=240)
    description: str = Field(default="", max_length=20000)
    priority: int = Field(default=50, ge=1, le=100)
    max_attempts: int = Field(default=2, ge=1, le=5)

class TaskView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    title: str
    description: str
    state: str
    priority: int
    checkpoint: dict
    result: str | None
    error_detail: str | None
    attempt_count: int
    max_attempts: int
    assigned_agent_id: str | None
    created_at: datetime
    updated_at: datetime
    started_at: datetime | None
    completed_at: datetime | None

class CheckpointUpdate(BaseModel):
    state: Literal["ANALYZING", "PLANNING", "RECRUITING", "ASSIGNED", "RUNNING", "WAITING_FOR_TOOL", "WAITING_FOR_API", "WAITING_FOR_APPROVAL", "PAUSED", "FAILED", "TESTING", "COMPLETED", "CANCELLED"]
    checkpoint: dict = Field(default_factory=dict)
    assigned_agent_id: str | None = None

class MemoryCreate(BaseModel):
    scope: Literal["agent", "project", "headquarters"] = "agent"
    agent_id: str | None = None
    project_key: str | None = Field(default=None, max_length=120)
    memory_type: str = Field(default="note", max_length=40)
    content: str = Field(min_length=1, max_length=12000)
    keywords: list[str] = Field(default_factory=list, max_length=30)
    importance: float = Field(default=0.5, ge=0, le=1)

class MemoryView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    agent_id: str | None
    scope: str
    project_key: str | None
    memory_type: str
    content: str
    keywords: list[str]
    importance: float
    created_at: datetime
    accessed_at: datetime | None

class AgentMessageCreate(BaseModel):
    task_id: str | None = None
    sender_agent_id: str
    recipient_agent_id: str | None = None
    message_type: str = Field(default="report", max_length=32)
    content: dict

class AgentMessageView(BaseModel):
    id: str
    task_id: str | None
    sender_agent_id: str
    recipient_agent_id: str | None
    message_type: str
    content: dict
    created_at: datetime

class ResourceSettingsUpdate(BaseModel):
    max_concurrency: int = Field(default=2, ge=1, le=16)
    max_active_tasks: int = Field(default=4, ge=1, le=100)
    daily_request_budget: int | None = Field(default=None, ge=1)

class ActivityView(BaseModel):
    id: str
    task_id: str | None
    agent_id: str | None
    event_type: str
    payload: dict
    created_at: datetime

class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=20000)
    model: str | None = Field(default=None, max_length=160)
    conversation_id: str | None = None

class ChatResponse(BaseModel):
    response: str
    provider: str
    model: str
    conversation_id: str

class MessageView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    role: str
    content: str
    model: str | None
    status: str
    created_at: datetime

class ConversationView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    title: str
    created_at: datetime
    updated_at: datetime

class ConversationDetail(ConversationView):
    messages: list[MessageView]
