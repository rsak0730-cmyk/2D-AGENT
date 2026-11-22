"""Deterministic four-way A* pathfinder used by the office simulation."""
from heapq import heappop, heappush
from itertools import count
from typing import Iterable
Cell = tuple[int, int]

def find_path(start: Cell, goal: Cell, blocked: Iterable[Cell] = (), bounds: tuple[int, int] | None = None) -> list[Cell]:
    """Return a shortest path including start and goal; return [] if unreachable.

    Bounds are width/height for coordinates in [0,width) x [0,height). Ties are
    deterministic for reproducible tests and synchronized client rendering.
    """
    obstacles = set(blocked)
    if start in obstacles or goal in obstacles: return []
    if bounds and any(x < 0 or y < 0 or x >= bounds[0] or y >= bounds[1] for x, y in (start, goal)): return []
    serial = count()
    frontier = [(0, next(serial), start)]
    came_from: dict[Cell, Cell | None] = {start: None}
    cost = {start: 0}
    while frontier:
        _, _, current = heappop(frontier)
        if current == goal:
            path = []
            while current is not None:
                path.append(current); current = came_from[current]
            return list(reversed(path))
        for nxt in ((current[0]+1,current[1]), (current[0]-1,current[1]), (current[0],current[1]+1), (current[0],current[1]-1)):
            if nxt in obstacles or (bounds and (nxt[0] < 0 or nxt[1] < 0 or nxt[0] >= bounds[0] or nxt[1] >= bounds[1])): continue
            new_cost = cost[current] + 1
            if new_cost < cost.get(nxt, 1 << 60):
                cost[nxt] = new_cost; came_from[nxt] = current
                heuristic = abs(goal[0]-nxt[0]) + abs(goal[1]-nxt[1])
                heappush(frontier, (new_cost + heuristic, next(serial), nxt))
    return []
