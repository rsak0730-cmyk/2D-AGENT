# Headquarters status

The Android Headquarters screen reads the actual persisted agent roster and backend activity-event records and refreshes the snapshot periodically while open. It now includes a pixel-art 2D office scene with fixed desks and one seated character per loaded agent record. Character count and working/error monitor colors are derived from backend agent status; an empty roster shows empty desks and a setup prompt. Room decoration is static illustration, not claimed work or simulation state.

This first renderer is a static layout: it has no character movement, persistent per-agent position, occupancy editor, or animation loop. It does not synthesize activity. Events originate from real task lifecycle, agent execution, provider setup, memory, and resource configuration API operations. WebSocket notifications remain process-local, so Android polls the persisted event API. Future movement must remain deterministic and separate from LLM calls.
