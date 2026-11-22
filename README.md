# NOVA

NOVA is an Android AI assistant backed by a modular API. This repository is an **early, testable foundation**, not the complete workforce vision in the master brief. The current implementation intentionally reports unavailable capabilities rather than returning fake AI answers or showing fake agent activity.

## What works in this checkpoint

- Kotlin/Jetpack Compose Android app shell with Home, Assistant, Headquarters, Agents, Tasks and Settings navigation.
- HTTPS backend URL and scoped API token settings encrypted at rest using Android Keystore; the API token is not committed or embedded in the APK.
- FastAPI REST/OpenAPI server with health check, hashed scoped API keys, key revocation, per-key isolated persistent conversations with history/search/delete, agent identity records, task records and checkpoint updates.
- An actual provider request adapter for Arena's documented OpenAI-compatible API. Users can paste an Arena key into the mobile AI Provider setup; the app sends it over HTTPS to the backend, which encrypts it at rest per scoped NOVA key. Backend environment configuration remains supported. Without a provider key, chat reports a clear error rather than fabricating a reply.
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
# Edit .env: set NOVA_ADMIN_TOKEN. For mobile provider-key entry also set NOVA_CREDENTIAL_ENCRYPTION_KEY.
set -a
. ./.env
set +a
cd backend
uvicorn nova_api.main:app --reload --host 0.0.0.0 --port 8000
```

The local default uses SQLite and creates the current development tables automatically. Open `http://localhost:8000/docs` for interactive OpenAPI. Create a scoped app key using the admin secret:

```sh
curl -X POST http://localhost:8000/api/v1/admin/api-keys \
  -H "Authorization: Bearer $NOVA_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Android phone","scopes":["assistant:read","assistant:write","providers:read","providers:write","tasks:read","tasks:write","agents:read","agents:write","events:read"]}'
```

Save the one-time `key` value securely; the server stores only its SHA-256 digest. For API security, do not expose the development server directly to the public internet; use HTTPS and a deployment proxy.

### PostgreSQL via Compose

Set `POSTGRES_PASSWORD` and `NOVA_ADMIN_TOKEN` in your local, untracked `.env`; also set `NOVA_CREDENTIAL_ENCRYPTION_KEY` if using mobile provider-key entry. Generate that key with `python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'`. Then run `docker compose up --build`. The development app's SQLAlchemy `create_all` initializes current tables on startup; the SQL files in `database/migrations/` are baselines for managed databases, not yet an automated migration runner. Compose port 8000 is plain HTTP for local use only.

## Run tests

```sh
python -m pip install -r backend/requirements.txt
python -m pytest -q tests
```

## Android

Open this repository in Android Studio with JDK 17 and Android SDK 35. Run `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. Configure a deployed HTTPS NOVA API URL and a scoped API token in Settings. The app requires no AI credential to launch; real responses require an Arena key configured either in backend environment or from the mobile AI Provider form (with `NOVA_CREDENTIAL_ENCRYPTION_KEY` configured server-side).

The `Android Debug APK` GitHub Actions workflow on `main`, pull requests and manual dispatch builds and uploads `nova-debug-apk`. PR #1 produced a successful Android build and a 16,448,412-byte APK artifact on 2026-11-22: [workflow run](https://github.com/rsak0730-cmyk/2D-AGENT/actions/runs/45045951618). The artifact is available for 14 days. This sandbox lacks JDK/SDK, and the APK was not downloaded or installed on a physical device here; compilation and artifact existence passed, device runtime remains unverified.

## Roadmap and security

See [docs/PROGRESS.md](docs/PROGRESS.md), [API setup](docs/API_SETUP_GUIDE.md), [security](docs/SECURITY.md) and [contributing](CONTRIBUTING.md). Provider credentials belong in backend environment/secrets manager or the encrypted per-token backend credential store; never embed them in the APK. Back up `NOVA_CREDENTIAL_ENCRYPTION_KEY` securely because losing it makes stored mobile-entered credentials undecryptable. Do not use this early foundation to host untrusted code or arbitrary tool execution.
