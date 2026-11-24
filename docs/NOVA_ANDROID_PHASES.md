# NOVA Android implementation status

This note records what the checked-in native Android code does today and what the existing APIs do not support. `Complete` below means the feature is implemented in the repository and has local or CI evidence, not that unverified external services or absent source art have been accepted.

| Phase | Status | Implementation / boundary |
|---|---|---|
| 1. Empty two-floor office | Implemented as visual preview | `TwoFloorOffice.kt` draws two selectable architectural floor views, ten desks per floor, an upper command globe, night skyline, stairs, rails, lounge/refreshment details, and pan/zoom. Supplied reference images are not in this checkout, so pixel-level reproduction is unverified. |
| 2. Eleven identities | Catalog and backend defaults aligned | `CharacterRoster.kt` and `backend/nova_api/characters.py` use Nova, Aiden, Mira, Luna, Zen, Riya, Kian, Sia, Aron, Evelyn and Rex, with the requested roles. The visual asset set is not present; existing procedural sprites are not a substitute for a confirmed exact likeness. |
| 3. Expressions and animation | Partial | Existing `PixelWorkerSprite` has lightweight pose/face motions and task/event-linked pose selection. It does not yet implement the full 14-expression set or authored frame-based sprite sheets. |
| 4. Worker occupancy | Partial | Live worker IDs/statuses can drive character sprites when the legacy backend roster is populated. No duplicate agent is shown in both floor views at once. The Gateway session is not mapped to these legacy models. |
| 5. Navigation and selection | Partial | Headquarters/lower floor toggle, pinch pan/zoom, recenter and selectable workers are available. The user-facing bottom navigation is now Headquarters, Chat, Agents, Projects, Settings. Floor transitions are not authoritative because no floor movement API exists. |
| 6. Backend identities/status | Partial | Existing Gateway workspace screen reads its supplied workspace contract; Head/worker AI requests go through NOVA-GATEWAY. Its live response schema was not tested. Gateway data is not yet normalized into the legacy office/profile models. No model assignment is called verified without provenance. |
| 7. Main destinations | Partial | Chat and Gateway workspace use NOVA-GATEWAY. Agents has the canonical catalog and clear unavailable/unverified labels. Projects lists/creates actual Gateway task records through `/v1/tasks`; the contract has no separate project API. Settings retain existing account, security, appearance, voice and integration controls where supported. |
| 8. Voice | Partial | The separate NOVA backend has workspace-scoped character voice endpoints and server-side ElevenLabs handling; these routes are not exposed by the current Gateway client, so mobile character voice configuration cannot safely use the app's Gateway-only JWT session yet. Device speech remains a local fallback. |
| 9. Build and verification | Verified in CI | Android Debug APK run `45430705564` passed tests, lint, debug APK assembly and APK verification on commit `de449349d38fcaea528db4f1fe15c71a467cea87`; it uploaded `nova-debug-apk`. Backend validation also passed on that commit (run `45430730840`). No local Java runtime is available in the sandbox. |

## Concrete API/artifact requirements to finish

1. Add the missing character image references and approved sprite assets (all supplied design images must be committed under Android resources or otherwise made available to the build).
2. Publish a versioned NOVA-GATEWAY schema for worker IDs/roles/profiles, activity/task state, model assignment provenance, voice/profile customization, and workspace-scoped history. The Android client must then map those documented responses rather than guess field names.
3. Extend the office API with a stable floor identifier, per-agent primary/upper/lower desk positions, walkable stair/elevator graph, floor-aware move/step/cancel state, and persisted floor-change events; expose it through the authenticated Gateway contract while retaining a single canonical agent identity.
4. Define a separate project resource if projects are distinct from tasks; otherwise officially specify that project screens are task views.
5. Provide a Gateway voice proxy for character voice IDs, previews and synthesis so provider keys remain server-side and Android retains only the user JWT triple.
6. Add a provenance/attestation response for the model that actually served a request before the UI can display a verified underlying model identity.
7. Supply the two reference images and approve the sprite/office match on-device across the supported screen sizes.

The current client intentionally displays unavailable/unverified states instead of fabricating these missing backend facts.
