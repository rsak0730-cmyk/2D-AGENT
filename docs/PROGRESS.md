# NOVA progress and verification

## Implemented and verified in this branch (workforce phase)

- [x] Owner-scoped persistent 11-agent roster seed and individual model/instructions configuration.
- [x] Encrypted provider registry for Arena, Groq, Gemini OpenAI-compatible, OpenRouter, and Cloudflare Workers AI; model catalog discovery; priority fallback; bounded error behavior.
- [x] Persisted task states, manager-generated JSON delegation validation, worker assignments/results, final manager synthesis, explicit retry limit, usage records, and failures that remain failures.
- [x] Explicit saved agent/project/headquarters notes, owner-isolated agent messages, resource/request budgets, authenticated event history, mobile agent/task setup, and live persisted-event Headquarters feed.
- [x] PostgreSQL migration 0003 and mock-provider tests for encryption, routing fallback, delegation success, invalid planning, roster idempotence, and memory isolation.

Current backend test count is recorded from the latest actual test run below; Android is only verified after the Android Actions workflow completes. Real-provider integration is not verified because this environment has no provider credentials.

## Known boundaries — do not claim complete

- Task execution is synchronously triggered by an API request and delegates serially. There is no independent durable worker, background queue, safe parallel subtask execution, multi-process locking/leases, crash recovery, cancellation, or horizontal scaling. Concurrency settings are persisted but do not create parallel workers.
- Provider token counts are recorded as zero where the provider usage metadata is not consumed; costs are not guessed. Provider and owner request ceilings are enforced, provider-level quotas remain authoritative.
- Memory is keyword/text notes, not vector search. Project memory is not automatically injected into task prompts. Tool permissions are metadata; no tools, shell, MCP, external action, or approval-gated operation is implemented.
- Headquarters is event-backed status/activity, not pixel art. This baseline repo had no existing sprite renderer to preserve/extend.
- Admin infrastructure endpoint uses the backend bootstrap secret and reports this process' DB/task/provider/usage counts; it is not a distributed cluster monitor.
- PostgreSQL migration is manual; existing SQLite databases are not migrated by `create_all`.
- Free tier/usage is provider/account/model-dependent. Current provider documentation and limitations are in `docs/PROVIDERS.md`; no live provider test is run in CI.

## Last known previous baseline

Before workforce changes, commit `29bc630` passed 16 backend tests, and Android/backend CI runs `45050141556`/`45050141594` passed. Those checks apply to that baseline, not to this unverified patch. See the current PR checks for updated outcomes.
