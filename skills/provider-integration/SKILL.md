# Provider Integration Skill

## Purpose
Add provider-independent model API configuration and bounded failure handling.

## Required knowledge
OpenAI-compatible chat completions, model IDs, 402/429, Retry-After, accounting.

## Supported tools
Arena adapter currently uses its documented OpenAI-compatible API; HTTPX adapter.

## Development rules
Mobile UI may submit provider credentials to the NOVA backend over HTTPS, but credentials must never persist in Android preferences, source, resources or APK. Never retry indefinitely or conceal spend/quota failures.

## Implementation procedure
Verify official docs; configure base URL/key/model; call models or minimal request; parse validated response; record safe usage metadata.

## Security requirements
Keep provider keys in server environment or Fernet-encrypted backend storage using a separate, stable secret-manager key. Redact upstream bodies; do not imply unlimited quota.

## Testing procedure
Mock success, malformed JSON, timeout, invalid key, 402, 429 Retry-After, and outage.

## Troubleshooting
Use provider status and request IDs if safe; classify billing vs transient errors distinctly.

## References
https://platform.arena.ai/docs/api ; https://platform.arena.ai/docs/errors
