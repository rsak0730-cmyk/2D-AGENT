# NOVA progress and verification

## Implemented and verified in this branch (workforce phase)

- [x] Owner-scoped persistent 11-agent roster seed and individual model/instructions configuration.
- [x] Encrypted provider registry for Arena, Groq, Gemini OpenAI-compatible, OpenRouter, Cloudflare Workers AI, and allow-listed Claude Code Gateway; model catalog discovery; priority fallback; bounded error behavior.
- [x] Android provider setup center now accepts gateway host/token, describes the self-hosted requirement, displays saved connections with retest/disconnect actions, and avoids claiming model-list success proves inference or a free quota. Gateway endpoint must be HTTPS on an operator allow-list.
- [x] Added a pixel-art 2D Headquarters view with fixed desks/characters populated only from actual persisted agent records and visual working/error indicators from backend status.
- [x] Added optional speak-aloud controls to assistant replies using the Android device's configured TTS engine; this is not a generated voice service or live agent voice.
- [x] Persisted task states, manager-generated JSON delegation validation, worker assignments/results, final manager synthesis, explicit retry limit, usage records, and failures that remain failures.
- [x] Explicit saved agent/project/headquarters notes, owner-isolated agent messages, resource/request budgets, authenticated event history, mobile agent/task setup, and live persisted-event Headquarters feed.
- [x] PostgreSQL migration 0003 and mock-provider tests for encryption, routing fallback, delegation success, invalid planning, roster idempotence, and memory isolation.

Latest local backend verification for this in-progress provider phase: `28 passed, 1 skipped, 1 warning` (`.venv/bin/python -m pytest -q tests`). Android verification for this patch is pending GitHub Actions because this sandbox has no JDK/Gradle. Real-provider integration is not verified because this environment has no provider credentials.

## Known boundaries — do not claim complete

- Task execution is synchronously triggered by an API request and delegates serially. There is no independent durable worker, background queue, safe parallel subtask execution, multi-process locking/leases, crash recovery, cancellation, or horizontal scaling. Concurrency settings are persisted but do not create parallel workers.
- Provider token counts are recorded as zero where the provider usage metadata is not consumed; costs are not guessed. Provider and owner request ceilings are enforced, provider-level quotas remain authoritative.
- Memory is keyword/text notes, not vector search. Project memory is not automatically injected into task prompts. Tool permissions are metadata; no tools, shell, MCP, external action, or approval-gated operation is implemented.
- Headquarters now includes static pixel-art room art bound to roster and actual backend statuses, but there is no movement animation, saved position/occupancy, or independent office simulation loop. The baseline repo had no existing renderer to preserve/extend.
- Admin infrastructure endpoint uses the backend bootstrap secret and reports this process' DB/task/provider/usage counts; it is not a distributed cluster monitor.
- PostgreSQL migration is manual; existing SQLite databases are not migrated by `create_all`.
- Free tier/usage is provider/account/model-dependent. Current provider documentation and limitations are in `docs/PROVIDERS.md`; no live provider test is run in CI.

## Last known previous baseline

Before workforce changes, commit `29bc630` passed 16 backend tests, and Android/backend CI runs `45050141556`/`45050141594` passed. Those checks apply to that baseline, not to this unverified patch. See the current PR checks for updated outcomes.
