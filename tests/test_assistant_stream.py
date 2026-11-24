import json

from backend.nova_api import main
from backend.nova_api.models import ConversationMessage
from conftest import TestingSession, auth_key, client


class FakeStream:
    model = "served-model"
    input_tokens = 3
    output_tokens = 2
    finish_reason = "stop"
    saw_text = True
    async def events(self):
        yield {"type": "text", "text": "Hello "}
        yield {"type": "text", "text": "there."}
    async def close(self):
        pass


def test_mobile_assistant_sse_stream_persists_complete_conversation(monkeypatch):
    async def fake_open(db, owner, model, messages, temperature, max_tokens):
        assert messages[-1] == {"role": "user", "content": "Say hello"}
        return FakeStream(), "openai_compatible", False

    monkeypatch.setattr(main, "open_owner_stream", fake_open)
    monkeypatch.setattr(main, "SessionLocal", TestingSession)
    headers = auth_key(["assistant:read", "assistant:write"])
    response = client.post("/api/v1/assistant/chat/stream", headers=headers, json={"message": "Say hello", "model": "test-model"})
    assert response.status_code == 200, response.text
    assert response.headers["content-type"].startswith("text/event-stream")
    assert "event: meta" in response.text
    assert "event: delta" in response.text
    assert "event: done" in response.text
    assert "Hello " in response.text and "there." in response.text
    events = [line[6:] for line in response.text.splitlines() if line.startswith("data: ")]
    parsed = [json.loads(event) for event in events]
    conversation_id = next(event["conversation_id"] for event in parsed if "conversation_id" in event)
    db = TestingSession()
    messages = db.query(ConversationMessage).filter_by(conversation_id=conversation_id).order_by(ConversationMessage.created_at.asc()).all()
    assert [(item.role, item.content) for item in messages] == [("user", "Say hello"), ("assistant", "Hello there.")]
    db.close()


def test_mobile_assistant_stream_is_owner_scoped(monkeypatch):
    async def fake_open(*args, **kwargs):
        return FakeStream(), "openai_compatible", False

    monkeypatch.setattr(main, "open_owner_stream", fake_open)
    monkeypatch.setattr(main, "SessionLocal", TestingSession)
    owner = auth_key(["assistant:read", "assistant:write"])
    created = client.post("/api/v1/assistant/chat/stream", headers=owner, json={"message": "hello", "model": "test-model"})
    assert created.status_code == 200
    outsider = auth_key(["assistant:write"])
    assert client.post("/api/v1/assistant/chat/stream", headers=outsider, json={"message": "steal", "conversation_id": "not-owned", "model": "test-model"}).status_code == 404
