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
#: **They are not all the same kind of steel structure**, and that is what the spread encodes. A
#: barrel, a breech and a muzzle are solid: they get the high figures. A mount, a gear housing and a
#: shield are castings and pressings enclosing air, and giving them a barrel's density made the
#: shipped pedestal cannon weigh **611 kg** — a third of the car carrying it, enough to take the
#: Stampede's 0-100 from 3.4 s to 4.4 s and to compress its suspension by 19 mm.
#:
#: Calibrated so the shipped machine gun lands near 20 kg and the pedestal cannon near 200 kg — and
#: the cannon is what set the ceiling. At 389 kg on the Stampede's **roof** turret it raised the
#: car's
#: centre of mass by 24 cm, and a bot driving it managed 19 m in a match against the 160 m its
#: stablemate covered. A weapon heavy enough to make its carrier undriveable is not a heavy weapon,
#: it is a broken one, and that is a tighter constraint than plausibility (DISC-060).
AREAL_DENSITY_KG_PER_M2 = {
    MOUNT: 8.0,
    RECEIVER: 19.0,
    BARREL: 55.0,
    MUZZLE: 45.0,
    BREECH: 45.0,
    FEED: 18.0,
    GEAR: 12.0,
    SIGHT: 6.0,
    FURNITURE: 7.0,
    UNCLASSIFIED: 19.0,
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

#: How many times the largest barrel-like shell's sample is repeated in the bore fit; a smaller
#: shell
#: is repeated in proportion to its triangle count (D17-R24).
#:
#: Every shell carries the same 96-vertex sample whatever its size, so an unweighted fit treats a
#: conduit strapped along a barrel as the barrel's equal. Eight steps is enough to make a
#: 600-triangle
#: barrel outvote a 174-triangle pipe and small enough that the fit stays a few thousand points.
BORE_FIT_WEIGHT_STEPS = 8

#: How far off the bore axis a centroid may sit and still join the barrel group (D17-R1).
#:
#: **A fraction of the weapon's own bore length, not metres.** Every threshold below that measures a
#: distance is, and the reason is that a downloaded model arrives at an unknown scale: these two
#: import at 100x, and a metre-based tolerance applied to them classifies nothing. The pipeline
#: normalises the model to unit bore length before it labels anything (D17-R23a), which makes every
#: one of these a proportion of the gun — which is what they always meant.
BORE_COAXIAL_TOL = 0.12

#: How far off the bore a breech or a receiver may sit and still count as built around it.
#:
#: Wider than :data:`BORE_COAXIAL_TOL` because those parts *enclose* the bore rather than being it.
NEAR_AXIS_TOL = 0.22

#: How round in section a shell must be before elongation alone makes it a barrel (D17-R36).
#: A barrel is a tube; a receiver is a box that happens to be long.
BARREL_ROUNDNESS_MIN = 0.70

#: How round about the bore axis a shell must be to be a candidate for defining the bore *line*.
#: A muzzle is a circular opening; a bracket is not (D17-R24).
MUZZLE_ROUNDNESS_MIN = 0.80

#: A shell whose two larger extents are at least this close to equal is round in its own plane.
DISC_ROUNDNESS_MIN = 0.80

#: ...and whose smallest extent is at most this fraction of its largest is thin through that plane.
#: Together these two make the disc test that finds a cog whatever axis it turns about (D17-R36b).
DISC_THINNESS_MAX = 0.45

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

#: Radius about the bore, as a fraction of bore length, beyond which geometry is a **stray** — a
#: diorama's ground plane or a display board, not part of the weapon (D17-R27, D17-E3).
#:
#: **Deliberately generous, and it used to be four times tighter.** At 0.40 it discarded the shipped
#: cannon's entire pedestal mount and both of its gears on the theory that they were a gun
#: carriage's
#: road wheels. They are not: the model is a deck gun on a pedestal, the "carriage" is the mounting
#: platform and the "wheels" are cogs (DISC-059). A mount is *supposed* to sit well off the bore —
#: that is what mounting means — so a rule that cuts at the mount's radius cannot distinguish the
#: two.
#: Only something further out than the weapon is wide has no plausible claim to be part of it.
STRAY_RADIUS = 1.60

#: Fraction of the model's **own** furthest offset from its bore beyond which a shell is part of
#: what
#: the weapon bolts to rather than part of the weapon (D17-R36a).
#:
#: Relative, not absolute, and that is the whole of why it needs no per-model tuning. How far a
#: gun's
#: mounting hangs off its bore is a property of the gun: the shipped cannon's pedestal reaches half
#: the gun's length and the shipped machine gun's side bracket reaches four per cent of it, and both
#: are equally the mount. An absolute threshold that finds one cannot find the other.
MOUNT_SHARE_MIN = 0.60

#: Bore position beyond which the mount cue stops voting. Nothing bolts a gun on by its muzzle.
MOUNT_FORWARD_LIMIT = 0.85

#: How lopsided a model must be, as a fraction of bore length, before the mount cue votes at all.
#:
#: A weapon that is genuinely symmetric about its bore has no mounting direction, and without this
#: floor the cue would find one in floating-point noise and label whichever shell won it.
MOUNT_REACH_FLOOR = 0.02

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
