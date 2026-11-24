from types import SimpleNamespace

from fastapi import HTTPException

from backend.nova_api import characters
from backend.nova_api.models import AgentMemory, ApiKey
from backend.nova_api.auth import digest
from backend.nova_api.characters import head_system_context
from conftest import TestingSession, auth_key, client


def test_character_roster_has_distinct_head_and_five_five_persistent_workers():
    owner = auth_key(["agents:read", "agents:write"])
    assert client.post("/api/v1/agents/seed", headers=owner).status_code == 200
    first = client.get("/api/v1/characters", headers=owner)
    assert first.status_code == 200, first.text
    profiles = first.json()
    assert len(profiles) == 11
    assert profiles[0]["character_key"] == "head"
    assert profiles[0]["name"] == "Astra Vale"
    assert profiles[0]["gender_identity"] == "female" and profiles[0]["age"] == 17
    assert profiles[0]["appearance"]["description"].startswith("Fictional 17-year-old girl")
    workers = profiles[1:]
    assert len(workers) == 10
    assert sum(row["gender_identity"] == "female" for row in workers) == 5
    assert sum(row["gender_identity"] == "male" for row in workers) == 5
    assert len({row["name"] for row in profiles}) == 11
    assert len({row["personality"] for row in workers}) == 10
    again = client.get("/api/v1/characters", headers=owner).json()
    assert [row["character_key"] for row in profiles] == [row["character_key"] for row in again]
    outsider = auth_key(["agents:read"])
    outsider_profiles = client.get("/api/v1/characters", headers=outsider).json()
    assert [row["character_key"] for row in outsider_profiles] == ["head"]
    assert outsider_profiles[0]["name"] == "Astra Vale"
    assert outsider_profiles[0]["voice_id"] is None


def test_voice_validation_gender_matching_persistence_and_isolation(monkeypatch):
    owner = auth_key(["agents:read", "agents:write"])
    client.post("/api/v1/agents/seed", headers=owner)
    assert len(client.get("/api/v1/characters", headers=owner).json()) == 11
    monkeypatch.setattr(characters, "get_settings", lambda: SimpleNamespace(elevenlabs_api_key="server-only-test-key", elevenlabs_base_url="https://api.elevenlabs.io/v1"))

    async def details(voice_id):
        if voice_id == "voice-does-not-exist":
            raise HTTPException(422, "ElevenLabs did not find this Voice ID")
        gender = "female" if voice_id == "female-voice-01" else "male"
        return {"name": f"Test {gender}", "labels": {"gender": gender}}

    monkeypatch.setattr(characters, "_voice_details", details)
    head = client.put("/api/v1/characters/head/voice", headers=owner, json={"voice_id": "female-voice-01", "settings": {"speed": 1.05, "stability": 0.6, "style": 0.3, "similarity_boost": 0.8}})
    assert head.status_code == 200, head.text
    worker = client.put("/api/v1/characters/android/voice", headers=owner, json={"voice_id": "female-voice-01"})
    assert worker.status_code == 200, worker.text
    mismatch = client.put("/api/v1/characters/web/voice", headers=owner, json={"voice_id": "female-voice-01"})
    assert mismatch.status_code == 422
    assert client.put("/api/v1/characters/head/voice", headers=owner, json={"voice_id": "voice-does-not-exist"}).status_code == 422
    assert client.put("/api/v1/characters/head/voice", headers=owner, json={"voice_id": "bad id"}).status_code == 422

    loaded = {row["character_key"]: row for row in client.get("/api/v1/characters", headers=owner).json()}
    assert loaded["head"]["voice_id"] == "female-voice-01"
    assert loaded["head"]["voice_settings"]["speed"] == 1.05
    assert loaded["android"]["voice_id"] == "female-voice-01"
    reset = client.delete("/api/v1/characters/head/voice", headers=owner)
    assert reset.status_code == 200 and reset.json()["voice_id"] is None
    loaded = {row["character_key"]: row for row in client.get("/api/v1/characters", headers=owner).json()}
    assert loaded["head"]["voice_id"] is None
    assert loaded["android"]["voice_id"] == "female-voice-01"


def test_voice_service_blocked_without_server_secret(monkeypatch):
    monkeypatch.setattr(characters, "get_settings", lambda: SimpleNamespace(elevenlabs_api_key=""))
    import asyncio
    try:
        asyncio.run(characters._voice_details("voice-abcde"))
    except HTTPException as error:
        assert error.status_code == 503
    else:
        raise AssertionError("Missing server-side voice credentials must fail closed")


def test_appearance_customization_persists_without_changing_model_personality_or_memory():
    owner = auth_key(["agents:read", "agents:write"])
    client.post("/api/v1/agents/seed", headers=owner)
    before = {row["character_key"]: row for row in client.get("/api/v1/characters", headers=owner).json()}
    head = before["head"]
    patch = {"appearance": {**head["appearance"], "hair_color": "#AB34CD", "accessory": "custom star pin"}}
    saved = client.patch("/api/v1/characters/head", headers=owner, json=patch)
    assert saved.status_code == 200, saved.text
    after = {row["character_key"]: row for row in client.get("/api/v1/characters", headers=owner).json()}
    assert after["head"]["appearance"]["hair_color"] == "#AB34CD"
    assert after["head"]["appearance"]["accessory"] == "custom star pin"
    assert after["head"]["personality"] == head["personality"]
    assert (after["head"]["provider"], after["head"]["model_id"]) == (head["provider"], head["model_id"])
    assert after["android"]["appearance"] == before["android"]["appearance"]


def test_head_chat_context_uses_saved_identity_and_only_owners_memory():
    owner = auth_key(["agents:read", "agents:write"])
    stranger = auth_key(["agents:read", "agents:write"])
    client.post("/api/v1/agents/seed", headers=owner)
    client.post("/api/v1/agents/seed", headers=stranger)
    saved = client.patch("/api/v1/characters/head", headers=owner, json={"name": "Astra North", "personality": "Careful, curious, and candid."})
    assert saved.status_code == 200
    db = TestingSession()
    def owner_id(headers):
        token = headers["Authorization"].split(" ", 1)[1]
        return db.query(ApiKey).filter_by(key_hash=digest(token)).one().id
    owner_key_id = owner_id(owner)
    stranger_key_id = owner_id(stranger)
    db.add(AgentMemory(owner_key_id=owner_key_id, scope="headquarters", content="Owner-only preference: metric units", importance=0.9))
    db.add(AgentMemory(owner_key_id=stranger_key_id, scope="headquarters", content="Do not leak this private note", importance=1.0))
    db.commit()
    context = head_system_context(db, owner_key_id)
    db.close()
    assert "Astra North" in context and "Careful, curious, and candid" in context
    assert "metric units" in context
    assert "Do not leak this private note" not in context
