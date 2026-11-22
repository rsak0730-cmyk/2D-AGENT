# NOVA

NOVA is an Android AI assistant backed by a modular API. This repository is an **early, testable foundation**, not the complete workforce vision in the master brief. The current implementation intentionally reports unavailable capabilities rather than returning fake AI answers or showing fake agent activity.

## What works in this checkpoint

- Kotlin/Jetpack Compose Android app shell with Home, Assistant, Headquarters, Agents, Tasks and Settings navigation.
- HTTPS backend URL and scoped API token settings encrypted at rest using Android Keystore; the API token is not committed or embedded in the APK.
- FastAPI REST/OpenAPI server with health check, hashed scoped API keys, key revocation, per-key isolated persistent conversations with history/search/delete, agent identity records, task records and checkpoint updates.
- An actual provider request adapter for Arena's documented OpenAI-compatible Chat Completions API. Without a provider key, chat returns a clear 503; it never fabricates a reply.
- Authenticated WebSocket task update events and a deterministic tested A* pathfinder library.
- PostgreSQL Docker Compose configuration, SQL schema baseline, test suites and GitHub Actions workflows for backend CI and Android debug APK artifact builds.

## Not implemented yet

There is no autonomous task worker, agent execution loop, multi-agent orchestration, vector/long-term memory, MCP connector engine, office renderer/editor, voice flow, reminder scheduler, telephony receptionist, API dashboard, distributed rate limiter, or deployed backend. Conversations are persisted and searchable, but task state/checkpoint endpoints only store records; they do not run jobs. Read [progress](docs/PROGRESS.md) and the [architecture](docs/ARCHITECTURE.md) for exact boundaries.

## Run backend locally

Requires Python 3.11+. From repository root:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
cp .env.example .env
# Edit .env: set a long random NOVA_ADMIN_TOKEN; optionally configure an Arena key.
cd backend
uvicorn nova_api.main:app --reload --host 0.0.0.0 --port 8000
```

The local default uses SQLite and creates the current development tables automatically. Open `http://localhost:8000/docs` for interactive OpenAPI. Create a scoped app key using the admin secret:

```sh
curl -X POST http://localhost:8000/api/v1/admin/api-keys \
  -H "Authorization: Bearer $NOVA_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Android phone","scopes":["assistant:read","assistant:write","tasks:read","tasks:write","agents:read","agents:write","events:read"]}'
```

Save the one-time `key` value securely; the server stores only its SHA-256 digest. For API security, do not expose the development server directly to the public internet; use HTTPS and a deployment proxy.

### PostgreSQL via Compose

Set `POSTGRES_PASSWORD` and `NOVA_ADMIN_TOKEN` in your local, untracked `.env`, then run `docker compose up --build`. The development app's SQLAlchemy `create_all` initializes current tables on startup; `database/migrations/0001_initial.sql` is a baseline for a managed database, not yet an automated migration runner. Compose port 8000 is plain HTTP for local use only.

## Run tests

```sh
python -m pip install -r backend/requirements.txt
python -m pytest -q tests
```

## Android

Open this repository in Android Studio with JDK 17 and Android SDK 35. Run `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. Configure a deployed HTTPS NOVA API URL and a scoped API token in Settings. The starter app requires no AI credential to launch; the backend needs an Arena provider key to produce real cloud AI responses.

The `Android Debug APK` GitHub Actions workflow on `main`, pull requests and manual dispatch builds and uploads `nova-debug-apk`. This checkpoint's workspace did not have a JDK/SDK, so no APK artifact is claimed here.

## Roadmap and security

See [docs/PROGRESS.md](docs/PROGRESS.md), [API setup](docs/API_SETUP_GUIDE.md), [security](docs/SECURITY.md) and [contributing](CONTRIBUTING.md). Provider credentials belong in backend environment/secrets manager only. Do not use this early foundation to host untrusted code or arbitrary tool execution.
