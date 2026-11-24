# NOVA architecture — hybrid workforce phase

```text
Android Compose (Keystore-protected NOVA token; upstream keys remain backend-side)
       HTTPS REST + authenticated WebSocket
FastAPI API / owner-scoped router / provider fallback
SQLAlchemy persistence: SQLite development / PostgreSQL Compose
  ├─ distinct Head Manager + 10 worker identities
  ├─ encrypted provider connections, dynamic model IDs, usage and budgets
  ├─ durable tasks, validated delegations, results/checkpoints, messages
  ├─ agent/project/headquarters notes and append-only activity events
  └─ resource and admin infrastructure summaries
Provider adapters: Arena, Groq, OpenRouter, native Gemini generateContent,
                   Cloudflare Workers AI native REST, native Anthropic Messages,
                   allow-listed Claude Code Gateway and compatible gateways
```

## What runs today

- Eleven role records can be seeded idempotently per NOVA API key. Each record has its own role identity, model/provider selection, prompt, specialties, temperature, context setting, memory toggle, priority, critical marker, task-duration cap, and explicit tool permission list. The mobile editor offers models returned by provider discovery as selectable suggestions, while exact model IDs remain editable; assignment is not a preflight inference check, and actual provider calls can still reject a selected model.
- Task execution can be explicitly triggered by API or dispatched by the separate `nova_api.queue_worker` process (Compose service `worker`). Tasks hold same-owner prerequisite IDs; the manager's 1–5 subtask DAG is validated and ready assignments execute in parallel up to configured concurrency. Progress, assignment results, leases and heartbeat state persist in the database. A provider/plan failure remains failed and never fabricates output. Retry attempts are bounded and provider operations time out per agent. Owners can cancel tasks or stop an employee cooperatively between provider calls; in-flight provider HTTP requests are not interrupted.
- Expired task leases are detected on retry/queue dispatch. Recovery preserves completed assignment outputs only when re-planned key, title, instructions, and dependency list match; unfinished work is reassigned. Plan changes may repeat work. Failed tasks use bounded exponential retry delay. PostgreSQL owner-capacity checks are transaction-advisory-locked; the compare-and-set task lease prevents duplicate task claims. A live multi-replica stress test was not available.
- Agent messages, per-agent direct-chat sessions/messages, meeting invitations/transcripts and task lifecycle events persist to SQL. Meetings store schedules, shared project context and invitation states; the worker starts due meetings and participants make sequential real-provider calls. Task handoffs append context to queued tasks and persist inter-agent messages.
- OpenAI-compatible and Anthropic Messages gateway endpoints translate native upstream SSE. A downstream cancellation closes the upstream and is recorded; post-header failures become protocol error events. A client retry starts a new generation, not a resumed stream. Gemini and Cloudflare upstream streaming remain unsupported.
- PostgreSQL migration 0008 adds a transactional activity-event trigger and `LISTEN`/`NOTIFY`; each API instance reads the persisted event and forwards it to its local authenticated WebSockets. Reconnect receives a bounded saved event tail and authoritative snapshot. This shared broker path requires PostgreSQL and migration 0008; local SQLite uses process-local fan-out.
- Memory is a small, owner-scoped, text/keyword-filtered note store, not a vector database. When enabled, the worker prompt includes a bounded set of its own and headquarters notes. These are treated as untrusted context, not executable commands. Project-scoped notes can be saved and queried, but project assignment is not yet part of task execution.
- Restricted gateway tokens for app-created connections use the separate Fernet key `NOVA_CREDENTIAL_ENCRYPTION_KEY`; upstream provider keys are rejected from application-client setup and belong in backend runtime or gateway secret managers. Raw gateway credentials are never returned to Android. Connections are per NOVA token and can be disabled/deleted. Models are discovered from provider APIs, including native Gemini model discovery.
- Router fallback is finite and priority ordered. It can move to another configured, model-compatible connection for rate limiting, upstream failure, timeout, or rejected credentials. It does not retry 402 billing failures. A per-connection daily request ceiling and owner daily request budget are enforced. Provider token/cost metadata is recorded only if returned; we do not infer prices or token counts.

## Provider base endpoints

- Arena: configured `NOVA_PROVIDER_BASE_URL`, default documented Arena OpenAI-compatible API.
- Groq: `https://api.groq.com/openai/v1`.
- Gemini: native `https://generativelanguage.googleapis.com/v1beta` models and `generateContent` endpoints, using the `x-goog-api-key` header.
- Anthropic: native `/v1/models` and `/v1/messages` Messages API.
- OpenRouter: `https://openrouter.ai/api/v1`.
- Cloudflare Workers AI: `https://api.cloudflare.com/client/v4/accounts/{account_id}`, with native `/ai/run/{model}` and account model discovery requests.
- Claude Code Gateway: opt-in OpenAI-compatible `GET /v1/models` and `POST /v1/chat/completions`, fixed `/v1` base path, exact HTTPS host allow-list via `NOVA_GATEWAY_ALLOWED_HOSTS`. It is a self-hosted routing layer and requires separately configured upstream provider credentials; it does not grant free or unlimited inference.

Credentials and model catalogs are provider/account specific; adding a connection requires credentials and successful provider model discovery before its returned models become selectable in mobile UI. Free access is not a global guarantee: consult [provider setup and limits](PROVIDERS.md).

## Security boundaries and known gaps

- Android tokens are protected by Android Keystore; provider secrets are HTTPS-transmitted and Fernet-encrypted server-side. Key encryption rotation/backup remains operator-managed.
- All task, worker, memory, provider, event, and usage lookups are scoped to the authenticated NOVA key. The infrastructure endpoint is protected by `NOVA_ADMIN_TOKEN`.
- A worker may request only deterministic tools explicitly enabled for its identity; the backend enforces the saved allow-list and supports bounded arithmetic, text analysis, and owner/agent-scoped memory search. No shell, arbitrary filesystem/network, MCP, payment, or external-system action is available. Dangerous external actions and approval workflows remain unimplemented.
- Request rate limiting and WebSocket events are process-local; production needs a shared gateway/limiter and event broker. Do not expose plain HTTP Compose to the public internet.
- PostgreSQL upgrade SQL is ordered through `database/migrations/0005_agent_chat.sql`; development SQLite `create_all` initializes new installs but does not migrate old SQLite files.
- Headquarters renders persisted room/furniture layout and agent positions; movement routes are A*-validated and persisted one cell at a time. Android exposes room geometry/entrance and furniture editing. Automatic task/meeting-driven destinations and real furniture interaction remain unimplemented.
