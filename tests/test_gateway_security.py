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


def test_android_uses_zero_login_device_installation_and_never_collects_provider_keys():
    source = (ROOT / "app/src/main/java/ai/nova/app/MainActivity.kt").read_text()
    gateway = (ROOT / "app/src/main/java/ai/nova/app/GatewayApi.kt").read_text()
    projects = (ROOT / "app/src/main/java/ai/nova/app/GatewayProjects.kt").read_text()
    assert '"/v1/installations/register"' in gateway
    assert '"/v1/installations/refresh"' in gateway
    assert '"X-Workspace-Id"' in gateway and '"X-Installation-Token"' in gateway
    assert '"/v1/auth/login"' not in gateway and '"/v1/auth/register"' not in gateway
    assert '"/v1/gateways"' not in gateway
    assert '.put("api_key"' not in gateway
    assert "OutlinedTextField(providerKey" not in gateway
    assert "Sign in" not in source and "Sign in" not in projects
    assert "gatewayHeadOrchestrate" in source and '"/v1/chat/completions"' in gateway
    assert 'secure.put(KEY_INSTALLATION_TOKEN' in gateway
