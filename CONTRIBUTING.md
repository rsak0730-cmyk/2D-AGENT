# Contributing

Read [AGENTS.md](AGENTS.md) and the feature skill under `skills/` before changes. Keep features modular, update docs, add tests, and report actual command outcomes. Do not commit secrets, personal data, provider keys, local DB files, or build outputs. Use mock provider responses in tests and never call paid external APIs from CI. Do not expose host shell/terminal execution from the API. A successful Python suite does not imply Android or production readiness; state which targets were built and tested.
