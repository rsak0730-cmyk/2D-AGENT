-- Persist a user's preferred employee for a queued task. The Head Manager still plans
-- and may delegate helper subtasks; the requested worker owns the first/core assignment.
ALTER TABLE tasks
    ADD COLUMN requested_agent_id VARCHAR(36) NULL
    REFERENCES agents(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_tasks_requested_agent_id
    ON tasks(requested_agent_id);
