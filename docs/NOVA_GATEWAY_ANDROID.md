# NOVA Android ↔ NOVA-GATEWAY

The Android client targets `https://gateway-server-mea8.onrender.com` and the workspace WebSocket `wss://gateway-server-mea8.onrender.com/ws/{workspace_id}?token={access_token}`. This endpoint was wired from the supplied contract. No live request has been made from this workspace, so deployed response compatibility remains unverified.

## Authentication and secrets

- Register/login use `/v1/auth/register` and `/v1/auth/login`.
- Authenticated REST requests send `Authorization: Bearer <access_token>` and `X-Workspace-Id: <workspace_id>`.
- A `401` triggers `/v1/auth/refresh`; refreshed credentials replace the stored values. If refresh fails, the session is cleared.
- Only `access_token`, `refresh_token`, and `workspace_id` are persisted for the gateway session. `SecureSettings` encrypts values with an Android Keystore AES-GCM key.
- Old NOVA API base/token preferences are cleared on app startup. Provider keys are never compiled into the APK or written to preferences/files. The gateway-registration form sends a provider key in the HTTPS `/v1/gateways` request body and immediately clears its input; the gateway is responsible for storing it.

## Current Android screens and endpoints

The workspace screen loads Head status, workers, gateways, Head/worker model pools, tasks, messages, memory, and activity. It offers Head orchestration, worker model assignment/chat, task/message/memory creation, gateway registration, and model discovery. Workspace JSON events are received over WebSocket, workspace-checked, and displayed; the socket reconnects with backoff.

The Chat screen submits to the gateway Head orchestration API. It is a request/response operation in this client, not SSE/delta streaming. The Projects screen reads/creates records through the already-used `/v1/tasks` collection. The documented/current client surface does not expose a distinct project entity API, so tasks are not represented as fabricated projects.

The Agents screen includes the specified character identity catalog and labels live values as unavailable/unverified unless returned by a backend. Its current `WorkforceAgent`/`CharacterProfile` live binding still belongs to the legacy NOVA REST client and is not populated by the Gateway session. The Gateway workspace screen can show the server-returned worker records, but the Android project has no verified Gateway response schema for lossless mapping into canonical character profiles or office sprites.

The two-floor HQ is a native visual layout preview. The repository's legacy NOVA backend has one grid-based office and no floors; the Gateway contract has no office positioning, floor transitions, or stair movement routes. Therefore the client does not infer that a character changed floors or show an authoritative task/activity unless a backend record supplies it. See [Headquarters](HEADQUARTERS.md).

Character voice/profile APIs exist in the separate NOVA backend (`/api/v1/characters` and its voice routes), but they are not exposed by the Android Gateway API. Do not configure a second client credential or invent gateway endpoints to reach them; a supported, workspace-scoped Gateway contract is needed to connect those controls under the app's JWT-only storage rule.

## Verification limits

No request was made to the live host. Android compilation/APK generation is verified only when the GitHub Actions Android workflow succeeds. This does not prove Gateway live response schema, image-reference fidelity, project entities, Gateway-backed profile/voice data, model provenance, or floor-aware movement. The supplied character and office reference images were not present in this checkout; the native illustration is not claimed as an exact reproduction.
