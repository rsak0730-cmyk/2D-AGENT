# Backend Development Skill

## Purpose
Develop NOVA HTTP and WebSocket services with FastAPI and persistent storage.

## Required knowledge
Python 3.11, FastAPI, Pydantic, SQLAlchemy, async I/O, PostgreSQL.

## Supported tools
pytest, FastAPI TestClient, HTTPX, SQL migration scripts.

## Development rules
Validate every boundary; fail closed; redact secrets; do not execute arbitrary host shell input.

## Implementation procedure
Define schemas and scopes; implement persistence and errors; document OpenAPI; add migration and isolated tests before integration.

## Security requirements
Use scoped bearer keys, HTTPS in deployment, parameterized ORM, provider output validation, bounded retries.

## Testing procedure
Install pinned requirements and run pytest; test missing credentials and invalid scopes.

## Troubleshooting
Follow stack traces to root cause; check DB URL, migrations, and async/sync boundaries.

## References
https://github.com/fastapi/fastapi ; https://github.com/postgres/postgres
