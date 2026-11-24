from typing import Literal
from pydantic import BaseModel, Field, model_validator


class OfficeCell(BaseModel):
    x: int = Field(ge=0, le=127)
    y: int = Field(ge=0, le=127)


class OfficeRoom(BaseModel):
    id: str = Field(min_length=1, max_length=80)
    name: str = Field(min_length=1, max_length=120)
    x: int = Field(ge=0, le=127)
    y: int = Field(ge=0, le=127)
    width: int = Field(ge=2, le=128)
    height: int = Field(ge=2, le=128)
    entrance: OfficeCell


class OfficeFurniture(BaseModel):
    id: str = Field(min_length=1, max_length=80)
    kind: Literal["desk", "chair", "computer", "monitor", "whiteboard", "meeting_table", "server_rack", "coffee_machine", "project_board", "memory_core", "personal_workstation"]
    x: int = Field(ge=0, le=127)
    y: int = Field(ge=0, le=127)
    width: int = Field(default=1, ge=1, le=16)
    height: int = Field(default=1, ge=1, le=16)
    rotation: Literal[0, 90, 180, 270] = 0


class OfficeLayoutUpdate(BaseModel):
    width: int = Field(ge=2, le=128)
    height: int = Field(ge=2, le=128)
    rooms: list[OfficeRoom] = Field(min_length=1, max_length=40)
    furniture: list[OfficeFurniture] = Field(default_factory=list, max_length=500)

    @model_validator(mode="after")
    def validate_room_grid(self):
        room_ids = [room.id for room in self.rooms]
        if len(room_ids) != len(set(room_ids)):
            raise ValueError("Room IDs must be unique")
        reserved_room_cells: set[tuple[int, int]] = set()
        for room in self.rooms:
            if room.x + room.width > self.width or room.y + room.height > self.height:
                raise ValueError(f"Room {room.id} extends outside the office grid")
            entrance = (room.entrance.x, room.entrance.y)
            if not (room.x <= entrance[0] < room.x + room.width and room.y <= entrance[1] < room.y + room.height):
                raise ValueError(f"Room {room.id} entrance must be inside its room")
            cells = {(x, y) for x in range(room.x, room.x + room.width) for y in range(room.y, room.y + room.height)}
            if cells & reserved_room_cells:
                raise ValueError(f"Room {room.id} overlaps another room")
            reserved_room_cells.update(cells)
        return self


class OfficeMoveRequest(BaseModel):
    x: int = Field(ge=0, le=127)
    y: int = Field(ge=0, le=127)
