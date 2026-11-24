# Office Simulation Skill

## Purpose
Render a 2D pixel-art office synchronized only to persisted backend activity.

## Required knowledge
Grid A*, occupancy, discrete animation states, event reconciliation, deterministic simulation.

## Supported tools
Pure Kotlin renderer or libGDX after measured evaluation; office_engine A* tests.

## Development rules
Never animate fake work as real; movement brain must not invoke LLM per frame; server events are truth.

## Implementation procedure
Read authoritative state; calculate navigation locally; bound movement; handle collision; display unavailable/offline explicitly.

## Security requirements
Do not expose task text or private content in office events. Validate all room and position bounds.

## Testing procedure
Test path optimality, blocked paths, collision, bed/desk assignment, and reconnect reconciliation.

## Troubleshooting
Inspect event timestamps, stale state, obstacle maps, and room assignments.

## References
https://github.com/libgdx/libgdx ; https://github.com/godotengine/godot
