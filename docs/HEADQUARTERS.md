# Headquarters status

The Android Headquarters screen now reads real persisted agent/task state and backend activity-event records and refreshes the snapshot periodically while open. Events originate from task lifecycle, agent execution, provider setup, memory, and resource configuration API operations. It shows an empty state when the API has no events; it does not animate invented work.

This repository did not previously contain pixel art, an office renderer, desk layout, rooms, character sprite/animation system, or occupancy editor. None has been fabricated in this phase. A future renderer can consume the same persisted events while keeping pathfinding and movement deterministic and entirely separate from LLM calls. WebSocket notifications are process-local, so polling the persisted event API is used for the Android status screen.
