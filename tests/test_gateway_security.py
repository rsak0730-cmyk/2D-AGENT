from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def test_ci_workflows_do_not_inject_secrets_into_android_build_or_print_them():
    workflows = list((ROOT / ".github" / "workflows").glob("*.yml"))
    assert workflows
    for workflow in workflows:
        content = workflow.read_text()
        assert "${{ secrets." not in content
        assert "set -x" not in content
        if "android" in workflow.name:
            assert "permissions:" in content and "contents: read" in content


def test_example_environment_contains_no_live_provider_or_gateway_credentials():
    env_example = (ROOT / ".env.example").read_text()
    for line in env_example.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        if any(marker in name for marker in ("API_KEY", "ADMIN_TOKEN", "ENCRYPTION_KEY")):
            assert value == "" or value.startswith("replace-with-") or value == "gateway.example.org"


def test_android_only_sends_gateway_token_and_never_configures_upstream_key_field():
    source = (ROOT / "app/src/main/java/ai/nova/app/MainActivity.kt").read_text()
    assert "Only restricted gateway credentials may be entered in the app" in source
    assert "private fun saveProviderConfiguration(base: String, token: String, model: String)" in source
    # The sole api_key request-body field belongs to the restricted gateway save helper.
    assert source.count('.put("api_key", apiKey.trim())') == 1
    assert 'provider !in listOf("claude_gateway", "openai_compatible", "anthropic_compatible")' in source
    assert not re.search(r'providerKey\s+by\s+remember', source)
