from datetime import datetime
from pydantic import BaseModel, ConfigDict, Field, field_validator


class MeetingCreate(BaseModel):
    title: str = Field(min_length=1, max_length=160)
    agenda: str = Field(min_length=1, max_length=6000)
    participant_ids: list[str] = Field(min_length=1, max_length=10)
    scheduled_at: datetime | None = None
    project_context: str = Field(default="", max_length=12000)
    task_id: str | None = None

    @field_validator("participant_ids")
    @classmethod
    def unique_participants(cls, value):
        if len(value) != len(set(value)):
            raise ValueError("participant_ids must be unique")
        return value

    @field_validator("scheduled_at")
    @classmethod
    def timezone_required(cls, value):
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("scheduled_at must include a timezone")
        return value


class MeetingInviteCreate(BaseModel):
    agent_ids: list[str] = Field(min_length=1, max_length=10)

    @field_validator("agent_ids")
    @classmethod
    def unique_agents(cls, value):
        if len(value) != len(set(value)):
            raise ValueError("agent_ids must be unique")
        return value


class MeetingInviteResponse(BaseModel):
    accepted: bool


class MeetingHandoffCreate(BaseModel):
    task_id: str
    agent_id: str
    instructions: str = Field(min_length=1, max_length=6000)


class MeetingMessageView(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: str
    speaker_agent_id: str | None
    role: str
    content: str
    provider: str | None
    model_id: str | None
    created_at: datetime


class MeetingInvitationView(BaseModel):
    id: str
    agent_id: str
    identity: str
    name: str
    status: str
    error_detail: str | None
    response_at: datetime | None


class MeetingView(BaseModel):
    id: str
    task_id: str | None
    title: str
    agenda: str
    project_context: str
    scheduled_at: datetime | None
    status: str
    created_at: datetime
    started_at: datetime | None
    completed_at: datetime | None
    invitations: list[MeetingInvitationView]
    messages: list[MeetingMessageView] = []


class MeetingHandoffView(BaseModel):
    meeting_id: str
    task_id: str
    agent_id: str
    status: str
