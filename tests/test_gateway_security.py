from pathlib import Path

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


def test_android_sends_provider_key_only_to_gateway_without_persisting_it():
    source = (ROOT / "app/src/main/java/ai/nova/app/MainActivity.kt").read_text()
    gateway = (ROOT / "app/src/main/java/ai/nova/app/GatewayApi.kt").read_text()
    assert "saveProviderConfiguration" not in source
    assert 'client.request("/v1/gateways", "POST", payload)' in gateway
    assert '.put("api_key", oneShotKey)' in gateway
    assert 'providerKey = ""' in gateway
    assert 'secure.put("provider_api_key"' not in gateway
    assert 'settings.put("provider_api_key"' not in source
    assert "GatewayClient" in source and "gatewayHeadOrchestrate" in source
