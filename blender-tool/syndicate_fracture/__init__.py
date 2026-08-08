"""Headless Blender destruction tool for Syndicate.

Specified by ``docs/09_blender_destruction_tool.md``. The tool fractures a clean mesh via
seeded Voronoi decomposition, generates damage-state shape keys, assigns per-shard mass
from volume x material density, builds collision hulls, exports glTF, and verifies its own
output before reporting success (D09-S7).

Communication with the JVM side is by file and exit code only (D02-S4.5); the tool never
imports or is imported by the game.
"""

from .errors import (
    EXIT_BLENDER_ERROR,
    EXIT_DETERMINISM_VIOLATION,
    EXIT_EXPORT_FAILED,
    EXIT_FRACTURE_FAILED,
    EXIT_HULL_FAILED,
    EXIT_INPUT_GEOMETRY_INVALID,
    EXIT_INPUT_INVALID,
    EXIT_MASS_IMPLAUSIBLE,
    EXIT_MATERIAL_UNRESOLVED,
    EXIT_OK,
    EXIT_OUTPUT_WRITE_FAILED,
    EXIT_SHAPEKEY_FAILED,
    EXIT_USAGE,
    EXIT_VERIFICATION_FAILED,
)

__version__ = "0.1.0"

# Retained under their original names because the skeleton's tests assert them. The full
# D09-S4.3 table now lives in `errors`, where the failure paths that raise them are.
EXIT_MATERIAL_UNKNOWN = EXIT_MATERIAL_UNRESOLVED
EXIT_BLENDER_NOT_FOUND = EXIT_BLENDER_ERROR

__all__ = [
    "EXIT_BLENDER_ERROR",
    "EXIT_BLENDER_NOT_FOUND",
    "EXIT_DETERMINISM_VIOLATION",
    "EXIT_EXPORT_FAILED",
    "EXIT_FRACTURE_FAILED",
    "EXIT_HULL_FAILED",
    "EXIT_INPUT_GEOMETRY_INVALID",
    "EXIT_INPUT_INVALID",
    "EXIT_MASS_IMPLAUSIBLE",
    "EXIT_MATERIAL_UNKNOWN",
    "EXIT_MATERIAL_UNRESOLVED",
    "EXIT_OK",
    "EXIT_OUTPUT_WRITE_FAILED",
    "EXIT_SHAPEKEY_FAILED",
    "EXIT_USAGE",
    "EXIT_VERIFICATION_FAILED",
    "__version__",
]
