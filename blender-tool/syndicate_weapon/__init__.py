"""Turn one downloaded weapon model into a multi-part, articulated, game-ready weapon.

``docs/17_weapon_system.md`` is the contract. D15 does this job for a *vehicle* and this package
does it for a *weapon*, and the two problems differ in one structural way that shapes everything
here: a car is a three-dimensional arrangement that has to be reasoned about as one, and a gun is a
**sequence along a single line**. Every stage in this package is organised around the bore axis, and
the cue ensemble's strongest member is simply "where along the gun is this piece".

The design principle, inherited from D15 and unchanged:

    Infer what geometry can prove. Read what the file happens to say. Never invent balance from art.

The last clause is D17-R51 and is the one this package adds: a weapon's fire rate, range and
projectile speed come from D01-S4.4's balance table, never from a mesh. What the geometry decides is
which family the thing is and how big it is.

Stage order is D17-S5.1's and is not interchangeable — see :mod:`syndicate_weapon.weapon`.
"""

from __future__ import annotations

__all__ = [
    "EXIT_BAD_MODEL",
    "EXIT_BLENDER_ERROR",
    "EXIT_EXPORT_FAILED",
    "EXIT_INPUT_MISSING",
    "EXIT_MASS_IMPLAUSIBLE",
    "EXIT_NO_BORE_AXIS",
    "EXIT_NO_GEOMETRY",
    "EXIT_OK",
    "EXIT_SEAM_OPEN",
    "EXIT_SELF_VERIFY_FAILED",
    "EXIT_TOO_MANY_SHELLS",
    "EXIT_TOO_MANY_SUBPARTS",
    "EXIT_USAGE",
]

#: Success; every check passed (D17-R19).
EXIT_OK = 0

#: Bad arguments.
EXIT_USAGE = 64

#: The model could not be read.
EXIT_BAD_MODEL = 65

#: The model file or directory is missing.
EXIT_INPUT_MISSING = 65

#: A Blender-side failure with no more specific code.
EXIT_BLENDER_ERROR = 70

#: No geometry survived the repair stage.
EXIT_NO_GEOMETRY = 80

#: Shell count exceeded ``WEAPON_MAX_SHELLS`` (D17-E2).
EXIT_TOO_MANY_SHELLS = 81

#: No bore axis could be established (D17-E1).
EXIT_NO_BORE_AXIS = 82

#: Sub-part count exceeded ``MAX_SUBPARTS_PER_WEAPON`` (D17-R40).
EXIT_TOO_MANY_SUBPARTS = 83

#: A seam exceeded ``MOUNT_SEAM_TOL_M`` and could not be closed (D17-R44).
EXIT_SEAM_OPEN = 84

#: Derived mass implausible for the family and size class (D17-R52).
EXIT_MASS_IMPLAUSIBLE = 85

#: Self-verification failed (D17-R63).
EXIT_SELF_VERIFY_FAILED = 86

#: Export failed.
EXIT_EXPORT_FAILED = 87
