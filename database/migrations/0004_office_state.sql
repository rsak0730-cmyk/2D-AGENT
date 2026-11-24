-- Persisted office layouts, agent cell positions, and resumable movement queues.
-- Apply after 0003_workforce.sql for PostgreSQL deployments. SQLite development tables
-- are created by SQLAlchemy create_all for new databases.
CREATE TABLE IF NOT EXISTS office_layouts (
  id varchar(36) PRIMARY KEY,
  owner_key_id varchar(36) NOT NULL UNIQUE REFERENCES api_keys(id) ON DELETE CASCADE,
  width integer NOT NULL DEFAULT 40,
  height integer NOT NULL DEFAULT 24,
  rooms json NOT NULL DEFAULT '[]',
  furniture json NOT NULL DEFAULT '[]',
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_office_layouts_owner_key_id ON office_layouts(owner_key_id);

CREATE TABLE IF NOT EXISTS office_agent_positions (
  id varchar(36) PRIMARY KEY,
  owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  agent_id varchar(36) NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  x integer NOT NULL,
  y integer NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_office_position_owner_agent UNIQUE(owner_key_id, agent_id)
);
CREATE INDEX IF NOT EXISTS ix_office_agent_positions_owner_key_id ON office_agent_positions(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_office_agent_positions_agent_id ON office_agent_positions(agent_id);

CREATE TABLE IF NOT EXISTS office_moves (
  id varchar(36) PRIMARY KEY,
  owner_key_id varchar(36) NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
  agent_id varchar(36) NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
  destination json NOT NULL,
  route json NOT NULL DEFAULT '[]',
  state varchar(24) NOT NULL DEFAULT 'queued',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_office_moves_owner_key_id ON office_moves(owner_key_id);
CREATE INDEX IF NOT EXISTS ix_office_moves_agent_id ON office_moves(agent_id);
CREATE INDEX IF NOT EXISTS ix_office_moves_state ON office_moves(state);
CREATE INDEX IF NOT EXISTS ix_office_moves_created_at ON office_moves(created_at);
