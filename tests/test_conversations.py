from types import SimpleNamespace
from backend.nova_api import routing
from conftest import auth_key, client

def test_conversation_history_search_and_key_isolation(monkeypatch):
    observed = []
    async def fake_completion(message, model=None, history=None, **kwargs):
        observed.append(history or [])
        return f"reply:{message}", "test-model", 3, 2
    monkeypatch.setattr(routing, "complete_detailed", fake_completion)
    monkeypatch.setattr(routing, "get_settings", lambda: SimpleNamespace(provider_api_key="synthetic", provider="arena", model="test-model", provider_base_url="https://arena.test/v1"))
    owner = auth_key(["assistant:read", "assistant:write"])
    first = client.post("/api/v1/assistant/chat", headers=owner, json={"message":"Remember the blue bicycle"})
    assert first.status_code == 200, first.text
    conversation_id = first.json()["conversation_id"]
    second = client.post("/api/v1/assistant/chat", headers=owner, json={"message":"What color was it?", "conversation_id":conversation_id})
    assert second.status_code == 200
    assert observed[0] == []
    assert observed[1] == [
        {"role":"user", "content":"Remember the blue bicycle"},
        {"role":"assistant", "content":"reply:Remember the blue bicycle"},
    ]
    detail = client.get(f"/api/v1/assistant/conversations/{conversation_id}", headers=owner)
    assert detail.status_code == 200 and len(detail.json()["messages"]) == 4
    assert len(client.get("/api/v1/assistant/conversations/search?q=bicycle", headers=owner).json()) == 1
    stranger = auth_key(["assistant:read", "assistant:write"])
    assert client.get(f"/api/v1/assistant/conversations/{conversation_id}", headers=stranger).status_code == 404
    assert client.get("/api/v1/assistant/conversations", headers=stranger).json() == []
    assert client.delete(f"/api/v1/assistant/conversations/{conversation_id}", headers=owner).json()["deleted"] is True
