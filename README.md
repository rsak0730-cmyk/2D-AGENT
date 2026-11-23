# NOVA

NOVA is an Android AI workspace with a FastAPI backend. This branch adds an executable, owner-scoped Head Manager + ten-worker system while preserving the assistant, conversation history, provider-key flow, and navigation. Headquarters shows the actual persisted workforce and events; subtle typing animation is limited to agents whose backend status is working.

## Current functionality

- Android Compose Home, Assistant, Headquarters, Agents, Tasks, and Settings screens; backend URL/token encryption remains Android Keystore-backed.
- Assistant and conversation history/search/delete using a real provider response; no provider response is fabricated when credentials or service are unavailable.
- Per-NOVA-token provider registry for Arena, Groq, Gemini, OpenRouter, Cloudflare Workers AI, and an opt-in allow-listed Claude Code Gateway adapter. Provider keys and gateway tokens are Fernet-encrypted on the backend. Exact models are discovered through provider APIs; fallback is finite and records provider/latency/status metadata. Gateway connectivity does not provide upstream credentials or free inference.
- Per-token seedable workforce of one Head Manager plus ten distinct configured roles. Agents have independent identity, provider/model, prompt, specialties, temperature/context settings, memory toggle, priority/critical markers, duration settings, status, and explicit tool permissions. Workers can optionally call bounded arithmetic, text-count, and own-notes search tools; no shell or arbitrary network tool exists.
- Headquarters has a pixel-art 2D office scene driven by the persisted roster and actual agent status, alongside persisted activity events. Characters animate subtle typing only while their saved backend status is `working`; there is no walking or background simulation.
- Assistant replies can be read aloud through Android's configured text-to-speech engine. This is device-engine speech (which may rely on the user's engine/network settings), not generated voice audio or a claim that an agent is speaking live.
- Owner-scoped tasks and a real API-triggered execution path: model-generated manager plans are parsed and validated; worker assignments/reports and manager synthesis persist. Invalid plans and provider failures remain failed; there is no placeholder success. Tasks run serially in the API request, not via a hidden background service. Owners can cancel queued tasks or request cooperative cancellation between provider calls; an active upstream request is not forcibly interrupted.
- Agent-to-agent message records, bounded notes for agent/project/headquarters memory, request-budget controls, usage/activity records, authenticated WebSocket events, and admin infrastructure summary.
- PostgreSQL schema migrations and mock-provider test coverage; the deterministic A* utility remains independent of model calls.

## Scope and limitations (read before deployment)

This is **not** yet a resilient distributed agent platform. There is no independent durable queue worker, cross-process task lease, crash-safe automatic resume, parallel subtask execution, horizontal-safe websocket/event broker, vector memory, MCP/tool runtime, pixel-art renderer, approval workflow for tools, browser/shell/filesystem access, production deployment, or live real-provider acceptance test. The current UI's resource/event view is scoped to its NOVA token; the separate administrator infrastructure endpoint requires the backend admin token. Do not host publicly without TLS, a trusted reverse proxy, shared rate limiting, migration/backups, secret rotation, and a worker lease design.

Provider free access and limits vary by account, model, and date; see [Provider notes](docs/PROVIDERS.md). Cloudflare documents a free allocation of 10,000 Neurons/day, not a fixed request/token quota. NOVA does not promise unlimited or universal free inference, and it does not infer prices. 402 billing errors are not retried onto another provider.

## Run backend locally

Requires Python 3.11+. From the repository root:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
cp .env.example .env
# Set unique NOVA_ADMIN_TOKEN and (for stored provider keys) NOVA_CREDENTIAL_ENCRYPTION_KEY.
python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'
set -a; . ./.env; set +a
cd backend
uvicorn nova_api.main:app --reload --host 0.0.0.0 --port 8000
```

For the optional Claude Code Gateway adapter, set backend `NOVA_GATEWAY_ALLOWED_HOSTS` to comma-separated exact hostnames (for example `gateway.example.org`); the app then accepts only `https://gateway.example.org/v1`. Never add a broad or untrusted host. Review the self-hosted gateway's own deployment/security guidance and configure an upstream provider.

Create an app token with only necessary scopes. For all current screens and features, the common scope set is `assistant:read`, `assistant:write`, `providers:read`, `providers:write`, `tasks:read`, `tasks:write`, `agents:read`, `agents:write`, and `events:read`. Use narrower scopes if a device does not need every feature. Create tokens with the `NOVA_ADMIN_TOKEN` endpoint `/api/v1/admin/api-keys`; the raw token is returned once and only its digest is stored.

To set up the roster: `POST /api/v1/agents/seed`. Add a provider connection using `POST /api/v1/providers/connections`, then test it with `POST /api/v1/providers/connections/{id}/test`; select one of the exact returned model IDs per agent. Create a task using `POST /api/v1/tasks`, then explicitly execute using `POST /api/v1/tasks/{id}/execute`. The mobile Agents/Tasks screens provide the same operations. Interactive API reference: `/docs`.

SQLite is the local default and `create_all` initializes new development databases. It does **not** migrate an existing SQLite DB. PostgreSQL deployments must apply migrations in order, including `database/migrations/0003_workforce.sql`; see `docs/DATABASE.md`. SQLite/PostgreSQL backups contain personal conversation/task data and must be protected.

### PostgreSQL Compose

Set `POSTGRES_PASSWORD`, `NOVA_ADMIN_TOKEN`, and `NOVA_CREDENTIAL_ENCRYPTION_KEY` in a local untracked `.env`; run `docker compose up --build`. Compose serves plain HTTP for local development only. Use HTTPS before sending keys from an Android device.

## Verification

```sh
python -m pip install -r backend/requirements.txt
python -m pytest -q tests
```

Tests use synthetic keys and HTTP mocks, and never call paid provider APIs. Live provider integration checks are not possible without user-provided provider credentials. This sandbox has no local Gradle/JDK/Android SDK; Android compilation must be verified by GitHub Actions. Android Studio users can run `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` with JDK 17/SDK 35.

## Security and operator notes

- Provider keys stay backend-side, encrypted with a stable, separately backed-up `NOVA_CREDENTIAL_ENCRYPTION_KEY`. The mobile provider form transmits them over the configured HTTPS backend; they are not stored in Android preferences/APK.
- Do not add secrets to source, logs, tests, or issue reports. Revoke a provider key at its source when deleting a NOVA connection.
- The executor calls only configured model APIs. Agent tool permissions are explicit metadata, but no tool runtime exists and no arbitrary host command can be run.
- Provider request ceilings and owner daily request budgets are enforced. Token reporting depends on provider response usage metadata; costs are intentionally not guessed.
- Read [architecture](docs/ARCHITECTURE.md), [security](docs/SECURITY.md), [providers](docs/PROVIDERS.md), [progress](docs/PROGRESS.md), and the database upgrade notes.
