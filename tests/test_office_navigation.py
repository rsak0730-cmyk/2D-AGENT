import pytest

from office_engine.navigation import Furniture, MovementEngine, validate_furniture_layout


def test_furniture_footprints_rotate_and_reject_overlap_or_blocked_entrance():
    desk = Furniture("desk-1", "desk", 2, 2, width=2, height=1, rotation=90)
    blocked = validate_furniture_layout(8, 8, [desk], entrances=[(0, 0)])
    assert blocked == {(2, 2), (2, 3)}
    with pytest.raises(ValueError, match="overlaps"):
        validate_furniture_layout(8, 8, [desk, Furniture("monitor-1", "monitor", 2, 3)])
    with pytest.raises(ValueError, match="entrance"):
        validate_furniture_layout(8, 8, [Furniture("rack", "server_rack", 0, 0)], entrances=[(0, 0)])
    with pytest.raises(ValueError, match="outside"):
        validate_furniture_layout(8, 8, [Furniture("large", "meeting_table", 7, 7, 2, 2)])


def test_path_uses_astar_around_furniture_and_steps_are_collision_free():
    engine = MovementEngine(7, 5, blocked={(3, 1), (3, 2), (3, 3)}, positions={"android": (1, 2)})
    result = engine.request_move("android", (5, 2))
    assert result["state"] == "walking"
    assert result["route"][0] == (1, 2) and result["route"][-1] == (5, 2)
    assert not ({(3, 1), (3, 2), (3, 3)} & set(result["route"]))
    assert len(result["route"]) > 5
    while "android" in engine.active:
        engine.advance("android")
    assert engine.positions["android"] == (5, 2)


def test_occupied_destinations_queue_fifo_and_replan_after_vacancy():
    engine = MovementEngine(6, 5, positions={"first": (1, 1), "second": (3, 1)})
    assert engine.request_move("first", (2, 1))["state"] == "walking"
    assert engine.request_move("second", (1, 1))["state"] == "queued"
    assert list(engine.pending)[0].agent_id == "second"
    engine.advance("first")
    assert "second" in engine.active
    while "second" in engine.active:
        engine.advance("second")
        assert len(set(engine.positions.values())) == len(engine.positions)
    assert engine.positions["second"] == (1, 1)


def test_movement_cancellation_and_unreachable_destinations():
    engine = MovementEngine(5, 3, blocked={(2, 0), (2, 1), (2, 2)}, positions={"worker": (0, 1), "blocker": (4, 1)})
    with pytest.raises(ValueError, match="No walkable route"):
        engine.request_move("worker", (4, 1))
    walking = engine.request_move("worker", (1, 1))
    assert walking["state"] == "walking"
    cancelled = engine.cancel("worker")
    assert cancelled == {"state": "cancelled", "position": (0, 1)}
    assert engine.positions["worker"] == (0, 1)
