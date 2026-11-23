-- Persistent, owner- and agent-scoped direct employee conversations.
-- Apply after 0004_office_state.sql; SQLite development databases use SQLAlchemy create_all.
CREATE TABLE IF NOT EXISTS agent_chat_sessions (
  id varchar(36) PRIMARY KEY,
  owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  agent_id varchar(36) NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  title varchar(120) NOT NULL DEFAULT 'Agent chat',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_agent_chat_sessions_owner_key_id ON agent_chat_sessions(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_agent_chat_sessions_agent_id ON agent_chat_sessions(agent_id);
CREATE INDEX IF NOT EXISTS ix_agent_chat_sessions_updated_at ON agent_chat_sessions(updated_at);

CREATE TABLE IF NOT EXISTS agent_chat_messages (
  id varchar(36) PRIMARY KEY,
  owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  session_id varchar(36) NOT NULL REFERENCES agent_chat_sessions(id) ON DELETE CASCADE,
  role varchar(16) NOT NULL CHECK (role IN ('user', 'assistant')),
  content text NOT NULL,
  provider varchar(40),
  model_id varchar(160),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_agent_chat_messages_owner_key_id ON agent_chat_messages(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_agent_chat_messages_session_id ON agent_chat_messages(session_id);
