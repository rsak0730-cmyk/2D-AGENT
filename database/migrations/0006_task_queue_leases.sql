-- Durable task dependencies, assignment dependencies, and recoverable worker leases.
-- Apply after 0005_agent_chat.sql. Existing work is retained and defaults to no dependencies.
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS dependencies json NOT NULL DEFAULT '[]';
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS lease_owner varchar(100);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS heartbeat_at timestamptz;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS next_retry_at timestamptz;
CREATE INDEX IF NOT EXISTS ix_tasks_next_retry_at ON tasks(next_retry_at);
CREATE INDEX IF NOT EXISTS ix_tasks_lease_owner ON tasks(lease_owner);
CREATE INDEX IF NOT EXISTS ix_tasks_lease_expires_at ON tasks(lease_expires_at);

ALTER TABLE task_assignments ADD COLUMN IF NOT EXISTS depends_on json NOT NULL DEFAULT '[]';
ALTER TABLE task_assignments ADD COLUMN IF NOT EXISTS lease_owner varchar(100);
ALTER TABLE task_assignments ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;
ALTER TABLE task_assignments ADD COLUMN IF NOT EXISTS heartbeat_at timestamptz;
CREATE INDEX IF NOT EXISTS ix_task_assignments_lease_owner ON task_assignments(lease_owner);
CREATE INDEX IF NOT EXISTS ix_task_assignments_lease_expires_at ON task_assignments(lease_expires_at);
