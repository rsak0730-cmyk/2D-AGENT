# NOVA API and protocol gateway

FastAPI serves OpenAPI at `/openapi.json` and Swagger at `/docs`. NOVA's first-party endpoints are under `/api/v1`; assistant, provider-connection, workforce, task, memory, event, office, persistent employee-chat, meetings/collaboration, and administration operations remain available with their documented scopes.

## Application authentication

A NOVA application key is separate from `NOVA_ADMIN_TOKEN` and upstream provider keys. Create it via `/api/v1/admin/api-keys` using the backend-only admin secret; the raw `nova_...` key is returned once, stored as a SHA-256 digest, and can be revoked. Admins can rotate encrypted gateway-credential ciphertext with `POST /api/v1/admin/credentials/rotate`; configure the new primary `NOVA_CREDENTIAL_ENCRYPTION_KEY` and old key in `NOVA_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS` first, then remove the previous key only after verifying the rotated row counts. Give client keys only needed scopes. The compatibility endpoints require `assistant:write` for completions and `assistant:read`, `assistant:write`, or `providers:read` for model listing. Revoked/expired keys stop working. Never put the admin token in Android, Claude Desktop, browser storage, a build, or a public repository.

## OpenAI-shaped endpoints

- `GET /v1/models` — owner-visible model catalog from enabled connections and configured backend runtime model.
- `POST /v1/chat/completions` — text-only user/assistant/system messages, model selection, temperature, and `max_tokens` capped by backend `NOVA_MAX_COMPLETION_TOKENS`. With `stream: true`, NOVA opens a native OpenAI-compatible upstream stream, translates text/finish/usage events into OpenAI SSE chunks, and closes the upstream if the downstream disconnects.

Example (replace the host and obtain an application key through the secure admin process; this is not a real key):

```sh
curl https://nova.example.org/v1/chat/completions \
  -H "Authorization: Bearer $NOVA_APP_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"your-discovered-model","messages":[{"role":"system","content":"Be concise."},{"role":"user","content":"Hello"}]}'
```

Responses include provider/model usage metadata when returned by upstream, `x-request-id`, `x-nova-provider`, and `x-nova-fallback`. The actual served model is in the response; fallback is never silent. Fallback candidates must support the exact requested model. Unknown provider token usage is currently represented as zero with an explanatory header, not estimated.

## Anthropic Messages-shaped endpoint

- `POST /v1/messages` — accepts `x-api-key` with a scoped NOVA application key (or `Authorization: Bearer`), `anthropic-version`, model, system text, user/assistant text history, `max_tokens`, and temperature.
- Produces an Anthropic-shaped `message` response with `content`, stop reason mapped from upstream finish reason, usage, `request-id`, provider, and fallback headers. With `stream: true`, Anthropic-compatible upstream SSE is translated into ordered `message_start`, content block, `message_delta`, and `message_stop` events.

Example:

```sh
curl https://nova.example.org/v1/messages \
  -H "x-api-key: $NOVA_APP_KEY" \
  -H 'anthropic-version: 2023-06-01' \
  -H 'Content-Type: application/json' \
  -d '{"model":"your-discovered-model","max_tokens":256,"system":"Be concise.","messages":[{"role":"user","content":"Hello"}]}'
```

## Workload analytics

- `GET /api/v1/analytics/workforce` (`agents:read`) returns per-agent saved assignment counts/outcomes, average duration only for completed assignments with both saved timestamps, provider request results, upstream-reported token totals, current backend status, and last recorded event.
- The Android Settings page presents these recorded values. Missing timing samples are shown as unavailable. Token totals can be zero when upstream usage metadata was not returned; no productivity scores or costs are inferred.

## Employee direct chat

- `GET /api/v1/agents/{agent_id}/chat` (`agents:read`) loads the latest saved conversation, or accepts `conversation_id` for a specific owner- and agent-scoped session.
- `POST /api/v1/agents/{agent_id}/chat` (`agents:write`) submits a user message to that employee's configured model/provider, using its saved instructions and bounded history. User messages and real provider replies are persisted separately; provider failures do not create fabricated assistant messages.
- PostgreSQL deployments must apply `database/migrations/0005_agent_chat.sql`; new local databases create the tables automatically.

## Meetings and collaboration

- `POST /api/v1/meetings` (`agents:write`) creates a persisted meeting with title, agenda, optional timezone-aware schedule, shared project context, optional linked task, and 1–10 owner-scoped employee invitations.
- `GET /api/v1/meetings` and `GET /api/v1/meetings/{id}` (`agents:read`) return invitation statuses and a bounded persisted transcript.
- `POST /api/v1/meetings/{id}/invitations` invites more owner employees before start. `POST /api/v1/meetings/{id}/invitations/{agent_id}/response` records accept/decline by the authenticated workspace owner; employees do not possess independent NOVA credentials.
- `POST /api/v1/meetings/{id}/start` makes real provider calls sequentially for accepted/pending, available participants. Each later turn receives the bounded prior transcript and shared context. `POST /cancel` cooperatively stops between employee calls. With the polling worker running, timezone-aware due meetings are auto-started; a scheduled meeting does not call providers before its due time.
- `POST /api/v1/meetings/{id}/handoffs` attaches meeting decisions to a queued task's prompt context and records an owner-scoped inter-agent `meeting_handoff` message to an invited recipient. This does not bypass the Head Manager's validated execution plan.

## Persisted office state and movement

- `GET /api/v1/office/state` (`agents:read`) creates/returns the owner's default 11-room layout and persistent agent grid positions.
- `PUT /api/v1/office/layout` (`office:write`) replaces the room/furniture layout after bounds, overlap, footprint, entrance, and saved-position validation. Layout edits are rejected while agents have active movement.
- `POST /api/v1/office/agents/{agent_id}/move` (`office:write`) calculates and saves an A* route or FIFO queued move; `/step` advances one collision-checked cell and persists it; `/cancel-move` stops at the last saved tile.
- The SQLAlchemy `create_all` path creates these tables for new local DBs. PostgreSQL deployments must apply migrations through `database/migrations/0008_shared_event_delivery.sql`. Existing SQLite DBs are not automatically migrated.

The Android HQ reads this saved state, provides an editor for room geometry/entrances and furniture placement, and advances walking through persisted `/step` requests. `office:write` is required for layout edits and movement; invalid collision/position changes are rejected by the server.

## Live workforce events

`GET /api/v1/events/recent` and `WS /api/v1/events` require the `events:read` scope. The WebSocket authenticates the same scoped NOVA Bearer key, sends an owner-filtered authoritative snapshot (agent roster, task state, provider status, scheduled meeting status, and a bounded recent event tail) immediately after connect, then streams that owner's persisted activity events. Android reconnects with backoff and refreshes its REST snapshot after reconnect or event bursts. PostgreSQL migration `0008_shared_event_delivery.sql` installs transactional `LISTEN`/`NOTIFY` event-ID fan-out; each API instance re-reads the persisted event and forwards it only to its local sockets. The broker path requires PostgreSQL and the migration; SQLite remains single-process. Office movement events are persisted separately; Android refreshes the authoritative office state and advances routes only through the validated movement API.

## Honest compatibility boundary

This remains a text-only protocol compatibility subset, not full OpenAI or Anthropic API compatibility. `stream: true` is supported through native SSE for OpenAI-compatible upstreams and Anthropic Messages upstreams; native Gemini and Cloudflare streaming are not implemented. Provider SSE errors are translated to protocol errors, broken/cancelled downstream streams close the upstream, and usage is recorded only when the stream ends; a client retry starts a new generation rather than resuming the previous one. Tool/function calls, images, audio, structured multimodal blocks, Anthropic tool-use blocks, and unsupported request fields are rejected. Some provider-specific fields remain unsupported. No real API credential was available for live integration tests; parser/translation tests use fake upstream event streams and do not establish live-provider compatibility.

## Native provider adapters

The backend includes a native Gemini REST adapter for model discovery and `generateContent`, authenticated with the `x-goog-api-key` header, and a native Anthropic `/v1/messages` adapter. Both handle bounded text history, configured system instructions, response text, token metadata when reported, and provider finish reasons. Adapters are HTTP-mock tested; no live provider acceptance test or inbound streaming is claimed.

## External gateway connections

Use Android **Settings → Configure multi-provider router** and provider identity `openai_compatible`, `anthropic_compatible`, or the retained `claude_gateway` adapter. Enter the gateway's HTTPS `/v1` base URL and its restricted gateway access token. Upstream provider keys belong at that gateway or in the backend runtime secret manager. The backend requires the exact hostname in `NOVA_GATEWAY_ALLOWED_HOSTS`; wildcard hosts, credentials embedded in URLs, query/fragment strings, non-HTTPS URLs, non-default ports, and paths other than `/v1` are rejected. The token is encrypted at rest with `NOVA_CREDENTIAL_ENCRYPTION_KEY`, never returned to clients, and never sent from NOVA directly to an upstream model API.

The gateway test performs model listing, not inference. Custom gateway adapters support either OpenAI-compatible `/models` and `/chat/completions` or Anthropic-compatible `/models` and `/messages`. Each NOVA owner can save one connection per gateway type. The gateway facade routes through owner-configured connection priority and bounded fallbacks only when the same exact model is available.

For direct built-in provider credentials, inject the existing `NOVA_PROVIDER`, `NOVA_PROVIDER_BASE_URL`, `NOVA_PROVIDER_API_KEY`, and `NOVA_MODEL` settings into the backend runtime using the deployment platform's secret manager. Ordinary application endpoints reject upstream provider keys submitted by clients. GitHub Actions secrets do not become runtime secrets unless a deployment workflow explicitly and securely injects them; this repository has no production deployment workflow.

See [provider configuration and limits](PROVIDERS.md), [security notes](SECURITY.md), and [progress/known gaps](PROGRESS.md).
