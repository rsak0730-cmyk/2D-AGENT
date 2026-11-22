"""Opt-in live smoke test; skipped unless explicit non-production test credentials are supplied."""
import os
import pytest
from backend.nova_api.providers import complete_detailed

@pytest.mark.skipif(not (os.getenv("NOVA_TEST_PROVIDER") and os.getenv("NOVA_TEST_API_KEY") and os.getenv("NOVA_TEST_MODEL")), reason="Set NOVA_TEST_PROVIDER, NOVA_TEST_API_KEY, and NOVA_TEST_MODEL to run a real provider smoke test")
def test_real_provider_chat_completion():
    import asyncio
    provider = os.environ["NOVA_TEST_PROVIDER"]
    if provider not in {"arena", "groq", "gemini", "openrouter", "cloudflare"}:
        pytest.fail("NOVA_TEST_PROVIDER must name a supported adapter")
    account_id = os.getenv("NOVA_TEST_ACCOUNT_ID")
    endpoint = f"https://api.cloudflare.com/client/v4/accounts/{account_id}" if provider == "cloudflare" and account_id else None
    if provider == "cloudflare" and not account_id:
        pytest.fail("NOVA_TEST_ACCOUNT_ID is required for Cloudflare")
    result, model, _, _ = asyncio.run(complete_detailed(
        "Reply with the short phrase NOVA integration check.",
        model=os.environ["NOVA_TEST_MODEL"], api_key=os.environ["NOVA_TEST_API_KEY"],
        provider=provider, base_url=endpoint,
    ))
    assert result.strip()
    assert model == os.environ["NOVA_TEST_MODEL"] or model
