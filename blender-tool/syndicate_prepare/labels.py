"""The closed part-label taxonomy of D15-S4.1, and the constants the ensemble measures against.

D15-R1 makes the set closed so a new model cannot silently introduce a category nothing
downstream handles, and D15-R2 makes ``unclassified`` a first-class outcome rather than a
failure: it merges into the chassis, which is always a correct-if-coarse answer, and it is
reported by count and by triangle share so an operator can see how much of the car the
pipeline could not name.

D15-R7: every threshold here is a constant, not per-model tuning. A threshold that has to
move for a new model is a bug in the threshold.
"""

from __future__ import annotations

from dataclasses import dataclass

# ---- The taxonomy (D15-S4.1) -----------------------------------------------------------

CHASSIS = "chassis"
WHEEL = "wheel"
HUB = "hub"
PANEL = "panel"
GLASS = "glass"
MIRROR = "mirror"
LIGHT = "light"
DECAL = "decal"
GRILLE = "grille"
INTERIOR = "interior"
DRIVETRAIN = "drivetrain"
WEAPON = "weapon"
UNCLASSIFIED = "unclassified"

#: Every label, in the order D15-S4.1 tabulates them.
LABELS = (
    CHASSIS,
    WHEEL,
    HUB,
    PANEL,
    GLASS,
    MIRROR,
    LIGHT,
    DECAL,
    GRILLE,
    INTERIOR,
    DRIVETRAIN,
    WEAPON,
    UNCLASSIFIED,
)

#: The slot role each label maps to (D15-S4.1). ``None`` where the label takes no slot.
SLOT_ROLE = {
    CHASSIS: "ROOT",
    WHEEL: "WHEEL",
    HUB: "HUB",
    PANEL: "PANEL",
    GLASS: "GLASS",
    MIRROR: "ACCESSORY",
    LIGHT: "ACCESSORY",
    DECAL: "DECAL",
    GRILLE: "ACCESSORY",
    INTERIOR: None,
    DRIVETRAIN: "INTERNAL",
    WEAPON: "TURRET_MOUNT",
    UNCLASSIFIED: "ROOT",
}

#: The destruction class each label receives (D15-S5.7, D15-R32). Matches
#: ``dev.syndicate.model.DestructionClass``.
DESTRUCTION_CLASS = {
    CHASSIS: "STRUCTURAL",
    WHEEL: "RIGID",
    HUB: "RIGID",
    PANEL: "SHEET_METAL",
    GLASS: "GLASS",
    MIRROR: "RIGID",
    LIGHT: "RIGID",
    DECAL: "NONE",
    GRILLE: "RIGID",
    INTERIOR: "NONE",
    DRIVETRAIN: "STRUCTURAL",
    WEAPON: "RIGID",
    UNCLASSIFIED: "SHEET_METAL",
}

#: The ``category`` each label is exported as (D08-S4.2). D15-S4.1's "slot role" column names
#: roles — ``HUB``, ``PANEL``, ``GLASS``, ``DECAL``, ``INTERNAL`` — that are **not** members of
#: ``SlotType`` (D05-S4.3), and a part.json carrying one of them does not load. These two tables
#: are the mapping onto what the runtime actually has, and D15-S4.1 was amended to carry them.
#:
#: A door is exported as ``PANEL`` rather than as some new category, and that is the right
#: answer rather than a compromise: an armour panel is precisely a part that covers a region of
#: the vehicle, absorbs hits meant for what is behind it, and can be shot off. That is a door.
PART_CATEGORY = {
    CHASSIS: "CHASSIS",
    WHEEL: "WHEEL",
    HUB: "UTILITY",
    PANEL: "PANEL",
    GLASS: "DECORATIVE",
    MIRROR: "DECORATIVE",
    LIGHT: "DECORATIVE",
    DECAL: "DECORATIVE",
    GRILLE: "DECORATIVE",
    INTERIOR: "DECORATIVE",
    DRIVETRAIN: "UTILITY",
    WEAPON: "WEAPON",
    UNCLASSIFIED: "CHASSIS",
}

#: The ``slotTypeRequired`` each label is exported as — a real ``SlotType`` (D05-S4.3), chosen
#: so that ``SlotType.acceptsCategory`` agrees with :data:`PART_CATEGORY` above. An assembly
#: whose parts do not satisfy that relation fails validation at load (D05-S5.1).
SLOT_TYPE_REQUIRED = {
    CHASSIS: "ROOT",
    WHEEL: "WHEEL",
    HUB: "HARDPOINT",
    PANEL: "PANEL",
    GLASS: "ACCESSORY",
    MIRROR: "ACCESSORY",
    LIGHT: "ACCESSORY",
    DECAL: "ACCESSORY",
    GRILLE: "ACCESSORY",
    INTERIOR: "ACCESSORY",
    DRIVETRAIN: "HARDPOINT",
    WEAPON: "TURRET_MOUNT",
    UNCLASSIFIED: "ROOT",
}

#: The material each label is made of, keyed into ``assets/materials/materials.json``
#: (DEC-045: the material says what a part is made of; the part says how it fails). A
#: ``parts.json`` cannot currently override this per part — a model whose bonnet is carbon
#: rather than steel is a case for a future ``materialOverrides``, and the report says which
#: material each part was given so the gap is visible rather than assumed.
DEFAULT_MATERIAL = {
    CHASSIS: "steel",
    WHEEL: "rubber",
    HUB: "steel",
    PANEL: "steel",
    GLASS: "glass",
    MIRROR: "plastic",
    LIGHT: "plastic",
    DECAL: "trim",
    GRILLE: "plastic",
    INTERIOR: "trim",
    DRIVETRAIN: "steel",
    WEAPON: "steel_hardened",
    UNCLASSIFIED: "steel",
}

#: Whether a label's part may leave the vehicle (D15-S4.1). The chassis never can (D05-R26).
DETACHES = {
    CHASSIS: False,
    WHEEL: True,
    HUB: True,
    PANEL: True,
    GLASS: True,
    MIRROR: True,
    LIGHT: True,
    DECAL: True,
    GRILLE: True,
    INTERIOR: False,
    DRIVETRAIN: True,
    WEAPON: True,
    UNCLASSIFIED: False,
}


# ---- Ensemble weights and thresholds (D15-S4.2, D15-R7) --------------------------------

#: Weight on each cue family when votes are summed. C2 outranks C3 (D15-R6): a file's
#: declared transparency is what it will actually render as, and its material *name* is a
#: comment. C1 is weighted above C3 for the same reason — measurement beats hearsay.
CUE_WEIGHT = {
    "C1_geometric": 1.0,
    "C2_material_physical": 1.3,
    "C3_material_nominal": 0.7,
    "C4_structural": 0.6,
}

#: The winning label's summed weight must exceed this or the shell is ``unclassified``
#: (D15-R4). Low enough that one confident cue can carry a shell, high enough that a single
#: weak vote cannot.
LABEL_MIN_CONFIDENCE = 0.55

#: Below this labelled-triangle fraction, strict mode exits non-zero (D15-R13). Two-thirds:
#: a car that is a third unnamed has not been prepared, and saying so loudly is the
#: difference between a pipeline and a plausible-looking one.
REPORT_MIN_LABELLED_FRACTION = 0.67


# ---- Geometry thresholds, in game metres ------------------------------------------------

#: Shells with fewer triangles than this are merged into their nearest labelled neighbour
#: rather than labelled independently (D15-R17). Two-thirds to three-quarters of the shells
#: on a real car are bolts, screws and single grille strands.
MIN_SHELL_TRIANGLES = 24

#: Abort rather than run for an unbounded time (D15-E7). Both shipped cars produce about
#: 6,800; twice that is a model this pipeline has no business trying to prepare unattended.
MAX_SHELLS = 20_000

#: A group's centroid must be at least this far off the centreline to take a side (D15-R19).
SIDE_DEADBAND_M = 0.06

#: A shell whose reflection about ``x = 0`` lands within this of another shell is one
#: instance of a two-instance part (D15-R20).
MIRROR_TOLERANCE_M = 0.05

#: Degrees of the circle a piece must occupy about the axle to rotate with a wheel (D15-R21).
ROTATION_SYMMETRY_MIN_DEG = 300.0

#: Sectors the circle is divided into when measuring that coverage (D15-R21).
ROTATION_SECTORS = 24

#: Length-to-width ratio above which a `weapon` part's geometry is a barrel rather than a
#: mount, and is therefore a cannon rather than an autocannon (D15-R41). A tank gun is ten
#: times as long as it is wide; a turret ring is not.
BARREL_ASPECT_MIN = 4.0

#: A shell this far outside the body's hull is a stray fragment (D15-S5.5).
STRAY_SHELL_M = 8.0

#: The body centroid may sit this far off ``x = 0`` before centring is reported (D15-S5.5).
CENTRING_TOLERANCE_M = 0.02

#: Plausible overall vehicle length, for the scale repair of D15-S5.5.
MIN_VEHICLE_LENGTH_M = 2.0
MAX_VEHICLE_LENGTH_M = 16.0


@dataclass(frozen=True)
class Vote:
    """One cue family's opinion about one shell.

    :param cue: which family — a key of :data:`CUE_WEIGHT`
    :param label: the label voted for
    :param confidence: ``[0,1]``; the vote contributes ``confidence * CUE_WEIGHT[cue]``
    :param because: a short human-readable reason, carried into the report so an operator can
        see *why* a shell was labelled and not merely *what* it was labelled
    """

    cue: str
    label: str
    confidence: float
    because: str

    @property
    def weight(self) -> float:
        return self.confidence * CUE_WEIGHT.get(self.cue, 0.0)
