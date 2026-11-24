# NOVA character identity and voice implementation report

Date: 2026-11-22 (UTC)

## Outcome at a glance

The existing NOVA Android app and NOVA API now have an owner-scoped Head/Worker profile model, seeded fictional identities, a separate Head persona prompt, and voice configuration/preview routes. Android exposes these profiles from Settings → Characters, including Head and all ten workers, Voice ID validation, per-profile voice settings, preview, save, reset, and message-backed Head playback with device-TTS fallback.

**This is not a verified live ElevenLabs integration.** The UI and server adapter are present, and backend behavior is covered with mocked voice metadata. No authorized ElevenLabs request has succeeded in this environment. No confirmed NOVA-GATEWAY ElevenLabs endpoint/authentication contract was found in this repository or public repository search; the current server adapter uses the configured ElevenLabs HTTP API directly and must not be described as gateway-integrated. Runtime credentials, provider terms, user consent/data retention, and any gateway mediation remain externally unverified.

## Fully implemented and tested locally

- Persistent owner-scoped character table/model with PostgreSQL migration `database/migrations/0010_character_profiles.sql`; SQLite test schema uses the existing metadata creation path.
- One separate fictional female Head profile, Astra Vale, with independently editable identity/personality/specialization/communication style and a Head-specific prompt for assistant responses. Authorized headquarters memories and the actual saved conversation history are included as context.
- Ten fictional worker identities with unique names, detailed non-stereotyped personalities, specializations, communication styles, appearance descriptions, model/provider references, and live agent availability. The seeded split is five female and five male; the separate Head is female.
- Idempotent owner-scoped profile seeding and isolation. Each voice ID/settings row is keyed to that character; changing/resetting Head does not mutate any worker.
- Authenticated Voice ID validation and save that first request upstream voice metadata, reject invalid IDs, enforce explicit matching female/male metadata, and fail closed when ElevenLabs is unconfigured/unavailable. Per-character speed, stability, style, and similarity settings are validated and persisted.
- Preview route requests actual audio and returns `no-store` audio bytes. The app's Speak action sends the selected, actual assistant message to the Head profile and plays returned audio; errors fall back to Android device TTS and text remains visible. A per-character sample preview is also available.
- Provider credentials are read only from `NOVA_ELEVENLABS_API_KEY` server configuration; no provider key field is added to the APK. Voice previews disclose that selected text is sent to the configured provider.
- Backend unit/API tests: `tests/test_characters.py` covers exactly 11 profiles (Head + 10 workers), five/five worker gender count, unique differentiation, persistence, tenant isolation, validation errors, gender mismatch, voice isolation/reset, and missing server-secret behavior. Full suite currently passes: **95 passed, 1 skipped, 1 warning**.

## Partially implemented

- The current adapter uses ElevenLabs HTTP API semantics in the NOVA API service. It is not routed through a confirmed NOVA-GATEWAY voice contract. Replace/route this transport only after the gateway owner supplies the actual URL, authentication, voice metadata/gender, preview/audio, quota, and error contract.
- Android character profiles and per-character voice management are under Settings → Characters; they are not yet integrated into each worker's ordinary chat/profile card. Worker voice IDs are independently configurable and previewable, but worker chat output is not yet automatically synthesized in the worker's voice.
- Head text chat uses the selected persona; generated audio is initiated from an explicit Speak button on an actual assistant reply. There is no automatic speech for every response, and no multi-clip queue, dedicated cancel control, playback-position UI, emotional TTS style modeling, or durable audio history yet. Android's existing listening/thinking/streaming indicators remain activity-based; generated speech status reports actual preview/playback events.
- Device TTS fallback is supported. If the device engine cannot initialize, the text reply remains available with a visible error; voice fallback device/language behavior is not instrumented on a physical device here.
- Character appearance is represented as profile text/metadata, not generated or animated portraits. Authorized profile history is available through existing conversation history; there is no separate character-specific memory permission editor.

## Not implemented

- No gateway-specific ElevenLabs adapter, protocol negotiation, remote voice catalog picker, provider quota/cost telemetry, or authorization/consent workflow.
- No automatic voice playback for Worker messages, multi-message queue, background audio service, or per-character audio cache/history.
- No verified APK content scan of a newly built artifact in this environment; source contains only server-config references and Android sends Voice IDs/text, not provider credentials.
- No physical-device verification of voice playback, interruption, lifecycle cleanup, speech-recognition states, accessibility, or audio focus.

## Externally blocked / required before production claim

1. Operator configures `NOVA_ELEVENLABS_API_KEY` in a server secret manager (never `.env` in source control, app settings, CI artifacts, or APK), and confirms valid server egress and account permissions.
2. NOVA-GATEWAY owner provides its authoritative ElevenLabs voice API contract. Confirm whether direct ElevenLabs access is permitted or all calls must traverse the gateway; if gateway use is mandatory, implementation must be adjusted before release.
3. Authorized live tests successfully list/validate the selected Voice ID and synthesize/return one sample; verify gender metadata availability, actual error responses, model/settings compatibility, quotas, billing, data retention, and consent terms. Until then, no live integration claim is permitted.
4. Android build/lint/device playback verification is blocked in this sandbox because no JDK/Android SDK is installed (`java: command not found`).
5. Existing PostgreSQL deployments must apply migration 0010. SQLite existing databases need their deployment's migration mechanism; `create_all` does not alter existing tables.

## Verification run

- `.venv/bin/python -m pytest tests/test_characters.py -q`: 4 passed (mocked upstream only).
- `.venv/bin/python -m pytest -q`: 94 passed, 1 skipped, 1 warning.
- `./gradlew --no-daemon :app:compileDebugKotlin`: not run to completion because the environment has no Java/JDK.
- No live ElevenLabs or NOVA-GATEWAY voice request has been made.
