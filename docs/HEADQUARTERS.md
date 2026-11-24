# Android Headquarters scene

The Android HQ now presents a native Compose/Canvas two-floor pixel-art cutaway with a floor selector, pinch zoom/pan, recenter, reduced-motion toggle, panoramic night skyline, glass framing, reflective tile grid, ten rendered worker desks per floor, staircase, plants, bookcase/lounge elements, lower-floor refreshment corner, and an upper-floor circular command dais with a floating holographic globe. The Head sprite appears at the upper command station and worker sprites are overlaid at workstations on the selected floor when backend agent records are available; the same agent identity is rendered once at a time, never simultaneously duplicated across both floor views. Worker poses are driven by returned task, status, communication, meeting and error state; the scene does not claim task progress without backend records.

The art is native vector/pixel-block drawing in `app/src/main/java/ai/nova/app/TwoFloorOffice.kt`. It is a scalable prototype scene, not a final imported sprite-sheet render. The two supplied visual-reference images were not present in the checkout, and there are no character or office image assets under `app/src/main/res`; therefore no claim is made that these drawings exactly reproduce the referenced people or architecture. Add those source images/assets before signing off on character likeness or final pixel-art fidelity.

## Live-data boundary

The app's established office API is the single-grid `GET /api/v1/office/state` and movement endpoints `POST /api/v1/office/agents/{agent_id}/move`, `/step`, and `/cancel-move`. It has no floor identifier, paired workstation mapping, vertical route, stair/elevator event, or inter-floor position. The current two-floor view therefore labels its floor/workstation location as a **layout preview** and does not assert that a backend character walked upstairs/downstairs. Gateway session data is not silently converted into a legacy backend API key.

The NOVA-GATEWAY Android contract used for AI/workspace requests has Head, workers, models, gateways, tasks, messages, memory, and activity APIs, but no office scene/movement API has been documented or verified. To make floor occupancy and movement authoritative, the backend contract needs an owner/workspace-scoped floor field, one canonical character-to-agent mapping, a pair of workstation coordinates per worker, stair-graph movement, and authoritative floor-change/activity events. Until that exists, the scene's architecture is visual and the live activity indicators remain limited to data actually returned by the connected backend.

## Character catalog

`CharacterRosterScreen` contains the specified canonical 11 names and roles as a character identity sheet. Model identity is always rendered as unverified in the current Android app because gateway model provenance/attestation is not available to this client. Backend-created NOVA character profiles now use the names Nova, Aiden, Mira, Luna, Zen, Riya, Kian, Sia, Aron, Evelyn and Rex, in that order; untouched legacy default names are upgraded while user-customized names are preserved. Worker voice gender defaults are five female and five male, and Nova is separate. These profile records are not evidence of exact reference-image artwork.

## Verification

- Backend tests cover the canonical character order, voice-gender balance, isolation and safe upgrade of untouched legacy defaults.
- GitHub Actions Android Debug APK run `45430705564` passed tests, lint, debug APK assembly, and APK verification on commit `de449349d38fcaea528db4f1fe15c71a467cea87`; it uploaded the `nova-debug-apk` artifact. The build log and artifact are available from https://github.com/rsak0730-cmyk/2D-AGENT/actions/runs/45430705564.
- Backend validation also passed on that commit (run `45430730840`). A successful compile does not verify the external gateway's live response schema, reference-image fidelity, floor movement, or model provenance.
