import pytest
from backend.nova_api.safe_tools import execute_safe_tool


def test_arithmetic_tool_is_bounded_and_not_python_eval():
    assert execute_safe_tool("math.calculate", {"expression": "(7 + 5) * 3 / 2"}) == {"expression": "(7 + 5) * 3 / 2", "result": 18}
    for expression in ("__import__('os').system('id')", "2 ** 100", "1 / 0", "x + 1"):
        with pytest.raises((ValueError, ArithmeticError)):
            execute_safe_tool("math.calculate", {"expression": expression})


def test_text_analyzer_returns_only_deterministic_counts():
    assert execute_safe_tool("text.analyze", {"text": "One sentence. Two words!"}) == {"characters": 24, "words": 4, "sentences": 2}
    with pytest.raises(ValueError):
        execute_safe_tool("text.analyze", {"text": "x" * 12001})


def test_memory_search_is_scoped_to_owner_agent_and_shared_headquarters():
    from backend.nova_api.models import AgentMemory
    from conftest import TestingSession
    db = TestingSession()
    db.add_all([
        AgentMemory(owner_key_id="owner-a", agent_id="agent-a", scope="agent", content="private alpha note", keywords=[]),
        AgentMemory(owner_key_id="owner-a", agent_id="agent-b", scope="agent", content="private alpha note", keywords=[]),
        AgentMemory(owner_key_id="owner-a", agent_id=None, scope="headquarters", content="shared alpha note", keywords=[]),
        AgentMemory(owner_key_id="owner-b", agent_id="agent-x", scope="agent", content="private alpha note", keywords=[]),
    ])
    db.commit()
    try:
        result = execute_safe_tool("memory.search", {"query": "alpha"}, db=db, owner_id="owner-a", agent_id="agent-a")
        assert {row["content"] for row in result["matches"]} == {"private alpha note", "shared alpha note"}
        with pytest.raises(ValueError, match="agent context"):
            execute_safe_tool("memory.search", {"query": "alpha"}, db=db, owner_id="owner-a")
    finally:
        db.close()


def test_unknown_tool_fails_closed():
    with pytest.raises(ValueError, match="Unknown tool"):
        execute_safe_tool("shell.run", {"command": "echo unsafe"})
