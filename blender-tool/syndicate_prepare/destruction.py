"""Stage 7: what treatment each part gets before anything ever hits it (D15-S5.7).

D15-R32 maps every label to a destruction class and every class to a treatment. This module is
that table as code, plus the two numbers each treatment needs that the table states in prose.

The rule that shapes it is D15-R33: **a class's parameters are per-class constants, not
per-part authoring.** A part that needs different numbers is evidence the taxonomy is missing a
class, not that the part needs hand-tuning — so there is no way to express a per-part override
here, deliberately, and adding one is a change to D15 rather than a change to a file.

Nothing in this module touches Blender. It decides what will be done;
:mod:`syndicate_prepare.exporter` does it, by calling the two transform tools.

**The table itself now lives in :mod:`syndicate_policy`**, so the tools it drives can consult it
and refuse rather than trusting this module to ask them correctly (DISC-068). What stays here is
the one thing that is genuinely this pipeline's: the mapping from a D15-S4.1 *label* to a
destruction *class*, which only a vehicle preparation run has the labels to do.
"""

from __future__ import annotations

from syndicate_policy.classes import (  # noqa: F401 - re-exported; callers import from here
    MORPH_LEVELS,
    TREATMENTS,
    Treatment,
)

from .labels import DESTRUCTION_CLASS


def treatment_for(label: str) -> Treatment:
    """The treatment a labelled part receives (D15-R32)."""
    return TREATMENTS[DESTRUCTION_CLASS[label]]


def plan(parts) -> dict:
    """A per-class summary of what stage 7 will do, for the report.

    Grouped by class rather than listed per part, because the thing worth reading is whether
    the *classes* came out where D15-S5.7 says they should — twenty doors all listed
    separately tell an operator nothing that "twenty sheet_metal parts" does not.
    """
    grouped: dict[str, list[str]] = {}
    for part in parts:
        grouped.setdefault(treatment_for(part.label).destruction_class, []).append(part.name)
    return {
        name: {
            "parts": len(members),
            "treatment": TREATMENTS[name].as_dict(),
            "examples": sorted(members)[:4],
        }
        for name, members in sorted(grouped.items())
    }
