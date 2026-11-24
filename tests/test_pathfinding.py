from office_engine.pathfinding import find_path

def test_shortest_path_avoids_wall():
    path = find_path((0, 0), (3, 0), {(1, 0), (2, 0)}, (5, 3))
    assert path[0] == (0, 0) and path[-1] == (3, 0)
    assert len(path) == 6
    assert not ({(1, 0), (2, 0)} & set(path))

def test_invalid_and_unreachable():
    assert find_path((0, 0), (1, 1), {(1, 1)}) == []
    assert find_path((-1, 0), (1, 1), bounds=(4, 4)) == []
    assert find_path((0, 0), (2, 0), {(1, 0)}, bounds=(3, 1)) == []

def test_start_is_goal():
    assert find_path((2, 2), (2, 2)) == [(2, 2)]
