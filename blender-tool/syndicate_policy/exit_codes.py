"""One exit-code table for the whole suite (D09-S4.3, D17-R19).

D09-R5 makes the exit code an agent's primary control signal and invites it to "branch on the
category with integer division". That only works if the categories mean the same thing in every
tool, and they did not: ``syndicate_weapon`` used 66 for a missing input where D09 uses it for
invalid geometry, and ``syndicate_prepare`` used 65 for an under-labelled model where D09 uses it
for an unreadable input.

The ranges:

===========  ====================================================================
``64-79``    Shared. Every tool uses these for the same thing (D09-S4.3).
``80-89``    ``syndicate_weapon``, reserved by D17-R19.
``90-99``    ``syndicate_prepare``. Reserved here, following D17-R19's precedent.
===========  ====================================================================

Codes are permanent and are never reused, exactly like the asset gate's ``A***`` codes.
"""

from __future__ import annotations

# --- Shared, 64-79 (D09-S4.3) ----------------------------------------------------------
EXIT_OK = 0
EXIT_USAGE = 64
EXIT_INPUT_INVALID = 65
EXIT_INPUT_GEOMETRY_INVALID = 66
EXIT_MATERIAL_UNRESOLVED = 67
EXIT_FRACTURE_FAILED = 68
EXIT_SHAPEKEY_FAILED = 69
EXIT_BLENDER_ERROR = 70
EXIT_HULL_FAILED = 71
EXIT_MASS_IMPLAUSIBLE = 72
EXIT_VERIFICATION_FAILED = 73
EXIT_EXPORT_FAILED = 74
EXIT_OUTPUT_WRITE_FAILED = 75
EXIT_DETERMINISM_VIOLATION = 76

#: A transform was asked of a destruction class D15-S5.7 does not give it — a windscreen sent to
#: the deform tool, a door sent to the fracture tool. Its own code rather than ``USAGE`` because
#: the invocation is well-formed and the *content decision* behind it is what is wrong, and an
#: agent's response differs: fix the class, or fix which tool you called, not the flags.
EXIT_TRANSFORM_NOT_PERMITTED = 77

# --- syndicate_weapon, 80-89 (D17-R19) -------------------------------------------------
EXIT_NO_GEOMETRY = 80
EXIT_TOO_MANY_SHELLS = 81
EXIT_NO_BORE_AXIS = 82
EXIT_TOO_MANY_SUBPARTS = 83
EXIT_SEAM_OPEN = 84
EXIT_WEAPON_MASS_IMPLAUSIBLE = 85
EXIT_SELF_VERIFY_FAILED = 86
EXIT_WEAPON_EXPORT_FAILED = 87

# --- syndicate_prepare, 90-99 ----------------------------------------------------------
#: Too little of the model's triangle area carried a label to trust the result (D15-R13). Was 65,
#: which D09-S4.3 already spends on "input file unreadable"; an agent that branched on the shared
#: meaning would have gone looking for a corrupt file.
EXIT_UNDER_LABELLED = 90

EXIT_NAMES: dict[int, str] = {
    EXIT_OK: "OK",
    EXIT_USAGE: "USAGE",
    EXIT_INPUT_INVALID: "INPUT_INVALID",
    EXIT_INPUT_GEOMETRY_INVALID: "INPUT_GEOMETRY_INVALID",
    EXIT_MATERIAL_UNRESOLVED: "MATERIAL_UNRESOLVED",
    EXIT_FRACTURE_FAILED: "FRACTURE_FAILED",
    EXIT_SHAPEKEY_FAILED: "SHAPEKEY_FAILED",
    EXIT_BLENDER_ERROR: "BLENDER_ERROR",
    EXIT_HULL_FAILED: "HULL_FAILED",
    EXIT_MASS_IMPLAUSIBLE: "MASS_IMPLAUSIBLE",
    EXIT_VERIFICATION_FAILED: "VERIFICATION_FAILED",
    EXIT_EXPORT_FAILED: "EXPORT_FAILED",
    EXIT_OUTPUT_WRITE_FAILED: "OUTPUT_WRITE_FAILED",
    EXIT_DETERMINISM_VIOLATION: "DETERMINISM_VIOLATION",
    EXIT_TRANSFORM_NOT_PERMITTED: "TRANSFORM_NOT_PERMITTED",
    EXIT_NO_GEOMETRY: "NO_GEOMETRY",
    EXIT_TOO_MANY_SHELLS: "TOO_MANY_SHELLS",
    EXIT_NO_BORE_AXIS: "NO_BORE_AXIS",
    EXIT_TOO_MANY_SUBPARTS: "TOO_MANY_SUBPARTS",
    EXIT_SEAM_OPEN: "SEAM_OPEN",
    EXIT_WEAPON_MASS_IMPLAUSIBLE: "MASS_IMPLAUSIBLE",
    EXIT_SELF_VERIFY_FAILED: "SELF_VERIFY_FAILED",
    EXIT_WEAPON_EXPORT_FAILED: "EXPORT_FAILED",
    EXIT_UNDER_LABELLED: "UNDER_LABELLED",
}


def name_for(code: int) -> str:
    """The stable name of an exit code, for a failure document's ``error`` field."""
    return EXIT_NAMES.get(code, f"UNKNOWN_{code}")
