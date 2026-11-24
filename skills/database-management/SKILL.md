# Database Management Skill

## Purpose
Manage schema evolution and persistence for local and production NOVA deployments.

## Required knowledge
PostgreSQL, SQLAlchemy, transactions, migrations, indexes, backups, retention.

## Supported tools
Versioned SQL migrations, PostgreSQL, SQLAlchemy.

## Development rules
No credential plaintext; least database privileges; test restores; avoid destructive migrations without approval.

## Implementation procedure
Design normalized entities; version migration; ensure rollback or forward recovery; add indexes and retention plan.

## Security requirements
Use TLS for remote DB, parameterized queries, backup encryption, access-separated roles.

## Testing procedure
Apply migrations in disposable DB; validate relationships and persistence across app restart.

## Troubleshooting
Check migration ordering, SQL dialect differences, and pool health before data repair.

## References
https://github.com/postgres/postgres ; https://github.com/pgvector/pgvector
