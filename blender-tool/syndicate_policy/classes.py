"""Which transform each destruction class receives (D15-S5.7).

D15-R32 maps every part label to a destruction class and every class to a treatment. This module is
that table, promoted out of ``syndicate_prepare`` so the transform tools it drives can consult it
themselves — which is what lets ``syndicate_fracture`` refuse to shard a door instead of doing it.

The rule that shapes it is D15-R33: **a class's parameters are per-class constants, not per-part
authoring.** A part that needs different numbers is evidence the taxonomy is missing a class, not
that the part needs hand-tuning, so there is no way to express a per-part override here and adding
one is a change to D15 rather than a change to a file.

The transform vocabulary is D00-S6's, not a new one. *Fracture* is "the one-time replacement of a
part's single rigid body with its pre-authored shards"; *deformation* is "continuous visual mesh
change driven by shape keys", and the glossary's own note on the first is "not fracture" on the
second. Those two words are the two tools.
"""

from __future__ import annotations

from dataclasses import dataclass

#: Damage morph names, in the order D07-S5.5 and D08-R6 require them. A part carries either exactly
#: these four or none at all (A211).
MORPH_LEVELS = ("dmg_25", "dmg_50", "dmg_75", "dmg_100")


class Transform(str):
    """A destruction transform, as D00-S6 names them.

    A ``str`` subclass rather than an ``Enum`` so it round-trips through JSON as itself: the value
    is written into every manifest and read back by the asset gate, and a bare string is what both
    ends want to compare.
    """

    __slots__ = ()


#: The one-time replacement of a part's body with its pre-authored shards (D07-S5.6).
FRACTURE = Transform("FRACTURE")

#: Continuous visual mesh change driven by damage shape keys (D07-S5.5).
DEFORM = Transform("DEFORM")

#: Every transform a manifest may declare. ``DETACH`` and ``ARTICULATE`` are absent deliberately:
#: neither is authored by a Blender tool. Detachment is a consequence of the slot graph and
#: ``breakImpulseN``, and articulation is a block on ``part.json`` (DEC-083).
TRANSFORMS: tuple[Transform, ...] = (FRACTURE, DEFORM)


class DestructionClass(str):
    """How a part fails (D15-S5.7). A ``str`` for the same reason :class:`Transform` is."""

    __slots__ = ()


SHEET_METAL = DestructionClass("SHEET_METAL")
GLASS = DestructionClass("GLASS")
STRUCTURAL = DestructionClass("STRUCTURAL")
RIGID = DestructionClass("RIGID")
NONE = DestructionClass("NONE")

CLASSES: tuple[DestructionClass, ...] = (SHEET_METAL, GLASS, STRUCTURAL, RIGID, NONE)


class PolicyError(ValueError):
    """A transform was asked of a class that D15-S5.7 does not give it.

    Carries the class and the transform so a tool can put both in its failure document without
    re-deriving them from the message.
    """

    def __init__(self, message: str, destruction_class: str, transform: str) -> None:
        super().__init__(message)
        self.destruction_class = destruction_class
        self.transform = transform


@dataclass(frozen=True)
class Treatment:
    """What one destruction class does to a part (D15-S5.7's table).

    :param subdivide_edge_m: target edge length before deformation authoring; ``0`` for no
        subdivision. A panel crumples locally and keeps its area, so it needs vertex density where
        the dent is or the dent is a facet. A chassis buckles and shears globally, and fine
        subdivision makes it squish like a sponge — which is the failure mode to avoid, and the
        reason these two numbers differ by an order of magnitude rather than by taste.
    :param deform: whether damage shape keys are authored. Glass does not dent: a deformed
        windscreen looks like a bug and a shattered one reads instantly.
    :param fracture_shards: shards to pre-author at build time; ``0`` for a part that leaves whole.
    :param yield_impulse_ns: the impulse at which a ``structural`` part starts to buckle, in
        newton-seconds so that it is comparable with ``breakImpulseN`` (D15-R34) and "the frame
        buckles before the mounts shear" is a statement about two numbers in the same unit. ``0``
        where the class has no yield behaviour.
    """

    destruction_class: DestructionClass
    subdivide_edge_m: float
    deform: bool
    fracture_shards: int
    yield_impulse_ns: float

    @property
    def morphs(self) -> bool:
        """Alias kept for D15's wording, which says "morph targets" where this says deform."""
        return self.deform

    def permits(self, transform: str) -> bool:
        """Whether this class receives ``transform`` (D15-S5.7)."""
        if transform == DEFORM:
            return self.deform
        if transform == FRACTURE:
            return self.fracture_shards > 0
        return False

    def as_dict(self) -> dict:
        return {
            "destructionClass": str(self.destruction_class),
            "subdivideEdgeM": self.subdivide_edge_m,
            "morphTargets": list(MORPH_LEVELS) if self.deform else [],
            "fractureShards": self.fracture_shards,
            "yieldImpulseNs": self.yield_impulse_ns,
        }


#: D15-S5.7's table, one row per class. Note that no class takes both transforms — that is the
#: invariant the whole suite exists to hold, and it is visible here as a property of the data
#: rather than asserted anywhere.
TREATMENTS: dict[DestructionClass, Treatment] = {
    SHEET_METAL: Treatment(SHEET_METAL, 0.08, True, 0, 0.0),
    GLASS: Treatment(GLASS, 0.0, False, 24, 0.0),
    STRUCTURAL: Treatment(STRUCTURAL, 0.60, True, 0, 9000.0),
    RIGID: Treatment(RIGID, 0.0, False, 0, 0.0),
    NONE: Treatment(NONE, 0.0, False, 0, 0.0),
}


def parse_class(raw: str | None) -> DestructionClass:
    """A destruction class from its name, case-insensitively.

    :raises PolicyError: when the name is not one of :data:`CLASSES`. Never defaults: a
        misspelled class silently becoming ``RIGID`` would author nothing and report success.
    """
    if raw is None or not str(raw).strip():
        raise PolicyError(
            f"no destruction class given; expected one of {', '.join(CLASSES)} (D15-S5.7)",
            destruction_class=str(raw),
            transform="",
        )
    candidate = str(raw).strip().upper()
    for known in CLASSES:
        if candidate == known:
            return known
    raise PolicyError(
        f"'{raw}' is not a destruction class; expected one of {', '.join(CLASSES)} (D15-S5.7)",
        destruction_class=str(raw),
        transform="",
    )


def treatment(destruction_class: str) -> Treatment:
    """The treatment a class receives. Accepts the class name in any case."""
    return TREATMENTS[parse_class(destruction_class)]


def permits(transform: str, destruction_class: str) -> bool:
    """Whether D15-S5.7 gives ``transform`` to ``destruction_class``."""
    return treatment(destruction_class).permits(transform)


def require_permitted(transform: str, destruction_class: str) -> Treatment:
    """The treatment, or :class:`PolicyError` if the class does not receive this transform.

    This is the call that turns D15-S5.7 from prose into something a tool refuses on. It is
    deliberately phrased as a *permission* rather than a *default*: a tool asks whether it may
    proceed and stops if not, instead of quietly substituting the treatment the class does get.
    Substituting would be worse than either — the caller asked for shards and would receive dents,
    with a success document saying so in a field nobody reads.
    """
    resolved = treatment(destruction_class)
    if resolved.permits(transform):
        return resolved
    permitted = [t for t in TRANSFORMS if resolved.permits(t)] or ["none"]
    raise PolicyError(
        f"D15-S5.7 does not give {transform} to a {resolved.destruction_class} part "
        f"(it receives: {', '.join(permitted)})",
        destruction_class=str(resolved.destruction_class),
        transform=str(transform),
    )
