# Provider Integration Skill

## Purpose
Add provider-independent model API configuration and bounded failure handling.

## Required knowledge
OpenAI-compatible chat completions, model IDs, 402/429, Retry-After, accounting.

## Supported tools
Arena adapter currently uses its documented OpenAI-compatible API; HTTPX adapter.

## Development rules
Never expose provider credentials to Android; never retry indefinitely or conceal spend/quota failures.

## Implementation procedure
Verify official docs; configure base URL/key/model; call models or minimal request; parse validated response; record safe usage metadata.

## Security requirements
Keep credentials in server environment/secret manager; redact upstream bodies; do not imply unlimited quota.

## Testing procedure
Mock success, malformed JSON, timeout, invalid key, 402, 429 Retry-After, and outage.

## Troubleshooting
Use provider status and request IDs if safe; classify billing vs transient errors distinctly.

## References
https://platform.arena.ai/docs/api ; https://platform.arena.ai/docs/errors
