# Security notes and limits

Implemented: provider secrets are read from backend env, not Android code; phone stores its NOVA bearer key encrypted using Android Keystore AES-GCM; API bearer keys are high-entropy values returned once and SHA-256 hashed in DB; scope checks, revocation, basic per-IP process-local rate limit, input length/schema validation, no upstream response-body reflection, and HTTPS-only Android backend URL.

Not yet implemented/verified: user identity/login and tenant isolation, encrypted provider credentials at rest beyond secret-manager deployment, audit log, distributed rate limit, TLS deployment configuration, backups/restore, API key management dashboard, security scan, agent permission matrix, approval workflow, prompt-injection defenses, connector sandbox and production threat model. Current admin token is a global bootstrap credential. Compose uses local plain HTTP. Do not deploy this code to the public internet as-is.

Android blocks cleartext backend URLs; Android Keystore contents are app-specific but reset/uninstall semantics apply. Protect scoped keys and revoke lost-device keys. The Android example disables backup. Provider keys must never be placed in build configuration or returned to clients. External calls and actions require explicit human approval before the product exposes them.
