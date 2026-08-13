"""Stage 7: what treatment each part gets before anything ever hits it (D15-S5.7).

D15-R32 maps every label to a destruction class and every class to a treatment. This module is
that table as code, plus the two numbers each treatment needs that the table states in prose.

The rule that shapes it is D15-R33: **a class's parameters are per-class constants, not
per-part authoring.** A part that needs different numbers is evidence the taxonomy is missing a
class, not that the part needs hand-tuning — so there is no way to express a per-part override
here, deliberately, and adding one is a change to D15 rather than a change to a file.

Nothing in this module touches Blender. It decides what will be done;
:mod:`syndicate_prepare.authoring` does it.
"""

from __future__ import annotations

from dataclasses import dataclass

from .labels import DESTRUCTION_CLASS

#: Damage morph names, in the order D07-S5.5 and D08-R6 require them. A part carries either
#: exactly these four or none at all (A211).
MORPH_LEVELS = ("dmg_25", "dmg_50", "dmg_75", "dmg_100")


@dataclass(frozen=True)
class Treatment:
    """What one destruction class does to a part (D15-S5.7's table).

    :param subdivide_edge_m: target edge length before deformation authoring; ``0`` for no
        subdivision. A panel crumples locally and keeps its area, so it needs vertex density
        where the dent is or the dent is a facet. A chassis buckles and shears globally, and
        fine subdivision makes it squish like a sponge — which is the failure mode to avoid,
        and the reason these two numbers differ by an order of magnitude rather than by taste.
    :param morphs: whether damage shape keys are generated. Glass does not dent: a deformed
        windscreen looks like a bug and a shattered one reads instantly.
    :param fracture_shards: shards to pre-author at build time; ``0`` for a part that leaves
        whole.
    :param yield_impulse_ns: the impulse at which a ``structural`` part starts to buckle,
        expressed in newton-seconds so that it is comparable with ``breakImpulseN`` (D15-R34)
        and "the frame buckles before the mounts shear" is a statement about two numbers in
        the same unit. ``0`` where the class has no yield behaviour.
    """

    destruction_class: str
    subdivide_edge_m: float
    morphs: bool
    fracture_shards: int
    yield_impulse_ns: float

    def as_dict(self) -> dict:
        return {
            "destructionClass": self.destruction_class,
            "subdivideEdgeM": self.subdivide_edge_m,
            "morphTargets": list(MORPH_LEVELS) if self.morphs else [],
            "fractureShards": self.fracture_shards,
            "yieldImpulseNs": self.yield_impulse_ns,
        }


#: D15-S5.7's table, one row per class.
TREATMENTS = {
    "SHEET_METAL": Treatment("SHEET_METAL", 0.08, True, 0, 0.0),
    "GLASS": Treatment("GLASS", 0.0, False, 24, 0.0),
    "STRUCTURAL": Treatment("STRUCTURAL", 0.60, True, 0, 9000.0),
    "RIGID": Treatment("RIGID", 0.0, False, 0, 0.0),
    "NONE": Treatment("NONE", 0.0, False, 0, 0.0),
}


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
