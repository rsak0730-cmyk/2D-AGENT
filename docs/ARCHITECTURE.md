# NOVA architecture — checkpoint 1

```text
Android (Compose; Android Keystore token store)
           HTTPS REST / authenticated WebSocket
                    FastAPI (`backend/nova_api`)
          ┌─────────────┴──────────────────┐
 SQLAlchemy persistence              Provider adapter
 SQLite local / PostgreSQL           Arena OpenAI-compatible
          │                            (server env key)
 API-key records / per-key conversations and messages / agents / tasks / task checkpoints

`office_engine` is presently a pure deterministic A* library; it is not connected to a renderer or agent events.
```

## Current decisions

- Android native UI uses Kotlin, Compose, Material 3, OkHttp and an AES-GCM data key held by Android Keystore. Credentials are encrypted locally, but the UI has not yet implemented token enrollment/rotation beyond pasting an existing scoped key.
- Backend uses FastAPI + SQLAlchemy with SQLite as the default local development DB and PostgreSQL via psycopg for Compose. Current development startup uses `create_all`; the SQL baseline in `database/migrations` is not yet an automated migration framework.
- The API has server-generated scoped bearer keys. Raw keys are returned once, then SHA-256 hashed at rest. Admin bootstrap uses a single `NOVA_ADMIN_TOKEN`, which operators must protect and rotate out of band.
- Arena is the only exercised provider adapter. Its OpenAI-compatible API is addressed by configurable base URL, model ID and bearer token. Model lookup/health checks, usage/cost reports, fallback and other providers are not implemented.
- Agent and task tables are API records. There is no durable executor, worker pool, task planner, model router or restart/resume process yet.
- The office A* module is renderer independent. It is chosen as a small testable initial utility; no commitment to libGDX vs native rendering is made until a renderer phase evaluates device performance and interaction requirements.
- WebSocket event authorization is scoped with `events:read`; current in-process broadcast and rate limiting do not scale horizontally.

## Resource review and compatibility

The supplied GitHub project metadata was inspected for all 25 named open-source projects: default branch, SPDX license (where available), archived status and repository description, as of 2026-11-22. All reported non-archived at that metadata check; no source trees were copied. Choices made: FastAPI (MIT), SQLAlchemy as standard persistence adapter, Compose/Android architecture (samples Apache-2.0), and later evaluation rather than install of LangGraph/Temporal/PydanticAI, E2B, MCP SDKs, LibGDX/Godot, LiteRT, pgvector and Playwright. User-linked repo list omitted Arena docs not on GitHub; current Arena docs were read directly. Full versions/compatibility are pinned in manifests where installed. The many candidate frameworks are intentionally not combined.

## Important boundaries

- Local development Compose publishes HTTP only. Production must terminate TLS, set trusted proxy headers safely, use a secrets manager and shared rate limiter, and use database migrations/backups.
- Provider errors are classified (429, 402, invalid credentials, timeout, generic status); upstream response bodies are not reflected in API errors.
- The Android assistant takes a scoped API token. Do not issue a broad token for a shared or untrusted device.
- Approval workflows, prompt-injection defenses for external tool outputs, connector sandboxing, tenant isolation and audit log are not yet implemented. No production task tools are exposed.
