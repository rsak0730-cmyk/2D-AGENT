"""Deterministic tile navigation, furniture validation, and collision-safe move queues.

This engine is deliberately separate from rendering. A caller advances one tile only
when the corresponding backend movement step is persisted; it never invents work or
moves an agent on a timer.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from typing import Iterable

from .pathfinding import Cell, find_path

SUPPORTED_FURNITURE = {"desk", "chair", "computer", "monitor", "whiteboard", "meeting_table", "server_rack", "coffee_machine", "project_board", "memory_core", "personal_workstation"}


@dataclass(frozen=True)
class Furniture:
    object_id: str
    kind: str
    x: int
    y: int
    width: int = 1
    height: int = 1
    rotation: int = 0

    @property
    def cells(self) -> set[Cell]:
        width, height = (self.height, self.width) if self.rotation in {90, 270} else (self.width, self.height)
        return {(self.x + dx, self.y + dy) for dx in range(width) for dy in range(height)}


def validate_furniture_layout(width: int, height: int, furniture: Iterable[Furniture], entrances: Iterable[Cell] = ()) -> set[Cell]:
    """Validate a room grid and return furniture-blocked cells.

    Furniture placement is cell-aligned, non-overlapping and cannot block a declared
    room entrance. Room boundaries are enforced by the supplied grid dimensions.
    """
    if not (2 <= width <= 128 and 2 <= height <= 128):
        raise ValueError("Office dimensions must be between 2 and 128 tiles")
    blocked: set[Cell] = set()
    entrance_cells = set(entrances)
    ids: set[str] = set()
    for item in furniture:
        if not item.object_id or len(item.object_id) > 80 or item.object_id in ids:
            raise ValueError("Furniture IDs must be unique, non-empty, and at most 80 characters")
        ids.add(item.object_id)
        if item.kind not in SUPPORTED_FURNITURE:
            raise ValueError(f"Unsupported furniture type: {item.kind}")
        if item.rotation not in {0, 90, 180, 270}:
            raise ValueError("Furniture rotation must be 0, 90, 180, or 270 degrees")
        if not (1 <= item.width <= 16 and 1 <= item.height <= 16):
            raise ValueError("Furniture footprint must be between 1 and 16 tiles")
        cells = item.cells
        if any(x < 0 or y < 0 or x >= width or y >= height for x, y in cells):
            raise ValueError(f"Furniture {item.object_id} extends outside the office")
        if cells & blocked:
            raise ValueError(f"Furniture {item.object_id} overlaps another object")
        if cells & entrance_cells:
            raise ValueError(f"Furniture {item.object_id} blocks a room entrance")
        blocked.update(cells)
    if any(x < 0 or y < 0 or x >= width or y >= height for x, y in entrance_cells):
        raise ValueError("Room entrances must be inside the office grid")
    return blocked


@dataclass
class MoveOrder:
    agent_id: str
    destination: Cell
    route: deque[Cell] = field(default_factory=deque)
    state: str = "queued"


class MovementEngine:
    """A* movement scheduler with tile occupancy, next-step reservations and FIFO queuing."""

    def __init__(self, width: int, height: int, blocked: Iterable[Cell] = (), positions: dict[str, Cell] | None = None):
        if not (2 <= width <= 128 and 2 <= height <= 128):
            raise ValueError("Office dimensions must be between 2 and 128 tiles")
        self.width, self.height = width, height
        self.blocked = set(blocked)
        self.positions = dict(positions or {})
        self.active: dict[str, MoveOrder] = {}
        self.pending: deque[MoveOrder] = deque()
        self._validate_world()

    def _inside(self, cell: Cell) -> bool:
        return 0 <= cell[0] < self.width and 0 <= cell[1] < self.height

    def _validate_world(self) -> None:
        if any(not self._inside(cell) for cell in self.blocked):
            raise ValueError("Blocked tiles must be within the office grid")
        if len(set(self.positions.values())) != len(self.positions):
            raise ValueError("Two employees cannot occupy the same tile")
        if any(not self._inside(cell) or cell in self.blocked for cell in self.positions.values()):
            raise ValueError("Employee positions must be walkable tiles inside the office")

    def request_move(self, agent_id: str, destination: Cell) -> dict:
        if agent_id not in self.positions:
            raise ValueError("Unknown employee")
        if not self._inside(destination) or destination in self.blocked:
            raise ValueError("Destination is outside the office or blocked by furniture")
        if agent_id in self.active or any(order.agent_id == agent_id for order in self.pending):
            raise ValueError("Employee already has an active or queued movement; cancel it first")
        if self.positions[agent_id] == destination:
            return {"state": "arrived", "route": [destination]}
        order = MoveOrder(agent_id, destination)
        if self._start(order):
            self.active[agent_id] = order
            return {"state": "walking", "route": [self.positions[agent_id], *order.route]}
        self.pending.append(order)
        return {"state": "queued", "route": []}

    def _reserved_next(self, exclude_agent: str | None = None) -> set[Cell]:
        return {order.route[0] for agent_id, order in self.active.items() if agent_id != exclude_agent and order.route}

    def _start(self, order: MoveOrder) -> bool:
        base_path = find_path(self.positions[order.agent_id], order.destination, self.blocked, (self.width, self.height))
        if not base_path:
            raise ValueError("No walkable route exists to the destination")
        occupied = {cell for agent_id, cell in self.positions.items() if agent_id != order.agent_id}
        reserved = self._reserved_next(order.agent_id)
        if order.destination in occupied or order.destination in reserved:
            return False
        path = find_path(self.positions[order.agent_id], order.destination, self.blocked | occupied | reserved, (self.width, self.height))
        if not path:
            return False
        order.route = deque(path[1:])
        order.state = "walking"
        return True

    def _drain(self) -> None:
        # FIFO fairness: a blocked head waits; later requests do not jump its queue.
        while self.pending:
            order = self.pending[0]
            if not self._start(order):
                break
            self.pending.popleft()
            self.active[order.agent_id] = order

    def advance(self, agent_id: str) -> dict:
        order = self.active.get(agent_id)
        if order is None:
            raise ValueError("Employee has no active movement")
        current = self.positions[agent_id]
        if not order.route:
            order.state = "arrived"
            self.active.pop(agent_id, None)
            self._drain()
            return {"state": "arrived", "from": current, "to": current}
        next_cell = order.route[0]
        occupied = {cell for other, cell in self.positions.items() if other != agent_id}
        reserved = self._reserved_next(agent_id)
        if next_cell in self.blocked or next_cell in occupied or next_cell in reserved:
            return {"state": "waiting", "from": current, "to": current}
        self.positions[agent_id] = next_cell
        order.route.popleft()
        arrived = not order.route
        if arrived:
            order.state = "arrived"
            self.active.pop(agent_id, None)
        self._drain()
        return {"state": "arrived" if arrived else "walking", "from": current, "to": next_cell}

    def cancel(self, agent_id: str) -> dict:
        order = self.active.pop(agent_id, None)
        if order is not None:
            self._drain()
            return {"state": "cancelled", "position": self.positions[agent_id]}
        before = len(self.pending)
        self.pending = deque(item for item in self.pending if item.agent_id != agent_id)
        if len(self.pending) != before:
            self._drain()
            return {"state": "cancelled", "position": self.positions[agent_id]}
        return {"state": "idle", "position": self.positions[agent_id]}
