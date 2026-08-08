"""Headless Blender destruction tool for Syndicate.

Specified by ``docs/09_blender_destruction_tool.md``. The tool fractures a clean mesh via
seeded Voronoi decomposition, generates damage-state shape keys, assigns per-shard mass
from volume x material density, builds collision hulls, exports glTF, and verifies its own
output before reporting success (D09-S7).

Communication with the JVM side is by file and exit code only (D02-S4.5); the tool never
imports or is imported by the game.
"""

__version__ = "0.1.0"

# Exit codes of D09-S4.3. Distinct from the game's (D03-S4.4) and the harness's (D14-S4.2)
# because these are three different programs.
EXIT_OK = 0
EXIT_USAGE = 64
EXIT_INPUT_INVALID = 66
EXIT_MATERIAL_UNKNOWN = 67
EXIT_BLENDER_NOT_FOUND = 70
