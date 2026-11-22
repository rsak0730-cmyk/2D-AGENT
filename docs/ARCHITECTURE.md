# NOVA architecture — hybrid workforce phase

```text
Android Compose (Keystore-protected NOVA token; provider keys are transient in UI)
       HTTPS REST + authenticated WebSocket
FastAPI API / owner-scoped router / provider fallback
SQLAlchemy persistence: SQLite development / PostgreSQL Compose
  ├─ distinct Head Manager + 10 worker identities
  ├─ encrypted provider connections, dynamic model IDs, usage and budgets
  ├─ durable tasks, validated delegations, results/checkpoints, messages
  ├─ agent/project/headquarters notes and append-only activity events
  └─ resource and admin infrastructure summaries
Provider adapters: Arena, Groq, Gemini OpenAI-compatible, OpenRouter,
                   Cloudflare Workers AI native REST
```

## What runs today

- Eleven role records can be seeded idempotently per NOVA API key. Each record has its own role identity, model/provider selection, prompt, specialties, temperature, context setting, memory toggle, priority, critical marker, task-duration cap, and explicit tool permission list. Model choices are checked against tested provider model IDs in the mobile workflow; the API can still accept configured exact IDs for integrations.
- Task execution is explicitly API-triggered. The Head Manager asks a configured real model for a JSON delegation plan; the server validates worker identities and persists assignments before worker calls. Workers produce real provider completions, and the manager receives saved reports for a final synthesis. A provider/plan failure leaves a failed task and does not fabricate a result. The execution loop is **serial within a request**, bounded by the task's configured attempt count, provider request limits, and HTTP timeouts.
- There is not a separate durable worker daemon, cross-process lease, restart/resume executor, or safe multi-instance scheduler. The API process can be interrupted while a task is in progress; operators must inspect task/assignment state. Do not scale the executor horizontally yet.
- Agent messages and task lifecycle events persist to SQL and are broadcast to currently connected in-process WebSockets. Event delivery across multiple server instances is not implemented.
- Memory is a small, owner-scoped, text/keyword-filtered note store, not a vector database. When enabled, the worker prompt includes a bounded set of its own and headquarters notes. These are treated as untrusted context, not executable commands. Project-scoped notes can be saved and queried, but project assignment is not yet part of task execution.
- Provider keys for provider connections use the separate Fernet key `NOVA_CREDENTIAL_ENCRYPTION_KEY`; raw credentials are never in Android preferences or model IDs. Connections are per NOVA token and can be disabled/deleted. Models are discovered from the provider's model API; the server does not hardcode non-Arena model IDs.
- Router fallback is finite and priority ordered. It can move to another configured, model-compatible connection for rate limiting, upstream failure, timeout, or rejected credentials. It does not retry 402 billing failures. A per-connection daily request ceiling and owner daily request budget are enforced. Provider token/cost metadata is recorded only if returned; we do not infer prices or token counts.

## Provider base endpoints

- Arena: configured `NOVA_PROVIDER_BASE_URL`, default documented Arena OpenAI-compatible API.
- Groq: `https://api.groq.com/openai/v1`.
- Gemini: `https://generativelanguage.googleapis.com/v1beta/openai`.
- OpenRouter: `https://openrouter.ai/api/v1`.
- Cloudflare Workers AI: `https://api.cloudflare.com/client/v4/accounts/{account_id}`, with native `/ai/run/{model}` and account model discovery requests.

Credentials and model catalogs are provider/account specific; adding a connection requires credentials and successful provider model discovery before its returned models become selectable in mobile UI. Free access is not a global guarantee: consult [provider setup and limits](PROVIDERS.md).

## Security boundaries and known gaps

- Android tokens are protected by Android Keystore; provider secrets are HTTPS-transmitted and Fernet-encrypted server-side. Key encryption rotation/backup remains operator-managed.
- All task, worker, memory, provider, event, and usage lookups are scoped to the authenticated NOVA key. The infrastructure endpoint is protected by `NOVA_ADMIN_TOKEN`.
- Task tools are currently disabled; the worker permissions list is metadata only. No shell, filesystem, browser, MCP, payment, messaging, deletion, or other external action tool is called by this executor. There is no approval UI because no such action is exposed.
- Request rate limiting and WebSocket events are process-local; production needs a shared gateway/limiter and event broker. Do not expose plain HTTP Compose to the public internet.
- PostgreSQL upgrade SQL is `database/migrations/0003_workforce.sql`; development SQLite `create_all` initializes new installs but does not migrate old SQLite files.
- Headquarters is presently a live persisted-event feed and status snapshot. There is no pixel-art renderer or movement animation in this repository yet, so the UI does not suggest simulated work or movement.
