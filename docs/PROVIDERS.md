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
| Claude Code Gateway (self-hosted) | Gateway bearer token plus `https://<host>/v1`; the hostname must be explicitly listed in backend `NOVA_GATEWAY_ALLOWED_HOSTS` (comma-separated exact hostnames). NOVA uses authenticated OpenAI-compatible `/v1/models` and `/v1/chat/completions`. | The gateway is a protocol router, not an inference provider or free-quota source. Deploy and secure it yourself, configure at least one upstream provider, and account for that provider's charges, limits, and terms. NOVA's model-list test does not prove inference will succeed. Use TLS, strong non-example tokens, protected admin/config endpoints, and a durable deployment appropriate to your gateway configuration. |

NOVA can enforce the per-connection daily request ceiling and owner-level daily request budget configured in the API. Provider-enforced rate limits, billing limits, and account quota responses remain authoritative. Fallback is finite and only tries configured compatible connections; 402 payment-required errors stop rather than spend elsewhere. Token counts are displayed only when a provider returns usage metadata; dollar cost is not estimated without reliable pricing metadata.

## In-app setup and gateway deployment

In Android, open **Settings → Configure multi-provider router**, enter a provider identity and credential, then use **Save and test**. For Cloudflare also enter the Account ID. For Claude Code Gateway, the backend operator must first set `NOVA_GATEWAY_ALLOWED_HOSTS` to the exact DNS hostname(s), separated by commas; then enter the gateway HTTPS base URL ending in `/v1` and its client bearer token. The backend rejects non-HTTPS, unlisted hosts, and paths other than `/v1`. After model discovery, use the exact model ID when editing the Head Manager or workers. Saved connections can be retested or disconnected in the same setup center. Re-adding a provider replaces its existing credential/connection; rotate the upstream credential too if it was exposed.

The setup test performs model listing only. It does not make a completion request, promise that a model is callable, verify billing, or imply free inference. A failed test is persisted as an error and should not be treated as connected. Never use the gateway repository's sample development tokens in a deployment; follow its current security guidance, use strong unique tokens, protect the admin/config surface, terminate TLS securely, and configure an upstream model provider.

## Provider documentation

- [Arena API documentation](https://platform.arena.ai/docs/api)
- [Groq OpenAI compatibility](https://console.groq.com/docs/openai) and [current model catalog](https://console.groq.com/docs/models)
- [Gemini API OpenAI compatibility](https://aistudio.google.com/docs/openai) and [rate limits](https://aistudio.google.com/docs/rate-limits)
- [OpenRouter quickstart](https://openrouter.ai/docs/quickstart) and [model list API](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)
- [Cloudflare Workers AI REST API setup](https://developers.cloudflare.com/workers-ai/get-started/rest-api/), [OpenAI compatibility](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/), [pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/), and [model search API](https://developers.cloudflare.com/api/resources/ai/subresources/models/methods/list/)
- [Claude Code Gateway source and deployment notes](https://github.com/Mukller/claude-code-gateway) (third-party, self-hosted project; review its current code/config before deployment)

Provider setup smoke checks in tests use HTTP mocks only. No real provider credentials are available in this environment; no live integration or paid/free eligibility test has been performed.
