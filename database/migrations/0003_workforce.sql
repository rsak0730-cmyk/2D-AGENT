-- NOVA workforce, provider registry, durable task/delegation records, memory and events.
-- Apply after 0001 and 0002 for an existing PostgreSQL deployment. New development databases
-- are initialized by SQLAlchemy create_all; this SQL is not run automatically.
ALTER TABLE agents ADD COLUMN IF NOT EXISTS owner_key_id varchar(36) NULL REFERENCES api_keys(id) ON DELETE CASCADE;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS identity varchar(80) NOT NULL DEFAULT 'worker';
ALTER TABLE agents ADD COLUMN IF NOT EXISTS temperature double precision NOT NULL DEFAULT 0.2;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS context_length integer NOT NULL DEFAULT 8192;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS tool_permissions json NOT NULL DEFAULT '[]';
ALTER TABLE agents ADD COLUMN IF NOT EXISTS memory_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS priority integer NOT NULL DEFAULT 50;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS critical boolean NOT NULL DEFAULT false;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS max_task_duration_seconds integer NOT NULL DEFAULT 300;
ALTER TABLE agents ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_owner_name ON agents(owner_key_id, name);
CREATE INDEX IF NOT EXISTS ix_agents_owner_key_id ON agents(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_agents_status ON agents(status);

ALTER TABLE tasks ADD COLUMN IF NOT EXISTS owner_key_id varchar(36) NULL REFERENCES api_keys(id) ON DELETE CASCADE;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS priority integer NOT NULL DEFAULT 50;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS result text NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS error_detail text NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS max_attempts integer NOT NULL DEFAULT 2;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS started_at timestamptz NULL;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS completed_at timestamptz NULL;
CREATE INDEX IF NOT EXISTS ix_tasks_owner_key_id ON tasks(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_tasks_owner_state_priority ON tasks(owner_key_id, state, priority);

CREATE TABLE IF NOT EXISTS provider_connections (
  id varchar(36) PRIMARY KEY, owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  provider varchar(40) NOT NULL, encrypted_api_key text NOT NULL, account_id varchar(160) NULL,
  endpoint varchar(500) NOT NULL DEFAULT '', auth_method varchar(40) NOT NULL DEFAULT 'bearer',
  models json NOT NULL DEFAULT '[]', priority integer NOT NULL DEFAULT 100,
  daily_request_limit integer NULL, enabled boolean NOT NULL DEFAULT true,
  status varchar(32) NOT NULL DEFAULT 'not_tested', last_error text NULL,
  last_checked_at timestamptz NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_provider_connection_owner_provider UNIQUE(owner_key_id, provider)
);
CREATE INDEX IF NOT EXISTS ix_provider_connections_owner_key_id ON provider_connections(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_provider_connections_provider ON provider_connections(provider);

CREATE TABLE IF NOT EXISTS task_assignments (
  id varchar(36) PRIMARY KEY, task_id varchar(36) NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  agent_id varchar(36) NOT NULL REFERENCES agents(id) ON DELETE CASCADE, subtask_key varchar(60) NOT NULL,
  title varchar(240) NOT NULL, instructions text NOT NULL, status varchar(32) NOT NULL DEFAULT 'QUEUED',
  result text NULL, error_detail text NULL, attempts integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(), started_at timestamptz NULL, completed_at timestamptz NULL
);
CREATE INDEX IF NOT EXISTS ix_task_assignments_task_id ON task_assignments(task_id);
CREATE INDEX IF NOT EXISTS ix_task_assignments_agent_id ON task_assignments(agent_id);
CREATE INDEX IF NOT EXISTS ix_task_assignments_status ON task_assignments(status);

CREATE TABLE IF NOT EXISTS agent_messages (
  id varchar(36) PRIMARY KEY, owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  task_id varchar(36) NULL REFERENCES tasks(id) ON DELETE CASCADE,
  sender_agent_id varchar(36) NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  recipient_agent_id varchar(36) NULL REFERENCES agents(id) ON DELETE CASCADE,
  message_type varchar(32) NOT NULL DEFAULT 'report', content json NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_agent_messages_owner_key_id ON agent_messages(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_agent_messages_task_id ON agent_messages(task_id);
CREATE INDEX IF NOT EXISTS ix_agent_messages_sender_agent_id ON agent_messages(sender_agent_id);
CREATE INDEX IF NOT EXISTS ix_agent_messages_recipient_agent_id ON agent_messages(recipient_agent_id);

CREATE TABLE IF NOT EXISTS agent_memories (
  id varchar(36) PRIMARY KEY, owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  agent_id varchar(36) NULL REFERENCES agents(id) ON DELETE CASCADE, scope varchar(24) NOT NULL DEFAULT 'agent',
  project_key varchar(120) NULL, memory_type varchar(40) NOT NULL DEFAULT 'note', content text NOT NULL,
  keywords json NOT NULL DEFAULT '[]', importance double precision NOT NULL DEFAULT 0.5,
  created_at timestamptz NOT NULL DEFAULT now(), accessed_at timestamptz NULL
);
CREATE INDEX IF NOT EXISTS ix_agent_memories_owner_key_id ON agent_memories(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_agent_memories_agent_id ON agent_memories(agent_id);
CREATE INDEX IF NOT EXISTS ix_agent_memories_scope ON agent_memories(scope);
CREATE INDEX IF NOT EXISTS ix_agent_memories_project_key ON agent_memories(project_key);

CREATE TABLE IF NOT EXISTS usage_records (
  id varchar(36) PRIMARY KEY, owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  agent_id varchar(36) NULL REFERENCES agents(id) ON DELETE SET NULL, task_id varchar(36) NULL REFERENCES tasks(id) ON DELETE SET NULL,
  provider varchar(40) NOT NULL, model_id varchar(160) NOT NULL, input_tokens integer NOT NULL DEFAULT 0,
  output_tokens integer NOT NULL DEFAULT 0, status varchar(24) NOT NULL DEFAULT 'success', latency_ms integer NOT NULL DEFAULT 0,
  fallback_used boolean NOT NULL DEFAULT false, error_kind varchar(40) NULL, cost_usd double precision NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_usage_records_owner_key_id ON usage_records(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_usage_records_agent_id ON usage_records(agent_id);
CREATE INDEX IF NOT EXISTS ix_usage_records_task_id ON usage_records(task_id);
CREATE INDEX IF NOT EXISTS ix_usage_records_created_at ON usage_records(created_at);

CREATE TABLE IF NOT EXISTS activity_events (
  id varchar(36) PRIMARY KEY, owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  task_id varchar(36) NULL REFERENCES tasks(id) ON DELETE CASCADE,
  agent_id varchar(36) NULL REFERENCES agents(id) ON DELETE SET NULL,
  event_type varchar(60) NOT NULL, payload json NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_activity_events_owner_key_id ON activity_events(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_activity_events_task_id ON activity_events(task_id);
CREATE INDEX IF NOT EXISTS ix_activity_events_agent_id ON activity_events(agent_id);
CREATE INDEX IF NOT EXISTS ix_activity_events_event_type ON activity_events(event_type);
CREATE INDEX IF NOT EXISTS ix_activity_events_created_at ON activity_events(created_at);

CREATE TABLE IF NOT EXISTS resource_settings (
  id varchar(36) PRIMARY KEY, owner_key_id varchar(36) NOT NULL UNIQUE REFERENCES api_keys(id) ON DELETE CASCADE,
  max_concurrency integer NOT NULL DEFAULT 2, max_active_tasks integer NOT NULL DEFAULT 4,
  daily_request_budget integer NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_resource_settings_owner_key_id ON resource_settings(owner_key_id);
