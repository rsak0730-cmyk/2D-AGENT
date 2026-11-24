# Api Security Skill

## Purpose
Protect NOVA credentials, identities, APIs, personal data, and approval-sensitive operations.

## Required knowledge
Threat modeling, OWASP API risks, key hashing/encryption, scopes, rate limits, auditability.

## Supported tools
Android Keystore, environment secrets, PostgreSQL, automated security tests.

## Development rules
No secrets in source, logs, test snapshots, or public artifacts. Never silently elevate scopes.

## Implementation procedure
Map assets and trust boundaries; minimize data; hash user API keys; encrypt provider credentials; add revocation and audit events.

## Security requirements
Default deny; HTTPS outside local development; require approval for destructive, external, financial, legal, or private-data actions.

## Testing procedure
Test unauthenticated access, scope isolation, expiry, revoke, redaction, and rate limits.

## Troubleshooting
Rotate leaked credentials immediately; inspect redacted audit events and token scope.

## References
https://owasp.org/www-project-api-security/ ; https://developer.android.com/privacy-and-security/keystore
