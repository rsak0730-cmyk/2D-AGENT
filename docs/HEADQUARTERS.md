# Headquarters status

The `office_engine` module contains deterministic four-direction A* navigation with blocked cells and optional rectangular bounds, covered by tests. This is a utility only: no pixels, character animations, ten-desk initial layout, desks/rooms editor, occupancy/collision controller, sleeping/waking, live backend binding or persistence exists yet. Android shows a clear in-development message rather than fake movement.

The rendering phase must compare native Canvas/Compose and libGDX for frame budget, touch editing, accessibility and Android APK size. Office state should visualize verified persisted work events and be a presentation layer only; navigation and animations must never trigger model calls per frame. Office design uses pixel-art, not glass styling.
