# API and credential setup

**Never commit API keys.** NOVA's assistant sends model requests from the backend. The Android app does not accept upstream provider keys; configure those only as backend runtime secrets or in an external gateway's secret manager. Android may save a restricted gateway token to the backend over HTTPS, where it is encrypted at rest. Use eligible accounts and current provider terms; availability, pricing, quotas and age eligibility can change.

## Arena — implemented adapter

1. Official docs: [Arena API](https://platform.arena.ai/docs/api), [errors](https://platform.arena.ai/docs/errors), [keys dashboard](https://platform.arena.ai/dashboard/keys).
2. Sign in/create an account at [Arena](https://platform.arena.ai/); review applicable eligibility, terms, funding and workspace policies.
3. Create an API key at the Keys page. Arena notes the key can only be copied once; store it in a secret manager immediately.
4. Configure the backend runtime environment (`NOVA_PROVIDER=arena`, `NOVA_PROVIDER_BASE_URL=https://api.arena.ai/v1`, `NOVA_PROVIDER_API_KEY=<secret>`, `NOVA_MODEL=<exact model ID>`) using your deployment's secret manager. Do not put this provider key in the app, repository, APK, or GitHub Actions build job.
5. Create a scoped NOVA API key with `assistant:write` for chat, `assistant:read` or `providers:read` for model listing, and `providers:write` only if the client needs to test a configured provider. The Android **AI provider** screen changes the selected model and tests the server runtime credential; it cannot upload an upstream key. `/health` does not spend model quota; chat sends a real provider request.
6. If storing restricted compatible-gateway tokens in NOVA, configure `NOVA_CREDENTIAL_ENCRYPTION_KEY` in backend secrets; generate it with `python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'`. Keep it stable and backed up separately from the database. The mobile router token is encrypted at rest; this is distinct from an upstream provider key.
7. 401/403: verify the backend runtime secret; 429: respect `Retry-After` and shared key limits; 402: check balance/payment; model errors: choose a returned model ID. Never retry a billing/auth error blindly. Revoke a compromised provider key in Arena and rotate it in the backend secret manager. Do not paste it in issue reports.

## Other provider and product setup status

| Service | Official entry | Current NOVA support | What is needed before enabling |
|---|---|---|---|
| Gemini | https://ai.google.dev/gemini-api/docs | Native `models.list` and `generateContent` adapter; mock-tested only | Configure `NOVA_PROVIDER=gemini`, backend-held `NOVA_PROVIDER_API_KEY`, and a discovered model ID; live acceptance and current billing/eligibility review remain operator tasks |
| OpenAI-compatible | Provider's own official API documentation | Generic base URL can be configured in backend, but only with the OpenAI Chat Completions-compatible shape; no polished provider profiles/model discovery | Confirm exact API compatibility, model ID, auth headers, endpoint and data terms; test on a low-cost request |
| OpenRouter | https://openrouter.ai/docs | Not individually tested/profiled | Review official docs, account terms, model/provider routing, data policy, billing, quotas |
| Anthropic | https://docs.anthropic.com/ | Native `/v1/models` and `/v1/messages` adapter; mock-tested, not live-tested | Configure `NOVA_PROVIDER=anthropic`, backend-held key, and exact model ID; review current account terms and billing |
| ElevenLabs | https://elevenlabs.io/docs | Not implemented | Voice adapter, consent/data retention, backend-held API key, available voice/model and billing review |
| OAuth applications | Each application’s official developer portal | Not implemented | Register app, exact callback URIs, scopes, client secret storage, consent/refresh/revoke behavior |
| Telephony | Provider’s official developer portal | Not implemented | Suitable provider/number, consent recording, local telecom compliance, backend credentials and call-flow review |
| Database | https://www.postgresql.org/docs/ | PostgreSQL is supported by SQLAlchemy/Compose | Create a private database and least-privilege account; set `NOVA_DATABASE_URL`; use TLS, backups and migrations |
| Hosting | Host's official docs | Not deployed | Configure TLS, managed secrets, network policy, database, monitoring and rollback procedure |

The current gateway's default URL is Arena. Setting a random provider URL does not establish compatibility. No Google/OpenAI/OpenRouter/Anthropic/ElevenLabs account or key is assumed. NOVA model usage remains subject to provider quota, billing and policy; the NOVA API is not an unlimited model API.

## NOVA API admin bootstrap

Set a long random `NOVA_ADMIN_TOKEN` in a secret manager before starting. Use it only from a trusted administrative workstation to create scoped NOVA API keys. The raw NOVA key is displayed once; server stores only the digest. `DELETE /api/v1/admin/api-keys/{id}` revokes it. Never put `NOVA_ADMIN_TOKEN` on the phone. Current available scopes are validated by `/api/v1/admin/api-keys` implementation; grant minimum needed. See `/docs` OpenAPI on the running backend.
