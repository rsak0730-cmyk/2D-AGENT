# NOVA implementation checklist

This checklist is based on the unfinished items from the prior implementation report. Statuses are evidence-based; a UI element alone does not count as implementation.

## Branch and repository inspection

- [x] Confirm current branch is `arena/1524bd7f-2d-agent` and inspect the existing modified/untracked files before editing. HEAD/base is the one-file initial commit (`README.md` only); the complete project tree was present in the workspace as pre-existing untracked files, not tracked deletions. Preserved all of it; no reset, delete, or overwrite.
- [x] Preserve pre-existing gateway, workforce, and office changes; no files were reset or discarded.
- [x] Initial inspection found the office rendered roster slots and polled REST every 15 seconds, with no persisted map or movement integration; later increments replaced polling and added backend state.
- [x] Confirm existing backend has owner-scoped persistent agents/tasks/events/messages/memories and an authenticated WebSocket route; the publisher originally broadcast events without owner filtering (fixed and regression-tested in this increment).

## Implementation status

### Office world and movement

- [x] Expandable roster-bound pixel-art scene and stable role-based sprite palette.
- [x] Persisted owner-scoped room/furniture layout and agent tile positions; default layout includes head office, development, design, research, security, testing, writing, meeting, server, reception, and break areas.
- [x] Deterministic A* engine with furniture footprint/rotation/overlap/bounds/entrance validation, occupancy, next-step reservations, FIFO requests, collision-safe advancement, and cancellation; covered by four unit tests.
- [x] Persist active/queued movement routes and current employee positions in SQLAlchemy models, with PostgreSQL migration 0004.
- [x] Owner-scoped authenticated APIs for state, layout replace, movement request, persisted one-cell step, and cancellation; four API tests cover persistence, ownership, layout constraints, movement, and scope enforcement.
- [ ] Automatically select movement destinations from actual task/meeting assignment events.
- [x] Android scene renders persisted room/furniture grid and agent positions; profile routes agents to saved room entrances and animation follows backend-persisted A* steps.
- [x] Android office editor changes room geometry/entrances and adds, moves, rotates, or removes furniture through the owner-scoped validated backend layout API. Directional sprite frames and furniture-object actions remain absent; Android build is unverified locally.
- [~] Scene characters open live profiles and saved room navigation; persisted task assignment preferences are available, while assignment-driven room visits and furniture-object actions remain unimplemented.
- [ ] Interactive office objects connected to real profile/task/meeting/provider actions.

### Employee operations and collaboration

- [x] Persistent role identities, per-agent model/provider/instructions, task execution, owner-scoped memory, and message records.
- [x] Direct employee chat endpoint/UI uses the selected agent's persisted instructions, configured provider/model, bounded history, owner-scoped conversation persistence, and real provider responses; mocked API tests cover routing, history, and owner isolation.
- [~] Profile shows current assigned task, persisted saved-chat history, identity/model/permissions, and navigation. Owner-scoped pause/resume and cooperative stop controls are connected in Android Agents; per-worker direct execution and fine-grained memory editing controls remain limited; task creation now supports a preferred primary worker.
- [~] Persisted owner-scoped meetings, scheduled dispatch, invitations/responses, provider-backed participation, shared context/transcripts, status events, and task handoffs are implemented and covered by tests/UI. Separate collaborative rooms, shared documents, and approvals remain.
- [x] Bounded participant count, provider-call timeout use, owner isolation, and meeting participation tests; independent employee identities are still represented by owner-selected agent records (no separate employee principal).

### Task execution and synchronization

- [x] Validated model-generated plans, persisted assignments/results, bounded retries, explicit execution, and cooperative cancellation at provider-call boundaries.
- [~] Database-backed polling queue, owner-scoped dependencies, parallel worker leases, progress/heartbeat checkpoints, bounded retries/backoff, lease-expiry recovery, exact-match completed-output reuse, reassignment, and cooperative timeout/cancellation handling are implemented/tested; approval gates and multi-replica active-capacity safety remain.
- [x] Owner-filtered WebSocket event delivery, authoritative reconnect snapshot, bounded event replay, and tenant-isolation regression tests.
- [x] Android WebSocket subscription with event coalescing, reconnect/backoff, authoritative REST refresh on connect/reconnect, replacing fixed periodic polling (Android build remains unverified locally).

### Analytics and gateway

- [x] Persisted request/usage/events and basic recorded task/provider counters.
- [~] Owner-scoped per-agent assignment outcomes, measured completed durations, actual provider call counts and reported token counts are available in API and Android settings. No inferred productivity scores/costs; provider/model breakdown and historical date filters remain.
- [x] Mock-tested OpenAI/Anthropic outbound adapters, restricted compatible gateways, and encrypted app gateway tokens.
- [x] Native Gemini `models.list` and `generateContent` adapter uses `x-goog-api-key`, system instructions, role-mapped history, token usage, finish reasons, and model validation; HTTP-mock tests cover discovery and responses.
- [~] Native OpenAI-compatible and Anthropic Messages upstream text SSE parsing and inbound SSE translation, cancellation cleanup, and error paths are tested with fake event streams. Native Gemini/Cloudflare streaming, live protocol-client tests, and resuming a broken stream are unsupported.
- [ ] Live provider/deployment acceptance tests (requires operator credentials and infrastructure).

### Verification and delivery

- [x] Backend tests for this then-current worktree: `87 passed, 1 skipped, 1 warning`; backend/office-engine/tests `compileall` and `git diff --check` pass.
- [x] Official Gradle Wrapper v8.9.0 added from the Gradle v8.9.0 source release; compatible project config is AGP 8.7.3 + Gradle 8.9 + Kotlin 2.0.21 + JDK 17 + Android SDK 35.
- [x] Android Actions run 45187971363 passed on source SHA `e15b49cb8e9643cc3dd9e25688feaa22da746fb2`: JDK 17/SDK 35 setup, unit tests, lint, debug APK assembly, artifact verification, and upload all succeeded. Artifact: `nova-debug-apk` at https://github.com/rsak0730-cmyk/2D-AGENT/actions/runs/45187971363 (sandbox download attempt returned EOF from the artifact host).
- [x] Android Actions run 45197778179 passed on source SHA `defc2864a14a8d0873ddd5e812ea437554b60c33`: Gradle unit tests, lint, debug APK assembly, verification, and artifact upload succeeded. Local build remains blocked because this environment has no Java/JDK or Android SDK; `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` exits before Gradle startup: `JAVA_HOME is not set and no 'java' command could be found`.
- [x] Static secret-pattern scan found no high-confidence API/private-key patterns; workflow contains no injected secrets. App uses scoped API auth and Android Keystore storage; admin API remains backend-only.
- [~] Provider keys remain server-side and compatible-gateway tokens are encrypted; Actions produced the APK, but APK contents were not independently inspected in this sandbox because artifact download failed.
- [ ] PostgreSQL multi-replica lock/event-bus integration and live provider compatibility require infrastructure/credentials not present locally.


### Current HQ / assistant / requested-worker increment

- [x] Pixel-art Headquarters is the default and visually dominant Android home; command drawer and clean destinations expose Workers, Projects, Activity, Memory, and Settings. Worker sprites open their profile; task creation offers preferred-worker selection.
- [x] Compact persistent assistant dock, expandable conversation/history, animated globe/orb activity states, device speech recognition, and streaming response rendering are wired into Android. Selected text-like files (up to 256 KB UTF-8) are read locally and appended to the outgoing message. Binary/large files and multipart uploads are unsupported.
- [x] Owner-scoped `POST /api/v1/assistant/chat/stream` emits `meta`/`delta`/`done`/`error` SSE events and saves a response only after successful completion; two tests cover persistence and cross-owner rejection.
- [x] Optional task preferred-worker field is persisted and owner-validated. The preferred employee owns the primary/core assignment, while the manager can retain helper delegation. PostgreSQL migration 0009 adds the reference; existing SQLite files need a manual migration.
- [x] Current backend checks: `compileall`, full pytest `87 passed, 1 skipped, 1 warning`, and `git diff --check`; backend validation Actions run 45197777634 passed on `defc2864a14a8d0873ddd5e812ea437554b60c33`.
- [x] Android build/tests/lint/APK passed in Actions run 45197778179 on `defc2864a14a8d0873ddd5e812ea437554b60c33`; local build remains unavailable because no JDK or SDK is installed. Live upstream/provider and deployment acceptance are unrun.


### Living headquarters renderer increment (work in progress)

- [x] Native Compose Canvas night-city/glass-tower scene with distinct pixel-drawn workers, room/furniture sprites, lights, and persisted backend position/task visuals; offline preview never fabricates workers or statuses.
- [x] Six named department focus areas, tappable rooms/workers, profile activity, pinch zoom/pan, accessible zoom/recenter, and visual indicators tied to persisted routes/messages/meetings.
- [x] Persistent reduced-motion preference and app/HQ lifecycle pause; simplified drawing detail below the narrow-viewport threshold.
- [x] Added Memory Core room/furniture to new default office worlds, safe additive upgrade for compatible legacy default grids, and accepted its furniture type through backend validation/editor. API/navigation tests cover default world, compatibility upgrade, and movement/layout regression.
- [x] Backend checks after this increment: `89 passed, 1 skipped, 1 warning`; `compileall` and `git diff --check` pass.
- [x] Android Actions run 45205476348 passed on `c7c265586a6d58754aa3b05f287ecdd85e6b736a`: Kotlin/Compose compile, unit tests, lint, debug APK assembly/verification, and artifact upload. Backend validation run 45205476276 also passed on this SHA. Local JDK/SDK is absent; device frame-time, battery, touch instrumentation, and live backend/provider/deployment acceptance tests remain unrun. Custom layouts with occupied Memory Core footprint are deliberately left untouched.
