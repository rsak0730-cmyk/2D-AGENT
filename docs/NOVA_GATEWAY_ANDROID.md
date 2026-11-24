# NOVA Android ↔ NOVA-GATEWAY

The app's Gateway tab targets `https://gateway-server-mea8.onrender.com` and its workspace WebSocket at `wss://gateway-server-mea8.onrender.com/ws/{workspace_id}?token={access_token}`. The endpoint was wired from the user-provided contract; live gateway compatibility has not yet been verified from this workspace.

## Authentication and secrets

- Register/login use `/v1/auth/register` and `/v1/auth/login`.
- Authenticated REST requests send `Authorization: Bearer <access_token>` and `X-Workspace-Id: <workspace_id>`.
- A `401` triggers `/v1/auth/refresh`; refreshed credentials replace the stored values. If refresh fails, the session is cleared.
- Only `access_token`, `refresh_token`, and `workspace_id` are persisted for the gateway session. `SecureSettings` encrypts values with an Android Keystore AES-GCM key.
- Old NOVA API base/token preferences are cleared on app startup. Provider keys are never compiled into the APK or written to preferences/files. The gateway-registration form sends a provider key in the HTTPS `/v1/gateways` request body and immediately clears its input; the gateway is responsible for storing it.

## Connected API surface

The Gateway workspace screen reads Head status, workers, gateways, Head/worker model pools, tasks, messages, memory, and activity. It supports Head orchestration, worker model assignment/chat, task/message/memory creation, gateway registration, and model discovery. Workspace JSON events are received over WebSocket, workspace-checked, and displayed; the socket reconnects with backoff.

Head and worker AI chat is routed through NOVA-GATEWAY. The older legacy NOVA credential inputs and provider/model editor are no longer exposed. The legacy office renderer remains separate because the supplied gateway contract has no office-layout or movement endpoints.

## Verification limitation

No request was made to the live host during implementation. The Android compile/device test is also pending because the sandbox has no Java executable. Confirm the deployed gateway's exact response schemas and API-key handling before production use.
