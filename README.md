# NOVA

NOVA is an Android AI workspace with a FastAPI backend. It has an executable, owner-scoped Head Manager + ten-worker system while preserving assistant, conversation history, workforce, and navigation features. The gateway facade supports owner-authenticated text streaming for OpenAI Chat Completions and Anthropic Messages, translating native upstream SSE through configured NOVA providers; ordinary app clients can submit only restricted gateway tokens, not upstream provider keys.

## Current functionality

- Android Compose Home, Assistant, Headquarters, Agents, Tasks, and Settings screens; backend URL/token encryption remains Android Keystore-backed.
- Assistant and conversation history/search/delete using a real provider response; no provider response is fabricated when credentials or service are unavailable.
- Central owner-authenticated gateway facade at `/v1/models`, `/v1/chat/completions`, and `/v1/messages`; custom OpenAI-compatible gateways (including LiteLLM/Bifrost-style endpoints) are configurable without hardcoding the gateway URL and require an exact operator hostname allow-list. Upstream provider keys are not accepted from app clients; configure them in backend runtime secrets or at the external gateway. App-entered gateway access tokens are Fernet-encrypted at rest. Model selection is exact and responses expose fallback/provider headers.
- Per-token seedable workforce of one Head Manager plus ten distinct configured roles. Agents have independent identity, provider/model, prompt, specialties, temperature/context settings, memory toggle, priority/critical markers, duration settings, status, and explicit tool permissions. Workers can optionally call bounded arithmetic, text-count, and own-notes search tools; no shell or arbitrary network tool exists.
- Headquarters renders the owner-scoped persisted office grid, rooms, furniture, and employee positions. Selecting an employee opens saved identity/model/status, room-navigation actions, and a direct chat backed by that employee's configured provider/model. Walking is advanced through persisted, backend-validated A* steps; there is no client-only movement simulation.
- The office editor can change room geometry/entrances and add, move, rotate, or remove furniture. The complete layout is submitted to backend validation and stored only when it passes collision, bounds, entrance, and current-employee-position checks.
- Assistant replies can be read aloud through Android's configured text-to-speech engine. This is device-engine speech (which may rely on the user's engine/network settings), not generated voice audio or a claim that an agent is speaking live.
- Owner-scoped persistent task queue with task prerequisites, validated dependency-DAG worker plans, bounded parallel worker batches, assignment progress checkpoints, lease heartbeats, scheduled meeting dispatch, and task-linked workstation movement. `python -m nova_api.queue_worker` (also enabled as the Compose `worker` service) dispatches eligible work; API-triggered execution remains available. Expired leases are detected, unfinished assignments are failed/reassigned, and matching completed outputs are reused only when key, title, instructions, and prerequisites still match exactly. Failed calls use bounded attempts and exponential retry backoff. Owner cancellation and employee stop are cooperative between provider calls; an active upstream request is not forcibly interrupted.
- Meetings support future scheduling, explicit employee invitations/responses, sequential real provider-backed participation with shared project context, saved transcripts, status/events, and queued-task context handoffs. The polling worker auto-starts due meetings; open meetings can be started/cancelled from the Headquarters UI.
- Per-agent analytics report persisted assignment outcomes and measured durations, provider requests, actual backend status, and upstream-reported tokens. Unmeasured durations display unavailable; no productivity scores or prices are invented. Agent-to-agent messages, bounded notes, request budgets, owner-filtered WebSocket events with reconnect snapshots, and infrastructure summary are also persisted.
- A deterministic office navigation engine provides A* paths, furniture placement checks, collision avoidance, FIFO movement requests, and cancellation. Office layouts, employee tile positions, and move steps are owner-scoped and persisted; Android edits rooms/furniture and animates server-validated movement.
- PostgreSQL schema migrations and mock-provider test coverage; the deterministic A* utility remains independent of model calls.

## Scope and limitations (read before deployment)

This is **not** yet a resilient distributed agent platform or a fully compatible universal gateway. The task queue uses database compare-and-set leases; PostgreSQL capacity checks are serialized per owner with transaction advisory locks. Apply migrations 0008 and 0009 for PostgreSQL event fan-out and preferred task-worker persistence; each API process still keeps its own in-memory socket registry, and multi-replica behavior has not been live/load-tested in this environment. Recovery reuses only exactly matching completed outputs; plan drift triggers fresh work. There is no approval workflow, production deployment, or live real-provider acceptance test. Meeting scheduling, invitations, provider-backed participation, history, shared context, and queued-task handoffs are implemented; signed shared documents remain out of scope. Streaming is text-only and supports OpenAI-compatible and Anthropic Messages upstream SSE; no stream continuation/resume protocol is provided after a broken connection, so retry starts a new generation. See `docs/NOVA_API.md` for exact compatibility limits. Native Gemini generate-content/model discovery and Anthropic Messages support are mock-tested; neither has live provider acceptance here. The current UI's resource/event view is scoped to its NOVA token; the separate administrator infrastructure endpoint requires the backend admin token. Do not host publicly without TLS, a trusted reverse proxy, shared rate limiting, migration/backups, secret rotation, and a multi-replica-safe worker/event-broker design.

Provider free access and limits vary by account, model, and date; see [Provider notes](docs/PROVIDERS.md). Cloudflare documents a free allocation of 10,000 Neurons/day, not a fixed request/token quota. NOVA does not promise unlimited or universal free inference, and it does not infer prices. 402 billing errors are not retried onto another provider.

## Run backend locally

Requires Python 3.11+. From the repository root:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r backend/requirements.txt
cp .env.example .env
# Set unique NOVA_ADMIN_TOKEN; if storing gateway access tokens, also set NOVA_CREDENTIAL_ENCRYPTION_KEY.
python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'
set -a; . ./.env; set +a
cd backend
uvicorn nova_api.main:app --reload --host 0.0.0.0 --port 8000
```

For a compatible external gateway, set backend `NOVA_GATEWAY_ALLOWED_HOSTS` to comma-separated exact hostnames (for example `gateway.example.org`, no wildcard); the app accepts only HTTPS URLs on those hosts with the `/v1` base path. Add the endpoint as `openai_compatible` in Settings. Upstream provider secrets belong in the external gateway or backend runtime secret manager, never in the Android app. NOVA application keys are distinct and scope-limited. The OpenAI/Anthropic-shaped facade streams text over SSE for compatible OpenAI and Anthropic upstreams; stream recovery means retrying a fresh generation, not resuming the interrupted upstream. See [gateway/API limitations and setup](docs/PROVIDERS.md).

Create an app token with only necessary scopes. The gateway facade needs `assistant:read` for `/v1/models` and `assistant:write` for completions. Workforce screens use `providers:read`/`providers:write`, `tasks:read`/`tasks:write`, `agents:read`/`agents:write`, and `events:read` as needed. Create/revoke tokens with backend-only `NOVA_ADMIN_TOKEN` via `/api/v1/admin/api-keys`; the raw token is returned once and only its digest is stored. Never give the admin token to a client.

Configure upstream credentials in backend runtime secrets or in your external gateway. For an external OpenAI-compatible gateway, allow-list the exact host in `NOVA_GATEWAY_ALLOWED_HOSTS`, then use Android Settings → Configure multi-provider router to save its `/v1` base URL and restricted gateway token. Provider testing only checks model discovery. Select exact model IDs for the Head and workers. Create tasks with `/api/v1/tasks` (optional `dependencies` contains same-owner task IDs) and explicitly execute via `/api/v1/tasks/{id}/execute`, or run the separate `nova_api.queue_worker` process for automatic polling/dispatch; Android Tasks shows saved dependency selection, live persisted progress, results, retry controls, and cancellation. Android Agents includes pause/resume and cooperative stop controls. See [protocol examples](docs/NOVA_API.md). Interactive API reference: `/docs`.

SQLite is the local default and `create_all` initializes new development databases. It does **not** migrate an existing SQLite DB. PostgreSQL deployments must apply migrations in order through `database/migrations/0009_requested_task_worker.sql`; see `docs/DATABASE.md`. SQLite/PostgreSQL backups contain personal conversation/task data and must be protected.

### PostgreSQL Compose

Set `POSTGRES_PASSWORD`, `NOVA_ADMIN_TOKEN`, and `NOVA_CREDENTIAL_ENCRYPTION_KEY` in a local untracked `.env`; run `docker compose up --build`. Compose serves plain HTTP for local development only. Use HTTPS before sending keys from an Android device.

## Verification

```sh
python -m pip install -r backend/requirements.txt
python -m pytest -q tests
```

Tests use synthetic keys and HTTP mocks, and never call paid provider APIs. Live provider integration checks are not possible without user-provided provider credentials. The repository includes the official Gradle 8.9 Wrapper; Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17, and Android SDK 35 are configured as a compatible toolchain. Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. This sandbox currently has no Java runtime or Android SDK, so local APK compilation cannot run; `.github/workflows/android-debug.yml` installs the required SDK and performs the build on GitHub Actions.

## Security and operator notes

- Upstream provider keys are backend/external-gateway runtime secrets. The API rejects upstream provider keys submitted by app clients; the app can submit only a restricted compatible-gateway token over HTTPS, encrypted at rest by the backend. `NOVA_ADMIN_TOKEN` is never an app credential.
- Do not add secrets to source, logs, tests, or issue reports. Revoke a provider key at its source when deleting a NOVA connection.
- The executor calls only configured model APIs plus explicitly granted bounded arithmetic, text-count, and owner/agent-scoped memory-search tools. No shell, filesystem, browser, arbitrary network, or external-action tool is available.
- Provider request ceilings and owner daily request budgets are enforced. Token reporting depends on provider response usage metadata; costs are intentionally not guessed.
- Read [architecture](docs/ARCHITECTURE.md), [security](docs/SECURITY.md), [providers](docs/PROVIDERS.md), [progress](docs/PROGRESS.md), and the database upgrade notes.
