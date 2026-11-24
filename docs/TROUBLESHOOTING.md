# Troubleshooting

- `503 No AI provider key configured`: configure `NOVA_PROVIDER_API_KEY` in backend secrets, or add `NOVA_CREDENTIAL_ENCRYPTION_KEY` and save an Arena key from the app's AI provider setup. Provider setup requires `providers:read` and `providers:write` scopes.
- `401` from NOVA: scoped token is missing/invalid/revoked/expired; create a new key with minimum required scope. Do not share admin token with mobile app.
- `403 Missing required scope`: issue a new narrow key containing the required scope; scope escalation is intentional.
- Provider `429`: respect returned `Retry-After`, avoid tight retry loops and remember Arena keys share quota limits.
- Provider `402`: account needs eligible funds/payment; waiting will not resolve a balance issue.
- Provider `502`: upstream rejects credential or returned failure; verify exact configured endpoint and model. Upstream body is intentionally not exposed.
- `/health` `503`: database is unavailable; check database URL, container health and network.
- Android refuses URL: backend must be HTTPS; local Android emulator `localhost` is not the host machine. Use emulator-specific host routing or a secure accessible development endpoint.
- Android build unavailable locally: requires JDK 17, Gradle 8.9 and Android SDK 35; check CI build logs. Do not claim success without build output.
- No agent motion/execution: those systems are not implemented; the app labels this explicitly.
