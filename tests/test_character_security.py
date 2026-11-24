from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_elevenlabs_secret_configuration_is_server_only_and_example_is_empty():
    android_sources = list((ROOT / "app").rglob("*.kt")) + list((ROOT / "app").rglob("*.xml"))
    for path in android_sources:
        source = path.read_text(errors="ignore")
        assert "NOVA_ELEVENLABS_API_KEY" not in source
        assert "xi-api-key" not in source.lower()
    example = (ROOT / ".env.example").read_text()
    values = {line.split("=", 1)[0]: line.split("=", 1)[1] for line in example.splitlines() if "=" in line and not line.lstrip().startswith("#")}
    assert values["NOVA_ELEVENLABS_API_KEY"] == ""


def test_mobile_sends_voice_ids_and_text_only_not_upstream_credentials():
    client_source = (ROOT / "app/src/main/java/ai/nova/app/CharacterApi.kt").read_text()
    assert '.put("voice_id"' in client_source
    assert '.put("text"' in client_source
    assert "api_key" not in client_source.lower()
    server_source = (ROOT / "backend/nova_api/characters.py").read_text()
    assert 'headers={"xi-api-key": key}' in server_source
    assert "cfg.elevenlabs_api_key.strip()" in server_source
