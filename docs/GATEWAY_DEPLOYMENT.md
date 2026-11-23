# Gateway deployment and secrets

## What exists in this repository

NOVA's FastAPI service can act as an owner-authenticated, text-only OpenAI/Anthropic-shaped facade with SSE streaming for supported upstream protocols. It can also call a configured OpenAI-compatible external gateway such as an operator-managed LiteLLM/Bifrost/New API-style service, provided the gateway exposes `/v1/models` and `/v1/chat/completions`. The gateway URL is supplied at configuration time and restricted by exact `NOVA_GATEWAY_ALLOWED_HOSTS` entries. This is not a production deployment recipe for any particular gateway vendor.

There is no production deploy target or GitHub Actions deployment workflow in this repository. The existing GitHub Actions workflows are CI-only: they install/test the backend and build the Android APK. They declare read-only repository permissions and do not consume secrets. A GitHub Secret does not automatically become a running backend environment variable.

## Backend runtime configuration

Copy `.env.example` only for local development. For deployment, inject values directly into your platform's backend runtime secret manager/environment:

- `NOVA_DATABASE_URL` — private database connection string.
- `NOVA_ADMIN_TOKEN` — long random admin bootstrap secret; never an application key.
- `NOVA_CREDENTIAL_ENCRYPTION_KEY` — Fernet key protecting stored restricted gateway tokens.
- `NOVA_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS` — optional comma-separated old key(s) during rotation only.
- `NOVA_GATEWAY_ALLOWED_HOSTS` — comma-separated exact DNS names; configuration, not a secret; do not use wildcard values.
- `NOVA_REQUEST_TIMEOUT_SECONDS`, `NOVA_MAX_COMPLETION_TOKENS`, `NOVA_MAX_PROMPT_CHARACTERS` — safe bounds.
- Optional direct single-provider runtime: `NOVA_PROVIDER`, `NOVA_PROVIDER_BASE_URL`, `NOVA_PROVIDER_API_KEY`, and `NOVA_MODEL`. The current built-in runtime-key setting represents one configured provider, not a complete multi-provider secret manager. For a multi-provider deployment, prefer storing upstream keys in the external gateway and give NOVA only a restricted gateway token.

The application client must use a separately created scoped NOVA application key. It must never receive `NOVA_ADMIN_TOKEN` or an upstream provider API key.

## Credential encryption key rotation

1. Back up the database and both key values securely.
2. Generate a new Fernet key outside the repository.
3. Deploy the new key as `NOVA_CREDENTIAL_ENCRYPTION_KEY` and the previous key as `NOVA_CREDENTIAL_ENCRYPTION_KEY_PREVIOUS`.
4. Call `POST /api/v1/admin/credentials/rotate` with the admin token from an operator-only channel. The response contains only row counts.
5. Verify all stored gateway connections still pass their model-list test.
6. Remove the previous key from runtime only after successful verification and backup.

The key rotation endpoint does not change provider/gateway access tokens at their issuer. Rotate those separately at the gateway and replace the restricted NOVA gateway token using the app setup flow.

## GitHub Secrets: do not confuse CI with runtime

No current workflow references `${{ secrets.* }}` or deploys NOVA. Do not add production provider keys to Android build jobs: they are not needed to build the APK. If an organization later adds a reviewed deploy workflow, the deployment job may map environment/repository secrets into backend runtime only, without echoing them or packaging them into artifacts. Suggested names for such an operator-controlled deployment are `NOVA_ADMIN_TOKEN`, `NOVA_DATABASE_URL`, `NOVA_CREDENTIAL_ENCRYPTION_KEY`, and—only for direct single-provider runtime—`NOVA_PROVIDER_API_KEY`. Put provider keys such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `GROQ_API_KEY`, or `OPENROUTER_API_KEY` in the external gateway's own secret manager when using that approach; this NOVA branch does not automatically load that whole set from GitHub Secrets. Do not create unused GitHub Secrets and assume they are live.

In GitHub, secrets are managed under **Repository → Settings → Secrets and variables → Actions**. Prefer environment-scoped secrets with deployment approvals. No real values belong in chat, `.env.example`, CI output, build summaries, or source files.

## Connect a client

- NOVA Android: set the HTTPS backend URL and scoped NOVA application key. Configure an external gateway in **Settings → Configure multi-provider router** using the exact allowed HTTPS `/v1` base and a restricted gateway token. Model discovery is a catalog check, not an inference health guarantee.
- Generic API client: use `https://<your-nova-host>/v1` as its base URL and the scoped NOVA key, using the OpenAI or Anthropic authentication header described in [NOVA API docs](NOVA_API.md). Text SSE streaming is translated for supported OpenAI-compatible and Anthropic Messages upstreams; multimodal/tool parity and stream resumption are not supported.
- Claude Desktop: `/v1/messages` now emits Anthropic-shaped text stream events, but Claude Desktop has not been live-tested and no compatibility claim should be inferred from protocol unit tests alone. Use the curl examples in `docs/NOVA_API.md` for scoped API validation.

There is no production public backend hostname or downloadable APK artifact attached to this phase. CI builds and verifies a debug APK; it contains no runtime provider secrets because no credential is passed to the Android build.
