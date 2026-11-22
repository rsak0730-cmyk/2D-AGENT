-- NOVA initial PostgreSQL schema, migration 0001. Apply once with psql.
CREATE TABLE api_keys (
  id varchar(36) PRIMARY KEY, name varchar(100) NOT NULL, key_hash varchar(64) NOT NULL UNIQUE,
  scopes json NOT NULL, expires_at timestamptz NULL, revoked boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_api_keys_key_hash ON api_keys(key_hash);
CREATE TABLE agents (
  id varchar(36) PRIMARY KEY, name varchar(100) NOT NULL, job_title varchar(100) NOT NULL DEFAULT 'Assistant',
  provider varchar(80) NOT NULL DEFAULT 'arena', model_id varchar(160) NOT NULL DEFAULT 'coding-router-preview',
  specialties json NOT NULL DEFAULT '[]', instructions text NOT NULL DEFAULT '', status varchar(30) NOT NULL DEFAULT 'idle',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_agents_name ON agents(name);
CREATE TABLE tasks (
  id varchar(36) PRIMARY KEY, title varchar(240) NOT NULL, description text NOT NULL DEFAULT '',
  state varchar(32) NOT NULL DEFAULT 'RECEIVED', checkpoint json NOT NULL DEFAULT '{}',
  assigned_agent_id varchar(36) NULL REFERENCES agents(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_tasks_state ON tasks(state);
