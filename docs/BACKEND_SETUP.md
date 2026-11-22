# Backend setup

Python 3.11+, pinned packages in `backend/requirements.txt`. For development use a virtualenv, copy `.env.example` to `.env`, set `NOVA_ADMIN_TOKEN`, then from `backend/` run `uvicorn nova_api.main:app --reload --host 0.0.0.0 --port 8000`. Local default is SQLite; tables are created at application startup. Swagger/OpenAPI at `/docs`, schema at `/openapi.json`.

For Docker Compose, set `POSTGRES_PASSWORD`, `NOVA_ADMIN_TOKEN`, and optional provider variables in an untracked `.env`; run `docker compose up --build`. Set `NOVA_DATABASE_URL=postgresql+psycopg://...` for PostgreSQL. `database/migrations/0001_initial.sql` is a baseline and has not been wired to a migration runner; in current code SQLAlchemy `create_all` initializes tables. Do not use this setup as production until Alembic/migration automation, TLS, shared rate limiting, backups, deployment secret management and health/observability are added.

Credentials: provider API key and admin token are backend environment variables. NOVA API raw keys are returned once and stored as SHA-256 digests. Client calls require the exact scope: assistant `assistant:write`, task operations `tasks:read`/`tasks:write`, agent operations `agents:read`/`agents:write`, events `events:read`. Rate limit is in-process 120 requests/IP/minute and is not a distributed control.
