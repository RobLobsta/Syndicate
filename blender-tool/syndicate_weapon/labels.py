"""The closed weapon sub-part taxonomy of D17-S4.2, and the constants the ensemble measures against.

D17-R2 makes the set closed for the reason D15-R1 makes the vehicle taxonomy closed: a new model
must not silently introduce a sub-part that nothing downstream handles. D17-R3 makes
``unclassified`` a first-class outcome that merges into the ``receiver`` — always a
correct-if-coarse answer — and reports how much of the gun could not be named.

D17-R1: every threshold here is a constant, not per-model tuning. A threshold that has to move for
a new model is a bug in the threshold.
"""

from __future__ import annotations

from dataclasses import dataclass

# ---- The taxonomy (D17-S4.2) -------------------------------------------------------------

MOUNT = "mount"
RECEIVER = "receiver"
BARREL = "barrel"
MUZZLE = "muzzle"
BREECH = "breech"
FEED = "feed"
GEAR = "gear"
SIGHT = "sight"
FURNITURE = "furniture"
UNCLASSIFIED = "unclassified"

#: Every label, in the order D17-S4.2 tabulates them.
LABELS = (MOUNT, RECEIVER, BARREL, MUZZLE, BREECH, FEED, GEAR, SIGHT, FURNITURE, UNCLASSIFIED)

#: The ``category`` each label is exported as (D08-S4.2, D17-S4.2). Only the labels that make the
#: gun *work* are ``WEAPON``; losing a sight or a grip is cosmetic, and the category is what says
#: so.
PART_CATEGORY = {
    MOUNT: "WEAPON",
    RECEIVER: "WEAPON",
    BARREL: "WEAPON",
    MUZZLE: "DECORATIVE",
    BREECH: "WEAPON",
    FEED: "UTILITY",
    GEAR: "DECORATIVE",
    SIGHT: "DECORATIVE",
    FURNITURE: "DECORATIVE",
    UNCLASSIFIED: "WEAPON",
}

#: The ``slotTypeRequired`` each label is exported as — a real ``SlotType`` (D05-S4.3), chosen so
#: that ``SlotType.acceptsCategory`` agrees with :data:`PART_CATEGORY`. The ``mount`` is the only
#: one that occupies a slot on the *vehicle*; every other sub-part occupies a ``SUBSLOT`` on its
#: parent within the weapon.
SLOT_TYPE_REQUIRED = {
    MOUNT: "TURRET_MOUNT",
    RECEIVER: "SUBSLOT",
    BARREL: "SUBSLOT",
    MUZZLE: "SUBSLOT",
    BREECH: "SUBSLOT",
    FEED: "SUBSLOT",
    GEAR: "SUBSLOT",
    SIGHT: "SUBSLOT",
    FURNITURE: "SUBSLOT",
    UNCLASSIFIED: "SUBSLOT",
}

#: The destruction class each label receives (D17-S4.2, applied through D15-S5.7's table).
DESTRUCTION_CLASS = {
    MOUNT: "STRUCTURAL",
    RECEIVER: "STRUCTURAL",
    BARREL: "RIGID",
    MUZZLE: "RIGID",
    BREECH: "STRUCTURAL",
    FEED: "RIGID",
    GEAR: "RIGID",
    SIGHT: "RIGID",
    FURNITURE: "SHEET_METAL",
    UNCLASSIFIED: "STRUCTURAL",
}

#: The material each label is made of, keyed into ``assets/materials/materials.json`` (DEC-045).
DEFAULT_MATERIAL = {
    MOUNT: "steel",
    RECEIVER: "steel_hardened",
    BARREL: "steel_hardened",
    MUZZLE: "steel_hardened",
    BREECH: "steel",
    FEED: "steel",
    GEAR: "steel",
    SIGHT: "plastic",
    FURNITURE: "steel",
    UNCLASSIFIED: "steel",
}

#: Which sub-part each label hangs off in the weapon's slot graph (D17-R42). ``None`` for the root.
#:
#: Parenting follows **support**, not proximity (D17-R43): a muzzle is on the barrel, not on the
#: receiver, because shooting the barrel off should take the muzzle with it.
PARENT_LABEL = {
    MOUNT: None,
    GEAR: MOUNT,
    RECEIVER: MOUNT,
    BARREL: RECEIVER,
    MUZZLE: BARREL,
    BREECH: RECEIVER,
    FEED: RECEIVER,
    SIGHT: RECEIVER,
    FURNITURE: RECEIVER,
}

#: Mass per square metre of surface, by label (DEC-067's rule, D17-R52's numbers).
#:
#: Several times the vehicle pipeline's, and that is the point: a car door is a 1 mm skin over air
#: and a gun barrel is a solid tube of steel. Using the vehicle figures here produced a 4 kg cannon,
#: which is what D17-R52 exists to prevent.
#:
#: Calibrated against real ordnance rather than chosen: these put the shipped machine gun at about
#: 45 kg, which is where a real vehicle-mounted heavy machine gun sits with its mount.
AREAL_DENSITY_KG_PER_M2 = {
    MOUNT: 55.0,
    RECEIVER: 60.0,
    BARREL: 90.0,
    MUZZLE: 70.0,
    BREECH: 80.0,
    FEED: 30.0,
    GEAR: 45.0,
    SIGHT: 8.0,
    FURNITURE: 18.0,
    UNCLASSIFIED: 50.0,
}


# ---- Ensemble weights and thresholds (D17-S5.6) ------------------------------------------

#: Weight on each cue family when votes are summed (D17-R34). The axial cue and the geometric cue
#: are peers and both outrank the two that read names and relations, for D15-R6's reason —
#: measurement beats hearsay.
CUE_WEIGHT = {
    "W1_axial": 1.0,
    "W2_geometric": 1.0,
    "W3_material": 0.7,
    "W4_structural": 0.6,
}

#: The winning label's summed weight must exceed this or the shell is ``unclassified`` (D17-R38).
LABEL_MIN_CONFIDENCE = 0.55


# ---- Geometry thresholds, in game metres (D17-S4.1) --------------------------------------

#: Shells below this merge into their nearest labelled neighbour (D17-R33). Lower than D15's 24: a
#: gun is two orders of magnitude smaller than a car, and its front sight is genuinely 10 triangles.
WEAPON_MIN_SHELL_TRIANGLES = 8

#: Abort rather than run for an unbounded time (D17-E2).
WEAPON_MAX_SHELLS = 4000

#: Length-to-width above which a shell is barrel-like (D17-R1). Dimensionless, so it needs no
#: normalisation.
BORE_ASPECT_MIN = 3.0

#: Fraction of the longest barrel-like shell's extent below which a shell is too small to help fit
#: the bore axis (D17-R24).
#:
#: A detailed model separates into hundreds of components and plenty of them are technically slender
#: — a bolt, a rivet strip, a hinge pin. Each contributes as many sampled vertices to the fit as the
#: barrel does, so on the shipped cannon 203 shells outvoted the one that mattered.
BORE_FIT_MIN_EXTENT_FRAC = 0.35

#: How far off the bore axis a centroid may sit and still join the barrel group (D17-R1).
#:
#: **A fraction of the weapon's own bore length, not metres.** Every threshold below that measures a
#: distance is, and the reason is that a downloaded model arrives at an unknown scale: these two
#: import at 100x, and a metre-based tolerance applied to them classifies nothing. The pipeline
#: normalises the model to unit bore length before it labels anything (D17-R23a), which makes every
#: one of these a proportion of the gun — which is what they always meant.
BORE_COAXIAL_TOL = 0.045

#: The reach, as a fraction of bore length, at which two sub-parts count as **touching** (D17-R44).
#:
#: This is a contact radius, not a gap budget, and the difference matters. An earlier version made
#: it
#: a 2 mm ceiling on the measured gap and failed every weapon: real art models clearances — a barrel
#: inside its shroud, a sight above its rail — so asserting that no two parts have daylight between
#: them asserts something about the artist rather than about the pipeline. What the pipeline is
#: responsible for is placing each join **on the contact the source actually has**, and never moving
#: geometry to fake one.
SEAM_CONTACT_REACH = 0.03

#: Widening passes applied to :data:`SEAM_CONTACT_REACH` when no contact is found at it. The pass
#: that succeeded is reported, so a join found at the widest reach reads as the weak join it is.
SEAM_REACH_PASSES = (1.0, 3.0, 8.0)

#: A weapon offering more sub-parts than D05-R14's per-part slot cap is a taxonomy failure
#: (D17-R40).
MAX_SUBPARTS_PER_WEAPON = 8

#: Bore-axis length each size class is scaled to (D17-R26).
TARGET_LENGTH_M = {"LIGHT": 0.9, "MEDIUM": 1.4, "HEAVY": 1.8}

#: A model already this close to its target is left alone rather than scaled by 1.02 (D17-R26).
SCALE_DEADBAND = 0.10

#: Radius about the bore, as a fraction of bore length, outside which geometry is **not the weapon**
#: (D17-R27).
#:
#: A gun's working parts — breech, receiver, barrel, muzzle, feed, sights, and the cradle that holds
#: them — all live close to the bore, because they are all arranged around it. What lives further
#: out
#: is what the gun is *carried on*: a display base, a diorama, or a siege carriage's road wheels and
#: axle. The shipped cannon is exactly that case (D17-E4): its cradle sits at 0.37 of its length
#: from
#: the bore and its wheels at 0.48, so this separates the gun from the thing it rolls on.
CARRIAGE_RADIUS = 0.40

#: Instances of a shape about an axis before it counts as a rotational set (D17-R37). Three, the
#: same threshold DEC-066 uses for a wheel's repetition test.
ROTATION_MIN_INSTANCES = 3

#: A shell whose reflection about the bore lands within this of another is one of a mirrored pair.
#: A fraction of bore length (see :data:`BORE_COAXIAL_TOL`).
MIRROR_TOLERANCE = 0.025

#: Labels that can legitimately exist as a left-and-right pair (D17-R39).
#:
#: A gun has one receiver, one breech and one bore. It can have two grips, two sights, two feed
#: chutes and two elevation quadrants. Splitting by side without this restriction turned the shipped
#: machine gun's single receiver into ``receiver_l`` and ``receiver_r``, which is not a weapon.
SIDED_LABELS = ("furniture", "gear", "sight", "feed")


@dataclass(frozen=True)
class Vote:
    """One cue family's opinion about one shell.

    :param cue: which family — a key of :data:`CUE_WEIGHT`
    :param label: the label voted for
    :param confidence: ``[0,1]``; contributes ``confidence * CUE_WEIGHT[cue]``
    :param because: a short human-readable reason, carried into the report so an operator can see
        *why* a shell was labelled and not merely *what* it was labelled (D15-R11's rule)
    """

    cue: str
    label: str
    confidence: float
    because: str

    @property
    def weight(self) -> float:
        return self.confidence * CUE_WEIGHT.get(self.cue, 0.0)

    def as_dict(self) -> dict:
        return {
            "cue": self.cue,
            "label": self.label,
            "confidence": round(self.confidence, 4),
            "weight": round(self.weight, 4),
            "because": self.because,
        }
