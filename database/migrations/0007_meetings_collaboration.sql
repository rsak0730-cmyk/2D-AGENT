-- Owner-scoped meetings, employee invitations, shared context, and durable transcript.
CREATE TABLE IF NOT EXISTS meetings (
    id varchar(36) PRIMARY KEY,
    owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    task_id varchar(36) REFERENCES tasks(id) ON DELETE SET NULL,
    title varchar(160) NOT NULL,
    agenda text NOT NULL,
    project_context text NOT NULL DEFAULT '',
    scheduled_at timestamptz,
    status varchar(32) NOT NULL DEFAULT 'scheduled',
    cancel_requested boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz
);
CREATE INDEX IF NOT EXISTS ix_meetings_owner_key_id ON meetings(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_meetings_task_id ON meetings(task_id);
CREATE INDEX IF NOT EXISTS ix_meetings_scheduled_at ON meetings(scheduled_at);
CREATE INDEX IF NOT EXISTS ix_meetings_owner_status_scheduled ON meetings(owner_key_id,status,scheduled_at);

CREATE TABLE IF NOT EXISTS meeting_invitations (
    id varchar(36) PRIMARY KEY,
    meeting_id varchar(36) NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    agent_id varchar(36) NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    status varchar(24) NOT NULL DEFAULT 'invited',
    error_detail text,
    response_at timestamptz,
    CONSTRAINT uq_meeting_invitation_agent UNIQUE(meeting_id,agent_id)
);
CREATE INDEX IF NOT EXISTS ix_meeting_invitations_meeting_id ON meeting_invitations(meeting_id);
CREATE INDEX IF NOT EXISTS ix_meeting_invitations_agent_id ON meeting_invitations(agent_id);
CREATE INDEX IF NOT EXISTS ix_meeting_invitations_status ON meeting_invitations(status);

CREATE TABLE IF NOT EXISTS meeting_messages (
    id varchar(36) PRIMARY KEY,
    meeting_id varchar(36) NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    speaker_agent_id varchar(36) REFERENCES agents(id) ON DELETE SET NULL,
    role varchar(16) NOT NULL,
    content text NOT NULL,
    provider varchar(80),
    model_id varchar(160),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_meeting_messages_meeting_id ON meeting_messages(meeting_id);
CREATE INDEX IF NOT EXISTS ix_meeting_messages_speaker_agent_id ON meeting_messages(speaker_agent_id);
