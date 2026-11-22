# Security posture

NOVA remains an early-stage, not production-ready application. Do not expose the development server or send sensitive production data until you deploy TLS, shared gateway rate limits, database migration/backup controls, secret-manager storage/rotation, and a durable worker design.

## Added workforce controls

- API keys are hashed at rest and scopes are enforced on every user API. Agents, tasks, memories, provider connections, usage and events are filtered by `owner_key_id`; cross-owner IDs return not found.
- Provider API secrets are Fernet-encrypted at rest using `NOVA_CREDENTIAL_ENCRYPTION_KEY`, never returned in API views, and never stored on Android. Secrets must be supplied only over HTTPS. Key loss makes ciphertext unrecoverable; back up separately. Revocation at the provider is still necessary when removing a stored key.
- Provider endpoints are fixed by provider identity (Arena configured base, known OpenAI-compatible endpoints, or Cloudflare account-specific endpoint); the client cannot submit a URL, reducing SSRF risk. Model IDs come from owner-authenticated model discovery.
- Router retries/fallback are finite. 402 billing errors stop. Provider bodies are not reflected in error responses. Per-connection and owner request budgets are checked before calls; provider-specific rate and spend controls remain authoritative.
- Task plans are constrained to known role IDs, a maximum of five unique text-only assignments, and persisted assignment status. No tool runtime is exposed, no host shell is invoked, and tool-permission metadata defaults empty. Provider output and memory are untrusted text; no instruction from them is executed as a host action.
- The admin infrastructure API requires `NOVA_ADMIN_TOKEN`; it is not accessible with ordinary scoped API credentials.

## Remaining production gaps

Task execution runs synchronously in the API process; a crash or horizontal scale can cause orphaned/inconsistent work. There are no leases/idempotency protections for multi-instance workers, durable queue, separate event broker, approval workflow, user-configurable retention/deletion for all task audit data, abuse controls by account, or production deployment. The per-process request rate limiter and WebSocket registry do not scale. Review `docs/ARCHITECTURE.md` before operating this branch.

Never commit provider keys, API tokens, admin secrets, encryption keys, user conversations, local DB files, or backups. Use synthetic test secrets and mocked provider HTTP responses.
