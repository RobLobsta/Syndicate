"""The destruction policy every tool in the suite agrees on.

This package holds two things and deliberately nothing else: **which transform each destruction
class is allowed to receive** (:mod:`~syndicate_policy.classes`) and **what a tool's exit code
means** (:mod:`~syndicate_policy.exit_codes`). Both are contracts between tools rather than the
business of any one of them, and both were previously duplicated or absent:

- The class-to-treatment table of D15-S5.7 lived in ``syndicate_prepare`` alone, so the transform
  tools it drives had no way to know what they were authoring for and no way to refuse. A steel
  door handed to ``syndicate_fracture`` came back with 24 shards and nothing anywhere said no
  (DISC-068).
- The exit-code table of D09-S4.3 was copied into three packages that then disagreed about what 65
  and 66 meant, while D09-R5 invites an agent to branch on the code by integer division.

**Nothing here imports ``bpy``.** It is pure data and pure functions, so every tool can consult the
policy before deciding whether to start Blender at all, and so the whole of it is unit-testable
without a Blender host.
"""

from __future__ import annotations

from .classes import (
    CLASSES,
    TRANSFORMS,
    DestructionClass,
    PolicyError,
    Transform,
    Treatment,
    permits,
    require_permitted,
    treatment,
)
from .exit_codes import EXIT_NAMES, name_for

__all__ = [
    "CLASSES",
    "EXIT_NAMES",
    "TRANSFORMS",
    "DestructionClass",
    "PolicyError",
    "Transform",
    "Treatment",
    "name_for",
    "permits",
    "require_permitted",
    "treatment",
]
