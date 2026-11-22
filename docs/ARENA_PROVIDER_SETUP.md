# Arena provider setup

Verified from Arena official API docs on 2026-11-22: OpenAI-compatible base URL `https://api.arena.ai/v1`; bearer authentication; Chat Completions `POST /v1/chat/completions`; model listing `GET /v1/models`; docs list router model `coding-router-preview`. See the comprehensive [API Setup Guide](API_SETUP_GUIDE.md) and official [API](https://platform.arena.ai/docs/api), [Quickstart](https://platform.arena.ai/docs/quickstart), and [error codes](https://platform.arena.ai/docs/errors).

Create a key through the official Arena dashboard, then set `NOVA_PROVIDER_API_KEY` as a backend-only secret. Configure exact model ID with `NOVA_MODEL`. The adapter returns clear 402/429/auth/network failures. Arena API usage consumes provider account quota/balance. It does not supply automatic fallback; NOVA does not claim unlimited use.
