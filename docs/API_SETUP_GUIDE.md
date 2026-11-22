# API and credential setup

**Never commit API keys.** NOVA's assistant sends model requests from the backend. The Android provider form transmits the key directly to your backend over HTTPS; it never embeds or retains that provider key in Android or the APK. Use an eligible account, follow each provider's current terms, and supply billing/payment details only through that provider's official site. Availability, pricing, quotas and age eligibility change; check current terms rather than assuming a free tier.

## Arena — implemented adapter

1. Official docs: [Arena API](https://platform.arena.ai/docs/api), [errors](https://platform.arena.ai/docs/errors), [keys dashboard](https://platform.arena.ai/dashboard/keys).
2. Sign in/create an account at [Arena](https://platform.arena.ai/); review applicable eligibility, terms, funding and workspace policies.
3. Create an API key at the Keys page. Arena notes the key can only be copied once; store it in a secret manager immediately.
4. Configure either the backend environment (`NOVA_PROVIDER=arena`, `NOVA_PROVIDER_BASE_URL=https://api.arena.ai/v1`, `NOVA_PROVIDER_API_KEY=<secret>`, `NOVA_MODEL=<exact model ID>`) **or** enter your Arena key in the Android app's **Assistant → AI provider** setup. For mobile entry, add `NOVA_CREDENTIAL_ENCRYPTION_KEY` to backend secrets; generate it with `python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'`. Keep this encryption key stable, private and backed up: it encrypts provider keys stored in PostgreSQL. The phone sends the provider key only to your NOVA backend over HTTPS. It is not included in or persisted by the APK.
5. Create the NOVA API key with `assistant:read`, `assistant:write`, `providers:read`, and `providers:write` scopes for chat/history and mobile provider setup. Enter the exact model ID; use the app's **Test** action to fetch model IDs from Arena's documented `GET /v1/models` endpoint. `/health` does not spend model quota; chat sends a real provider request.
6. The mobile-stored provider key is encrypted at rest and scoped to that NOVA API key. Removing the mobile credential in the app deletes that override; if `NOVA_PROVIDER_API_KEY` is set on the server, the backend then falls back to that environment key.
7. 401/403: verify/re-enter key; 429: respect `Retry-After` and shared key limits; 402: check balance/payment; model errors: choose a returned model ID. Never retry a billing/auth error blindly. Revoke a compromised key in Arena's dashboard and clear or replace it in NOVA. Do not paste it in issue reports.

## Other provider and product setup status

| Service | Official entry | Current NOVA support | What is needed before enabling |
|---|---|---|---|
| Gemini | https://ai.google.dev/gemini-api/docs | Not implemented | Official model/endpoint adapter, server-side secret, current billing/eligibility review and provider tests |
| OpenAI-compatible | Provider's own official API documentation | Generic base URL can be configured in backend, but only with the OpenAI Chat Completions-compatible shape; no polished provider profiles/model discovery | Confirm exact API compatibility, model ID, auth headers, endpoint and data terms; test on a low-cost request |
| OpenRouter | https://openrouter.ai/docs | Not individually tested/profiled | Review official docs, account terms, model/provider routing, data policy, billing, quotas |
| Anthropic-compatible | https://docs.anthropic.com/ | No Anthropic Messages adapter | Implement separate adapter (Arena's Anthropic-compatible route is not used by current implementation); verify official terms and model IDs |
| ElevenLabs | https://elevenlabs.io/docs | Not implemented | Voice adapter, consent/data retention, backend-held API key, available voice/model and billing review |
| OAuth applications | Each application’s official developer portal | Not implemented | Register app, exact callback URIs, scopes, client secret storage, consent/refresh/revoke behavior |
| Telephony | Provider’s official developer portal | Not implemented | Suitable provider/number, consent recording, local telecom compliance, backend credentials and call-flow review |
| Database | https://www.postgresql.org/docs/ | PostgreSQL is supported by SQLAlchemy/Compose | Create a private database and least-privilege account; set `NOVA_DATABASE_URL`; use TLS, backups and migrations |
| Hosting | Host's official docs | Not deployed | Configure TLS, managed secrets, network policy, database, monitoring and rollback procedure |

The current gateway's default URL is Arena. Setting a random provider URL does not establish compatibility. No Google/OpenAI/OpenRouter/Anthropic/ElevenLabs account or key is assumed. NOVA model usage remains subject to provider quota, billing and policy; the NOVA API is not an unlimited model API.

## NOVA API admin bootstrap

Set a long random `NOVA_ADMIN_TOKEN` in a secret manager before starting. Use it only from a trusted administrative workstation to create scoped NOVA API keys. The raw NOVA key is displayed once; server stores only the digest. `DELETE /api/v1/admin/api-keys/{id}` revokes it. Never put `NOVA_ADMIN_TOKEN` on the phone. Current available scopes are validated by `/api/v1/admin/api-keys` implementation; grant minimum needed. See `/docs` OpenAPI on the running backend.
