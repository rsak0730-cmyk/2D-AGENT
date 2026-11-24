# NOVA repository instructions

- Load the relevant `skills/<topic>/SKILL.md` before changing a feature.
- Keep Android, backend, office-engine, and connector-engine boundaries explicit.
- Never commit API keys, tokens, private signing files, `.env`, databases, APKs, caches, or personal data.
- Do not portray queued task records as executing autonomous work. Implement a worker and prove it with tests before changing that claim.
- Preserve explicit loading, success, empty, error, offline, and permission states.
- Add a regression test for every fixed defect. Run focused tests and the relevant full suite before claiming success.
- Prefer pinned dependencies and official docs. Do not copy whole projects from the resource list.
- Use approval gates for external messages, deletion, publishing, purchasing, and other high impact operations.
- Backend: `cd backend && python -m pytest ../tests -q`; Android: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Current execution environment has Python but no local JDK/Android SDK. Report Android builds as unverified unless CI or a toolchain-enabled runner produces the APK.
