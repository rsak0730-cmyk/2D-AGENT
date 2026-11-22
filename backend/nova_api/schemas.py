from datetime import datetime
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field

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

class AgentCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    job_title: str = Field(default="Assistant", max_length=100)
    provider: str = Field(default="arena", max_length=80)
    model_id: str = Field(default="coding-router-preview", max_length=160)
    specialties: list[str] = []
    instructions: str = Field(default="", max_length=8000)

class AgentView(AgentCreate):
    model_config = ConfigDict(from_attributes=True)
    id: str
    status: str
    created_at: datetime

class TaskCreate(BaseModel):
    title: str = Field(min_length=1, max_length=240)
    description: str = Field(default="", max_length=20000)

class TaskView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    title: str
    description: str
    state: str
    checkpoint: dict
    assigned_agent_id: str | None
    created_at: datetime
    updated_at: datetime

class CheckpointUpdate(BaseModel):
    state: Literal["ANALYZING", "PLANNING", "RECRUITING", "ASSIGNED", "RUNNING", "WAITING_FOR_TOOL", "WAITING_FOR_API", "WAITING_FOR_APPROVAL", "PAUSED", "FAILED", "TESTING", "COMPLETED", "CANCELLED"]
    checkpoint: dict = Field(default_factory=dict)
    assigned_agent_id: str | None = None

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
