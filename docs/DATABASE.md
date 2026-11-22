# Database schema lifecycle

New local SQLite databases are initialized from SQLAlchemy metadata at API startup. `create_all` creates missing tables but does not add new columns to existing databases. Back up the old DB before changing schema. There is no automated SQLite migration runner; for a local development DB, exporting needed rows and initializing a fresh DB is currently the manual path. Do not apply PostgreSQL SQL to SQLite.

For PostgreSQL, apply scripts once in numeric order using a migration account:

```sh
psql "$NOVA_DATABASE_URL" -v ON_ERROR_STOP=1 -f database/migrations/0001_initial.sql
psql "$NOVA_DATABASE_URL" -v ON_ERROR_STOP=1 -f database/migrations/0002_provider_credentials.sql
psql "$NOVA_DATABASE_URL" -v ON_ERROR_STOP=1 -f database/migrations/0003_workforce.sql
```

Migration 0003 adds owner linkage/configuration to preexisting agent/task rows and creates provider connections, task assignments, agent messages and memories, usage records, activity events, and per-owner resource settings. Historical agents/tasks retain null `owner_key_id` and are not exposed through owner-scoped list endpoints; an operator must deliberately reassign them after reviewing ownership. The SQL uses `IF NOT EXISTS` for additive rerun tolerance. It does not make schema migrations automatic or replace staging/backup/restore tests.

The ORM's `create_all` path is for new development databases. Production rollout: take and verify an encrypted backup, test migration on a restored staging copy, apply the versioned SQL as a privileged migration role, grant the runtime role only needed data access, and retain rollback/forward-recovery steps. Provider secret ciphertext becomes undecryptable if `NOVA_CREDENTIAL_ENCRYPTION_KEY` is lost; back up that key separately from the DB.
