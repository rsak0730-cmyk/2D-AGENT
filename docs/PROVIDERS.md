# Provider connections, free access, and quotas

NOVA stores the credentials supplied by the owner of each scoped NOVA API token. Keys are encrypted using `NOVA_CREDENTIAL_ENCRYPTION_KEY` and never embedded in Android. Set that key before saving provider connections. The mobile app sends keys only to the configured HTTPS NOVA backend. Deleting a connection removes its encrypted key from the DB; rotate/revoke it at the provider as well.

NOVA discovers model IDs from each provider account. Use the returned exact ID; model names and free availability can change. There is no universal free tier, no fixed 11-model requirement, and no unlimited quota promise.

| Provider | Verified API endpoint / setup | Free-tier and quota note |
| --- | --- | --- |
| Arena | Configured OpenAI-compatible base URL; API key required. | No free quota claim made here. |
| Groq | `https://api.groq.com/openai/v1`; key required. Current rate limits are account/model-specific; check the provider console. | NOVA does not claim a universal free quota. |
| Gemini API | `https://generativelanguage.googleapis.com/v1beta/openai`; API key required. | Google documents a free usage tier for eligible projects, but RPM/TPM/RPD vary by model/account and are not guaranteed. View active limits in AI Studio. |
| OpenRouter | `https://openrouter.ai/api/v1`; API key required; model discovery is account-dependent. | Free-model eligibility and limits depend on the current catalog/account. NOVA does not assume a free model. |
| Cloudflare Workers AI | API token and Account ID; endpoint is account-specific, e.g. `https://api.cloudflare.com/client/v4/accounts/{account_id}`. Native model execution and catalog search are used. | Cloudflare documents an allocation of 10,000 Neurons/day on its free plan with paid overage. Neurons vary by model/work; this is not a guaranteed fixed request or token quota. Verify current billing/plan terms in your account. |

NOVA can enforce the per-connection daily request ceiling and owner-level daily request budget configured in the API. Provider-enforced rate limits, billing limits, and account quota responses remain authoritative. Fallback is finite and only tries configured compatible connections; 402 payment-required errors stop rather than spend elsewhere. Token counts are displayed only when a provider returns usage metadata; dollar cost is not estimated without reliable pricing metadata.

## Provider documentation

- [Arena API documentation](https://platform.arena.ai/docs/api)
- [Groq OpenAI compatibility](https://console.groq.com/docs/openai) and [current model catalog](https://console.groq.com/docs/models)
- [Gemini API OpenAI compatibility](https://aistudio.google.com/docs/openai) and [rate limits](https://aistudio.google.com/docs/rate-limits)
- [OpenRouter quickstart](https://openrouter.ai/docs/quickstart) and [model list API](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)
- [Cloudflare Workers AI REST API setup](https://developers.cloudflare.com/workers-ai/get-started/rest-api/), [OpenAI compatibility](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/), [pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), and [model search API](https://developers.cloudflare.com/api/resources/ai/subresources/models/methods/list/)

Provider setup smoke checks in tests use HTTP mocks only. No real provider credentials are available in this environment; no live integration or paid/free eligibility test has been performed.
