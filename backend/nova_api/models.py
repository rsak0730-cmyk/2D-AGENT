from datetime import datetime, timezone
from uuid import uuid4
from sqlalchemy import Boolean, DateTime, Float, ForeignKey, Index, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from .db import Base

def now(): return datetime.now(timezone.utc)

class ApiKey(Base):
    __tablename__ = "api_keys"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    name: Mapped[str] = mapped_column(String(100))
    key_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    scopes: Mapped[list] = mapped_column(JSON, default=list)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)

class Conversation(Base):
    __tablename__ = "conversations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(160))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, onupdate=now, index=True)

class ConversationMessage(Base):
    __tablename__ = "conversation_messages"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversations.id", ondelete="CASCADE"), index=True)
    role: Mapped[str] = mapped_column(String(16))
    content: Mapped[str] = mapped_column(Text)
    model: Mapped[str | None] = mapped_column(String(160), nullable=True)
    status: Mapped[str] = mapped_column(String(24), default="complete")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)

class ProviderCredential(Base):
    """Legacy Arena mobile-key override; retained for compatibility with existing installs."""
    __tablename__ = "provider_credentials"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), unique=True, index=True)
    provider: Mapped[str] = mapped_column(String(40), default="arena")
    model_id: Mapped[str] = mapped_column(String(160), default="coding-router-preview")
    encrypted_api_key: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, onupdate=now)

class ProviderConnection(Base):
    __tablename__ = "provider_connections"
    __table_args__ = (UniqueConstraint("owner_key_id", "provider", name="uq_provider_connection_owner_provider"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), index=True)
    provider: Mapped[str] = mapped_column(String(40), index=True)
    encrypted_api_key: Mapped[str] = mapped_column(Text)
    account_id: Mapped[str | None] = mapped_column(String(160), nullable=True)
    endpoint: Mapped[str] = mapped_column(String(500))
    auth_method: Mapped[str] = mapped_column(String(40), default="bearer")
    models: Mapped[list] = mapped_column(JSON, default=list)
    priority: Mapped[int] = mapped_column(Integer, default=100)
    daily_request_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    status: Mapped[str] = mapped_column(String(32), default="not_tested")
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    last_checked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, onupdate=now)

class Agent(Base):
    __tablename__ = "agents"
    __table_args__ = (UniqueConstraint("owner_key_id", "name", name="uq_agent_owner_name"), UniqueConstraint("owner_key_id", "identity", name="uq_agent_owner_identity"))
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str | None] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), nullable=True, index=True)
    identity: Mapped[str] = mapped_column(String(80), default="worker")
    name: Mapped[str] = mapped_column(String(100), index=True)
    job_title: Mapped[str] = mapped_column(String(100), default="Assistant")
    provider: Mapped[str] = mapped_column(String(80), default="arena")
    model_id: Mapped[str] = mapped_column(String(160), default="coding-router-preview")
    specialties: Mapped[list] = mapped_column(JSON, default=list)
    instructions: Mapped[str] = mapped_column(Text, default="")
    temperature: Mapped[float] = mapped_column(Float, default=0.2)
    context_length: Mapped[int] = mapped_column(Integer, default=8192)
    tool_permissions: Mapped[list] = mapped_column(JSON, default=list)
    memory_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    priority: Mapped[int] = mapped_column(Integer, default=50)
    critical: Mapped[bool] = mapped_column(Boolean, default=False)
    max_task_duration_seconds: Mapped[int] = mapped_column(Integer, default=300)
    status: Mapped[str] = mapped_column(String(30), default="idle", index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, onupdate=now)

class Task(Base):
    __tablename__ = "tasks"
    __table_args__ = (Index("ix_tasks_owner_state_priority", "owner_key_id", "state", "priority"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str | None] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), nullable=True, index=True)
    title: Mapped[str] = mapped_column(String(240))
    description: Mapped[str] = mapped_column(Text, default="")
    state: Mapped[str] = mapped_column(String(32), default="RECEIVED", index=True)
    priority: Mapped[int] = mapped_column(Integer, default=50)
    checkpoint: Mapped[dict] = mapped_column(JSON, default=dict)
    result: Mapped[str | None] = mapped_column(Text, nullable=True)
    error_detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    attempt_count: Mapped[int] = mapped_column(Integer, default=0)
    max_attempts: Mapped[int] = mapped_column(Integer, default=2)
    assigned_agent_id: Mapped[str | None] = mapped_column(ForeignKey("agents.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, onupdate=now)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

class TaskAssignment(Base):
    __tablename__ = "task_assignments"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    task_id: Mapped[str] = mapped_column(ForeignKey("tasks.id", ondelete="CASCADE"), index=True)
    agent_id: Mapped[str] = mapped_column(ForeignKey("agents.id", ondelete="CASCADE"), index=True)
    subtask_key: Mapped[str] = mapped_column(String(60))
    title: Mapped[str] = mapped_column(String(240))
    instructions: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(32), default="QUEUED", index=True)
    result: Mapped[str | None] = mapped_column(Text, nullable=True)
    error_detail: Mapped[str | None] = mapped_column(Text, nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

class AgentMessage(Base):
    __tablename__ = "agent_messages"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), index=True)
    task_id: Mapped[str | None] = mapped_column(ForeignKey("tasks.id", ondelete="CASCADE"), nullable=True, index=True)
    sender_agent_id: Mapped[str] = mapped_column(ForeignKey("agents.id", ondelete="CASCADE"), index=True)
    recipient_agent_id: Mapped[str | None] = mapped_column(ForeignKey("agents.id", ondelete="CASCADE"), nullable=True, index=True)
    message_type: Mapped[str] = mapped_column(String(32), default="report")
    content: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)

class AgentMemory(Base):
    __tablename__ = "agent_memories"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), index=True)
    agent_id: Mapped[str | None] = mapped_column(ForeignKey("agents.id", ondelete="CASCADE"), nullable=True, index=True)
    scope: Mapped[str] = mapped_column(String(24), default="agent", index=True)
    project_key: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    memory_type: Mapped[str] = mapped_column(String(40), default="note")
    content: Mapped[str] = mapped_column(Text)
    keywords: Mapped[list] = mapped_column(JSON, default=list)
    importance: Mapped[float] = mapped_column(Float, default=0.5)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now)
    accessed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

class UsageRecord(Base):
    __tablename__ = "usage_records"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), index=True)
    agent_id: Mapped[str | None] = mapped_column(ForeignKey("agents.id", ondelete="SET NULL"), nullable=True, index=True)
    task_id: Mapped[str | None] = mapped_column(ForeignKey("tasks.id", ondelete="SET NULL"), nullable=True, index=True)
    provider: Mapped[str] = mapped_column(String(40))
    model_id: Mapped[str] = mapped_column(String(160))
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    status: Mapped[str] = mapped_column(String(24), default="success")
    latency_ms: Mapped[int] = mapped_column(Integer, default=0)
    fallback_used: Mapped[bool] = mapped_column(Boolean, default=False)
    error_kind: Mapped[str | None] = mapped_column(String(40), nullable=True)
    cost_usd: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, index=True)

class ActivityEvent(Base):
    __tablename__ = "activity_events"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), index=True)
    task_id: Mapped[str | None] = mapped_column(ForeignKey("tasks.id", ondelete="CASCADE"), nullable=True, index=True)
    agent_id: Mapped[str | None] = mapped_column(ForeignKey("agents.id", ondelete="SET NULL"), nullable=True, index=True)
    event_type: Mapped[str] = mapped_column(String(60), index=True)
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, index=True)

class ResourceSetting(Base):
    __tablename__ = "resource_settings"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    owner_key_id: Mapped[str] = mapped_column(ForeignKey("api_keys.id", ondelete="CASCADE"), unique=True, index=True)
    max_concurrency: Mapped[int] = mapped_column(Integer, default=2)
    max_active_tasks: Mapped[int] = mapped_column(Integer, default=4)
    daily_request_budget: Mapped[int | None] = mapped_column(Integer, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now, onupdate=now)
