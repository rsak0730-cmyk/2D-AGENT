# Security Policy

NOVA is an early-stage foundation, not production-ready. Do not deploy it with real sensitive data or expose it directly to the public internet. The current controls and gaps are documented in [docs/SECURITY.md](docs/SECURITY.md).

## Reporting a vulnerability

Do not open a public issue containing secrets, personal data, or an exploitable vulnerability. Contact the repository maintainers through a private GitHub security advisory if enabled. Include affected version/commit, reproduction steps, impact, and remediation suggestion; redact real keys and account identifiers.

## Secret handling

Never commit provider keys, admin tokens, scoped NOVA keys, signing material, user data, or database backups. Revoke any exposed key immediately at its provider and replace the server secret. Test with synthetic tokens only.
