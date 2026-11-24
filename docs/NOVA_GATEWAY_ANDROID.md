# NOVA Android ↔ NOVA Central Server

The native Android app connects to `https://gateway-server-mea8.onrender.com`. The user flow is **INSTALL → OPEN → USE NOVA**. The device registers itself automatically on first launch; regular users are never asked to create an account, sign in, or supply an AI provider/gateway key.

## Installation authentication

1. The app generates a random UUID `device_id` once and stores it encrypted with the Android Keystore-backed `SecureSettings` store.
2. First launch calls `POST /v1/installations/register` with `{ "device_id": "…", "app_version": "1.0.0" }`.
3. The returned `installation_id`, `workspace_id`, `installation_token`, and `expires_at` are encrypted at rest. No user account, provider credential, administrator credential, or provider API key is stored or packaged in the app.
4. Authenticated REST requests send `X-Workspace-Id` and `X-Installation-Token`. When expiry is within five minutes, or a request returns 401, the client calls `POST /v1/installations/refresh` using the installation ID/token and retries an unauthorized request once.
5. If installation/refresh fails, the UI reports a connection problem and offers retry. It does not switch to user login or an offline/fabricated AI response.

Registration has no existing workspace/token headers because the server has not issued them yet. The refresh request includes the current workspace and installation-token headers as well as the required refresh body.

## Endpoint integration

- Model catalog: `GET /v1/models` and `GET /v1/models/families`.
- Agent permissions: `GET /v1/agents/head/permissions` and `GET /v1/agents/worker_1/permissions` through `worker_10`.
- Chat: `POST /v1/chat/completions` with the model assignment read from the permission response, the user message, `max_tokens: 512`, and the canonical `agent_key`. The app does not select a provider, make inference calls, or perform failover. It refuses to guess a model if the server has not returned an assignment.
- Workspace data: `GET/POST /v1/tasks`, `/v1/messages`, and `/v1/memory`, plus `GET /v1/activity`. Projects displays task records (there is no separate project resource in the supplied contract).
- Voice: `POST /v1/voice/tts`; if the Gateway does not return audio, the app keeps the assistant reply as text and reports the fallback. It does not route voice through a provider directly.

Gateway responses remain authoritative. The app doesn't fabricate task execution, agent activity, model assignment, or permissions. `GatewayWorkspaceScreen` shows returned model catalog, family, permission, task, message, memory and activity data. The Headquarters/character roster still relies on a separate legacy REST shape that is not exposed in the supplied Gateway contract, so this integration does not pretend to map missing office/profile data into live Gateway records.

## Verification limits

No live request has been made to the deployed Gateway from this workspace, so endpoint response compatibility and server-side installation policy remain unverified. The Android code and debug APK must be compiled in the Java-enabled CI workflow. If permission responses do not contain a recognizable assigned-model field, chat fails closed with a clear message rather than choosing an arbitrary catalog model. The current client uses `/v1/chat/completions`; the supplied model-specific `/v1/models/{model_identity_id}/chat` route is not needed for the agent-key completion flow.
