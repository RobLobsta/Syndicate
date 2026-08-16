"""Stage 8's arithmetic: part ids, masses, and the ``part.json`` / ``assembly.json`` documents.

Everything here is pure. It takes the labelled, grouped, rigged parts and produces the exact
JSON that :mod:`syndicate_prepare.exporter` writes beside the meshes, which is what makes the
whole of D08-S4.2 and D08-S4.4 conformance testable without a Blender host.

Three decisions in this module are worth reading before the code, because they are what makes a
dropped-in model come out as a vehicle that drives rather than as a folder of meshes.

**Mass comes from surface area, not from volume.** D09-R16 computes a part's mass as
``volume x density`` and that is right for a fractured solid, where the geometry is the object.
It is wrong for vehicle art: a car's panels are *shells*. A door modelled as a closed skin
encloses about 0.1 m³ of air, and ``volume x density`` calls it 785 kg of steel. The same door
measured as ``area x areal density`` comes out at 29 kg, which is what a door weighs. The
constant is a mass per square metre rather than a thickness times a density, for the reason
recorded on :data:`AREAL_DENSITY_KG_PER_M2`, and the enclosed volume caps it so that a part
can never weigh more than the solid it encloses.

**The chassis takes the balance.** Every other part is weighed from its own geometry; the
chassis is whatever is left of the vehicle's target mass. That is how the shipped Eclipse is
authored — 1350 kg of chassis plus four 37.5 kg wheels making 1500 — and it means a prepared
vehicle hits a plausible kerb weight without anybody knowing the wall thickness of the
particular bonnet the artist modelled.

**The power budget is distributed, not summed.** D05-S5.7's reference formula fixes the
*ratios* between parts; ``assets/balance/classes.json`` fixes the *total* for the vehicle's
class, and A312 makes that total an error rather than a warning. So each part's ``powerCost`` is
its share of the class target, which satisfies A312 by construction and leaves A210's advisory
comparison to say how far the formula and the budget disagree.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

from .destruction import MORPH_LEVELS, treatment_for
from .labels import (
    BARREL_ASPECT_MIN,
    CHASSIS,
    DEFAULT_MATERIAL,
    DESTRUCTION_CLASS,
    DETACHES,
    GLASS,
    HUB,
    PART_CATEGORY,
    SLOT_TYPE_REQUIRED,
    UNCLASSIFIED,
    WEAPON,
    WHEEL,
)

SCHEMA_VERSION = "1.0.0"

#: The name of the per-vehicle parts manifest, beside the parts it describes (D08-R14b). Must
#: match ``dev.syndicate.model.AssetPaths.PARTS_MANIFEST_FILE``.
PARTS_MANIFEST_FILE = "manifest.json"

# ---- Mass (see the module docstring) ---------------------------------------------------

#: Mass per square metre of surface, per destruction class. **Not** a thickness times a
#: density, and the difference is not pedantry: 20 mm is the right wall for a rubber tyre and
#: gives 22 kg/m², and the same 20 mm of *steel* gives 157 — so a brake hub with 1.37 m² of
#: folded surface came out at 214 kg, ten times a real one, and inflated a 1500 kg car to
#: 1977 kg. One `rigid` class covers both a tyre and a steel casting, so the constant it
#: carries has to be the quantity that is stable across the two.
#:
#: And it is stable, because it is what vehicle construction *is*: a designer picks whatever
#: thickness gets the required stiffness out of the material to hand, which lands nearly
#: everything on a car between 17 and 20 kg/m². Measured on the shipped pair: a 1.5 m² door
#: is 29 kg (19), a 2.19 m² wheel is 37.5 kg (17), a hub assembly is about 26 kg (19). Glass
#: is the exception and is genuinely lighter; a chassis is the other, being box sections and
#: a floor pan rather than a skin.
AREAL_DENSITY_KG_PER_M2 = {
    "SHEET_METAL": 19.6,
    "GLASS": 12.5,
    "STRUCTURAL": 78.5,
    "RIGID": 18.0,
    "NONE": 3.5,
}

#: Wall thickness per destruction class, in metres — a real geometric thickness, used where
#: one is needed rather than as a mass proxy. Today that is exactly one place: the solidify
#: that gives a `glass` part a wall before it is fractured.
WALL_THICKNESS_M = {
    "SHEET_METAL": 0.0025,
    "GLASS": 0.0050,
    "STRUCTURAL": 0.0100,
    "RIGID": 0.0200,
    "NONE": 0.0050,
}

#: Kerb mass per square metre of footprint, for a vehicle nobody has weighed. A 4.5 x 1.9 m
#: car comes out at 1497 kg and a 5.9 x 2.05 m pickup at 2118 kg, both within a few percent
#: of the real things. Anything that is not shaped like a road vehicle — a tank — needs
#: ``--mass``, and the report says loudly which of the two produced the number.
DEFAULT_AREAL_DENSITY_KG_PER_M2 = 175.0

#: The chassis may not fall below this share of the vehicle's mass. If the measured parts
#: leave less, the target is raised to keep it and the report says by how much: a car whose
#: doors weigh more than its structure is a measurement fault, not a design.
CHASSIS_MIN_FRACTION = 0.45

#: D00-S6.4. Nothing lighter than this is simulable.
MIN_BODY_MASS_KG = 0.01

# ---- Health, armour and breaking, per class (D15-R33) -----------------------------------

#: Hit points per kilogram. Calibrated on the shipped parts: the Eclipse's 1350 kg chassis is
#: authored at 2600 hp and its 37.5 kg wheel at 380.
HP_PER_KG = {
    "STRUCTURAL": 2.0,
    "SHEET_METAL": 8.0,
    "GLASS": 3.0,
    "RIGID": 10.0,
    "NONE": 5.0,
}
MIN_MAX_HP = 25.0

#: Armour per kilogram, for the two categories that carry armour at all. The Eclipse's chassis
#: is 18.0 at 1350 kg; a 29 kg door lands at 10, which is the right order beside it.
ARMOR_PER_KG = {"CHASSIS": 0.0133, "PANEL": 0.35}

#: Break impulse in N·s per kilogram (D06-R22 — the unit is impulse, not force). Again from
#: the shipped parts: 22000 on a 1350 kg chassis, 3200 on a 37.5 kg wheel.
BREAK_IMPULSE_NS_PER_KG = {
    "STRUCTURAL": 16.0,
    "SHEET_METAL": 40.0,
    "GLASS": 20.0,
    "RIGID": 85.0,
    "NONE": 30.0,
}
MIN_BREAK_IMPULSE_NS = 250.0

# ---- Drivetrain, from mass ---------------------------------------------------------------

#: Newtons of tractive force per kilogram of vehicle. A prepared vehicle has no engine bay
#: anybody measured, so it gets a competent-but-unremarkable one: 8 N/kg is a 0-100 in the
#: mid sixes for a car and in the low tens for a two-tonne pickup.
ENGINE_FORCE_N_PER_KG = 8.0

#: Watts per kilogram. 90 W/kg is 190 kW on a 2.1 t pickup — a large petrol V8, which is what
#: a pickup has. It caps top speed honestly (DEC-032) rather than leaving it unbounded.
ENGINE_POWER_W_PER_KG = 90.0

#: Newtons of brake force per kilogram. Slightly above the engine, as every road vehicle is.
BRAKE_FORCE_N_PER_KG = 10.5

#: The reference chassis handling of D06-S4.5, for a vehicle with no measured aerodynamics.
DEFAULT_CHASSIS_HANDLING = {
    "dragCoefficient": 0.45,
    "rollingResistance": 0.015,
    "downforceCoefficient": 0.1,
}

#: The reference wheel handling of D06-S4.5 (DEC-029).
DEFAULT_WHEEL_HANDLING = {
    "suspensionCompression": 2.4,
    "suspensionDamping": 2.3,
    "rollInfluence": 0.15,
    "suspensionRestLengthM": 0.3,
}

# ---- Lamps (D15-S5.11) ----------------------------------------------------------------------

#: What a lamp of each role emits, as the ``light`` block of D08-R6.
#:
#: Only a **head** lamp casts. A tail lamp, an indicator and a reflector are things that *glow*:
#: they read as lit from outside and they light nothing, which is also true of the real ones and
#: is what keeps a car park of eight vehicles down to sixteen casting lights rather than fifty.
#:
#: The numbers are a car's, not a room's. 55 metres and a 34-degree cone is a dipped headlamp;
#: 5000 K is a modern projector, warm enough not to read as a torch. The 3.5-degree downward tilt
#: is what a beam cut-off does, and it is the reason the pool of light lands in front of the car
#: rather than on the horizon.
LAMPS = {
    "head": {
        "colorRgb": {"r": 1.0, "g": 0.96, "b": 0.88},
        "intensity": 34.0,
        "coneOuterDeg": 34.0,
        "coneInnerDeg": 13.0,
        "rangeM": 55.0,
        "tiltDownDeg": 3.5,
        "castsBeam": True,
    },
    "tail": {
        "colorRgb": {"r": 1.0, "g": 0.12, "b": 0.07},
        "intensity": 2.2,
        "coneOuterDeg": 70.0,
        "coneInnerDeg": 40.0,
        "rangeM": 6.0,
        "tiltDownDeg": 0.0,
        "castsBeam": False,
    },
}

#: A lamp with no role at all — a repeater, a reflector nobody named. Glows, casts nothing.
DEFAULT_LAMP = "tail"


#: Gravity, as D00-S6.4 fixes it. Needed here for exactly one thing: the static sag of a wheel
#: (see :func:`wheel_slot_lift`).
GRAVITY_M_PER_S2 = 9.81

#: The suspension stiffness a wheel gets when nothing overrides it, mirrored from
#: ``VehicleFactory.WHEEL_SUSPENSION_STIFFNESS`` so this module needs no JVM to place a slot.
DEFAULT_SUSPENSION_STIFFNESS = 30.0

#: Metres by which two wheels on one axle may disagree about their distance from the centreline
#: before the pipeline stops believing both of them (see :func:`slot_positions`). Two centimetres
#: is more than real art's asymmetry and far less than a measurement fault.
AXLE_SYMMETRY_TOLERANCE_M = 0.02

#: Mass boundaries between the vehicle classes of ``assets/balance/classes.json``.
CLASS_BOUNDARIES = ((1300.0, "light"), (1900.0, "medium"))
HEAVIEST_CLASS = "heavy"


# ---- Hardpoints (D15-S10) ------------------------------------------------------------------

#: The mounting points every prepared vehicle offers, whether or not the model it was cut from
#: had anything to put on them (D15-R42).
#:
#: This is the half of the vehicle that its own art cannot supply. Weapons and utility modules
#: are **shared** content in ``assets/parts/`` — one autocannon fits every car — so they cannot
#: be derived from a particular model the way a door can. What the pipeline can derive is
#: *where they go*, and it derives it from the body's own box: a turret ring on the roof, a
#: mount on the bonnet, one at the tail, and one on each flank.
#:
#: Each row is ``(slotId, slotType, x, y, z, sizeClass)`` where the three coordinates are
#: fractions of the
#: body box — ``x`` of the half width, ``y`` of the height above the ground plane, ``z`` of the
#: length measured from the **rear**. The vehicle's front is ``z = 1``: that is the sense
#: ``roles._front_fraction`` uses and the sense ``VehicleFactory`` builds its wheels in, and a
#: hardpoint measured the other way round would put every gun on the boot lid.
#:
#: Four HARDPOINTs is exactly what D05-S4.3 allows a vehicle (2-4). The turret ring is a fifth
#: slot of a different type, and TURRET_MOUNT has no such limit because a vehicle has at most
#: one roof.
#:
#: The **size class** (D17-R10) is derived from *where the slot is*, which is the only thing about
#: these five that the geometry decides. The roof centreline is the one place a big gun can sit
#: without fouling the wheels, so it is ``HEAVY``; the bonnet and tail are central but
#: height-limited, so ``MEDIUM``; the flanks sit outboard of the body, where anything bulky is
#: wider than the car, so ``LIGHT``. This is what stops a siege cannon being bolted to a wing
#: mirror.
HARDPOINTS = (
    ("turret_main", "TURRET_MOUNT", 0.0, 1.02, 0.48, "HEAVY"),
    ("hardpoint_bonnet", "HARDPOINT", 0.0, 0.62, 0.86, "MEDIUM"),
    ("hardpoint_rear", "HARDPOINT", 0.0, 0.66, 0.12, "MEDIUM"),
    ("hardpoint_flank_l", "HARDPOINT", -0.92, 0.55, 0.50, "LIGHT"),
    ("hardpoint_flank_r", "HARDPOINT", 0.92, 0.55, 0.50, "LIGHT"),
)

#: The slot ids :data:`HARDPOINTS` produces. Named once so the pipeline, the manifest and every
#: check that tolerates an empty slot agree about which slots are allowed to be empty.
#:
#: By id rather than by slot **type**, deliberately: a ``hub`` part already occupies a
#: ``HARDPOINT``-typed slot (DEC-063), so a rule written against the type would let a missing
#: brake hub pass as an unfitted weapon mount.
HARDPOINT_SLOT_IDS = tuple(slot_id for slot_id, *_ in HARDPOINTS)

#: Fraction of the vehicle's own mass a single hardpoint may carry. A 1500 kg car takes a
#: 120 kg autocannon on any one mount and would not take four of them, which is what the power
#: budget is for rather than this number.
HARDPOINT_MASS_FRACTION = 0.08

#: The same, for the roof turret ring, which is rated to carry far more.
#:
#: A `TURRET_MOUNT` is the vehicle's primary weapon station and is `HEAVY` by D17-R10 precisely
#: because the roof centreline is the one place a big gun can sit. Rating it at the flank fraction
#: made that size class unusable: the shipped pedestal cannon's mount alone is 265 kg and the ring
#: would take 157 kg, so the one weapon built for the one HEAVY mount could not be fitted to it.
TURRET_MASS_FRACTION = 0.20

#: The size class each D15-S4.1 label's part is exported at (D17-S4.3).
#:
#: Only the hub is not the default. Its slot is rated ``LIGHT`` because a brake hub is the only
#: thing that ever goes there (D17-R10), and a part left at the ``MEDIUM`` default then fails A316
#: against
#: its own slot — which is exactly what happened, on all eight hubs of both shipped cars.
PART_SIZE_CLASS = {"hub": "LIGHT"}

#: The lightest a hardpoint may be rated at, so a 400 kg buggy still mounts something.
HARDPOINT_MIN_MASS_KG = 40.0

#: Roll, in degrees about Z, applied to a hardpoint that mounts a weapon on its side (D17-R25).
FLANK_ROLL_DEG = {"hardpoint_flank_l": 90.0, "hardpoint_flank_r": -90.0}


class ManifestError(Exception):
    """Something that makes the emitted asset invalid, reported rather than written."""


@dataclass
class PreparedPart:
    """One part, named, weighed and rigged, ready to be written out.

    :param part_type_id: the directory name and ``partTypeId`` (D08-R6)
    :param slot_id: the slot on the chassis this instance hangs from
    :param group: the :class:`syndicate_prepare.grouping.Part` it came from
    :param origin: where this part's mesh origin sits in chassis space. This is *the same
        point* as the slot's ``localPosition``, which is what makes the exported mesh and the
        manifest agree without a second frame to reconcile.
    :param instances: every slot id sharing this part type, in order. More than one only for
        the wheels and hubs of one axle.
    """

    part_type_id: str
    slot_id: str
    label: str
    role: str | None
    side: str
    group: object
    origin: tuple[float, float, float]
    mass_kg: float = 0.0
    power_cost: float = 0.0
    material_id: str = "steel"
    hinge: object | None = None
    corner: str | None = None
    instances: list[str] = field(default_factory=list)

    #: The groups the *other* instances of this part type were cut from, and where each of
    #: them sits. A shared wheel type exports one mesh and is placed at two axles; these are
    #: the second axle and the shells it was measured from. They exist so that AC-D15-4's
    #: accounting stays exact — every source triangle is still attributable to exactly one
    #: part — and so a slot is placed at the axle that was measured rather than at the
    #: reflection of the other side's, which on real art is never quite the same point.
    mirrored_groups: list = field(default_factory=list)
    mirrored_origins: list = field(default_factory=list)

    @property
    def is_chassis(self) -> bool:
        return self.label == CHASSIS

    @property
    def all_shells(self) -> list:
        """Every shell this part type accounts for, across all of its instances."""
        shells = list(self.group.shells)
        for group in self.mirrored_groups:
            shells.extend(group.shells)
        return shells

    @property
    def destruction_class(self) -> str:
        return DESTRUCTION_CLASS[self.label]

    @property
    def centre_local(self) -> tuple[float, float, float]:
        """The part's own centre, in its own space — where its mass acts (DEC-043)."""
        centre = self.group.centre
        return tuple(centre[i] - self.origin[i] for i in range(3))


# ---- Naming -----------------------------------------------------------------------------


def part_type_id(label: str, vehicle: str, role: str | None, side: str, index: int) -> str:
    """``^[a-z][a-z0-9_]{2,63}$``, and readable (D00-R14, D08-R6).

    The label leads so that a directory listing of ``assets/parts`` groups by kind, the
    vehicle follows so two cars never collide, and the role and side are what a human uses to
    find the one they mean: ``panel_pickup_door_l_01``.
    """
    pieces = [label, vehicle]
    if role:
        pieces.append(role)
    if side != "c":
        pieces.append(side)
    if index:
        pieces.append(str(index))
    return _sanitise("_".join(pieces) + "_01", limit=63)


def slot_id_for(label: str, role: str | None, side: str, index: int) -> str:
    """``^[a-z][a-z0-9_]{1,31}$``, unique within the chassis (D08-R6)."""
    pieces = [label]
    if role:
        pieces.append(role)
    if side != "c":
        pieces.append(side)
    if index:
        pieces.append(str(index))
    return _sanitise("_".join(pieces), limit=31)


def _sanitise(text: str, limit: int) -> str:
    cleaned = "".join(character if character.isalnum() else "_" for character in text.lower())
    while "__" in cleaned:
        cleaned = cleaned.replace("__", "_")
    cleaned = cleaned.strip("_")
    if not cleaned or not cleaned[0].isalpha():
        cleaned = "p_" + cleaned
    return cleaned[:limit].rstrip("_")


# ---- Assembling the part set --------------------------------------------------------------


def prepare_parts(vehicle: str, parts, corners, body) -> list[PreparedPart]:
    """Name every part, place its origin, and share the part types that may be shared.

    Wheels and hubs on one axle share a part type across the two sides — which is how the
    shipped assemblies are already authored, and what D15-R20 means by "both take the same
    part type with opposite ``side``". Nothing else shares: a left door is not a right door
    reflected, and a pipeline that pretended otherwise would put the handle on the inside.
    """
    axle_of = {corner.name: corner for corner in corners}
    prepared: list[PreparedPart] = []
    shared: dict[tuple[str, str], PreparedPart] = {}
    used_slots: set[str] = set()
    counters: dict[tuple[str, str | None, str], int] = {}

    for group in sorted(
        parts, key=lambda part: (part.label, part.role or "", part.side, part.index)
    ):
        if group.label in (CHASSIS, UNCLASSIFIED):
            # Both go into the chassis: D15-R2 makes `unclassified` merge there, which is
            # always a correct-if-coarse answer, and the caller builds that one group itself
            # because it spans every bucket rather than being one of them.
            continue
        key = (group.label, group.role, group.side)
        index = counters.get(key, 0)
        counters[key] = index + 1

        corner = _corner_of(group, axle_of)
        axle = _axle_name(corner)
        if corner is not None:
            # `wheel_fl`, as the shipped assemblies name them, rather than `wheel_front_l`.
            slot = _unique(slot_id_for(group.label, corner.name, "c", 0), used_slots)
        else:
            slot = _unique(slot_id_for(group.label, group.role, group.side, index), used_slots)

        if group.label in (WHEEL, HUB) and axle:
            existing = shared.get((group.label, axle))
            if existing is not None:
                # The shared instance still needs a slot on the chassis, but no second mesh:
                # its geometry is the canonical side's, placed at this corner's own axle.
                existing.instances.append(slot)
                existing.mirrored_groups.append(group)
                existing.mirrored_origins.append(_origin_for(group, corner, body))
                continue
            identifier = part_type_id(group.label, vehicle, axle, "c", 0)
        else:
            identifier = part_type_id(group.label, vehicle, group.role, group.side, index)

        part = PreparedPart(
            part_type_id=identifier,
            slot_id=slot,
            label=group.label,
            role=group.role,
            side=group.side,
            group=group,
            origin=_origin_for(group, corner, body),
            material_id=DEFAULT_MATERIAL[group.label],
            corner=corner.name if corner else None,
            instances=[slot],
        )
        prepared.append(part)
        if group.label in (WHEEL, HUB) and axle:
            shared[(group.label, axle)] = part

    return sorted(prepared, key=lambda part: part.part_type_id)


def _unique(candidate: str, used: set[str]) -> str:
    slot = candidate
    suffix = 2
    while slot in used:
        slot = f"{candidate[:29]}_{suffix}"
        suffix += 1
    used.add(slot)
    return slot


def _corner_of(group, axle_of: dict):
    names = {shell.corner for shell in group.shells if shell.corner}
    if len(names) != 1:
        return None
    return axle_of.get(next(iter(names)))


def _axle_name(corner) -> str | None:
    if corner is None:
        return None
    name = corner.name
    if name.startswith("f"):
        return "front"
    if name.startswith("r"):
        return "rear"
    return name[:-1]  # a0l -> a0


def _origin_for(group, corner, body):
    """Where a part's mesh origin goes — the single most consequential choice in the export.

    A wheel's origin is its axle, because Bullet spins a wheel about its part origin and a
    wheel offset from that origin orbits the vehicle instead of turning. An articulated
    part's origin is its hinge pivot, because D15-R30 makes an opening door a slot whose
    local rotation animates, and a rotation animates about the part's origin. Everything else
    gets its own bounds centre, which is where its mass acts (DEC-043) and therefore the
    point the compound shape wants it at.
    """
    del corner, body
    # For a wheel this is the axle, and that is not a coincidence: a wheel group is a disc,
    # symmetric about its axle in every direction, so its bounding box centre *is* the axle.
    #
    # It used to be the corner's own `axle`, which is the bounding box of whichever shells voted
    # "wheel" — a different and larger set that includes brake furniture. On the Stampede the two
    # differed by 3.6 cm vertically and 3 cm across, which put every wheel off its own centre
    # (a wheel spun about a point 3.6 cm off its middle wobbles visibly) and its track 6 cm wider
    # than the car is sold as. The corner model still decides *which* shells are a wheel; it no
    # longer decides where the wheel is.
    return tuple(group.centre)


# ---- Mass ---------------------------------------------------------------------------------


def body_width_m(body, corners) -> float:
    """The vehicle's width over its bodywork, not over its bounding box.

    A bounding box includes the wing mirrors, and on the Eclipse that is 2.18 m against a real
    2.0 — 11% on a number the kerb mass is derived from. When the pipeline found wheels it
    knows something better: the track plus a wheel's width is a vehicle's width to within a
    few centimetres on every road car, because that is what a track *is*.
    """
    if not corners:
        return body.width
    offsets = [abs(corner.axle[0]) for corner in corners]
    widths = [corner.width_m for corner in corners]
    return 2.0 * (sum(offsets) / len(offsets)) + sum(widths) / len(widths)


def target_mass_kg(body, override: float | None, corners=()) -> tuple[float, str]:
    """The vehicle's kerb mass: what was asked for, or what its footprint implies."""
    if override is not None and override > 0.0:
        return override, "given on the command line"
    width = body_width_m(body, corners)
    mass = width * body.length * DEFAULT_AREAL_DENSITY_KG_PER_M2
    over = "its track" if corners else "its bounding box"
    return mass, (
        f"{DEFAULT_AREAL_DENSITY_KG_PER_M2:.0f} kg/m² over a {width:.2f} x "
        f"{body.length:.2f} m footprint, measured across {over} — pass --mass to author it"
    )


#: The thinnest skin any part is allowed to be. Below this a mesh reads as a surface rather
#: than as a solid, whatever its signed volume says.
MIN_WALL_M = 0.0005


def surface_mass_kg(area_m2: float, enclosed_m3: float, destruction_class: str,
                    density_kg_per_m3: float) -> float:
    """A part's mass, from its surface area and what it encloses.

    ``area x areal density`` is the reading that works for everything a vehicle is made of
    (see :data:`AREAL_DENSITY_KG_PER_M2`). It is **an upper bound only**, because a part
    cannot contain more material than fits inside it — so a mesh that genuinely encloses a
    small solid is weighed as that solid instead.

    An open surface encloses nothing and keeps the surface reading; a hollow box encloses far
    more than its walls hold, so the surface reading stays the smaller of the two and wins; a
    small solid lump encloses less than its own folded surface implies, and the lump wins.
    """
    surface = area_m2 * AREAL_DENSITY_KG_PER_M2[destruction_class]
    if enclosed_m3 > area_m2 * MIN_WALL_M:
        return min(surface, enclosed_m3 * density_kg_per_m3)
    return surface


def geometric_mass_kg(part: PreparedPart, densities: dict[str, float]) -> float:
    """One part's mass (see :func:`surface_mass_kg`)."""
    area = sum(shell.area_m2 for shell in part.group.shells)
    enclosed = sum(shell.volume_m3 for shell in part.group.shells)
    return max(
        MIN_BODY_MASS_KG,
        surface_mass_kg(area, enclosed, part.destruction_class, densities[part.material_id]),
    )


def group_mass_kg(group, densities: dict[str, float]) -> float:
    """The same arithmetic over a raw group, before it has become a :class:`PreparedPart`."""
    area = sum(shell.area_m2 for shell in group.shells)
    enclosed = sum(shell.volume_m3 for shell in group.shells)
    return surface_mass_kg(
        area, enclosed, DESTRUCTION_CLASS[group.label],
        densities[DEFAULT_MATERIAL[group.label]],
    )


#: A part lighter than this is not a part. D15-R17 already absorbs shells below a triangle
#: floor into their neighbours; this is the same argument one level up, and it is needed for
#: the same reason: on a real car the grouping produces a long tail of 20-gramme fragments —
#: a chrome strip, one slat of a grille, a wiper arm — and each of them would otherwise take a
#: slot on the chassis, a directory in `assets/parts`, a network id and a collision hull.
MIN_PART_MASS_KG = 0.75


def absorb_cornerless_wheels(parts, corners):
    """Split off ``wheel`` and ``hub`` groups that belong to no corner (D15-R21).

    A wheel is not a label, it is a **position**: the four things a car rides on, each at the end
    of an axle the corner model measured. A group the nominal cue called a wheel and the corner
    model never captured is not a fifth wheel — it is a fragment whose material happened to carry
    the token, the same class of mistake DISC-037 records.

    Shipping it as a wheel is not cosmetic. It takes a ``WHEEL`` slot on the chassis, and
    ``VehicleFactory`` gives every wheel part a ray cast: the Stampede came out of the first run
    with a 0.8 kg fifth wheel half a metre up the driver's door, and the car would have ridden on
    it. Folded into the chassis it is what it always was — 104 triangles of bodywork.

    :return: ``(kept, cornerless)``; the caller folds the second into the chassis group, so every
        triangle is still accounted for exactly once (AC-D15-4).
    """
    named = {corner.name for corner in corners}
    kept, cornerless = [], []
    for group in parts:
        if group.label not in (WHEEL, HUB):
            kept.append(group)
            continue
        corners_of = {shell.corner for shell in group.shells if shell.corner}
        (kept if corners_of & named else cornerless).append(group)
    return kept, cornerless


def absorb_small_parts(parts, densities: dict[str, float]):
    """Split groups into the ones worth a part and the ones that belong in the chassis.

    :return: ``(kept, absorbed)`` — the second is folded into the chassis group by the caller,
        which keeps every triangle in exactly one part (AC-D15-4).
    """
    kept, absorbed = [], []
    for group in parts:
        (absorbed if group_mass_kg(group, densities) < MIN_PART_MASS_KG else kept).append(group)
    return kept, absorbed


def assign_masses(
    prepared: list[PreparedPart],
    chassis: PreparedPart,
    densities: dict[str, float],
    target_kg: float,
    fixed: dict | None = None,
) -> dict:
    """Weigh every part from its geometry and give the chassis the balance.

    :param fixed: parts whose mass has already been settled by something more authoritative than
        this arithmetic — today, a glass part weighed by its own fracture manifest. Their masses
        are kept and counted; everything else is re-measured. Passing it is what makes a second
        call to this function a *re-balance* rather than an undo.
    :return: the report block, including the case where the measured parts left the chassis
        too little and the vehicle's mass was raised to keep it above
        :data:`CHASSIS_MIN_FRACTION`.
    """
    fixed = fixed or {}
    measured = 0.0
    for part in prepared:
        if part is chassis:
            continue
        if not fixed.get(part.part_type_id, {}).get("massOverrideKg"):
            part.mass_kg = round(geometric_mass_kg(part, densities), 3)
        measured += part.mass_kg * max(1, len(part.instances))

    raised = False
    total = target_kg
    if measured > (1.0 - CHASSIS_MIN_FRACTION) * total:
        total = measured / (1.0 - CHASSIS_MIN_FRACTION)
        raised = True
    chassis.mass_kg = round(total - measured, 3)

    return {
        "targetKg": round(target_kg, 2),
        "totalKg": round(total, 2),
        "measuredPartsKg": round(measured, 2),
        "chassisKg": chassis.mass_kg,
        "chassisFraction": round(chassis.mass_kg / max(1e-6, total), 4),
        "targetRaised": raised,
        "note": (
            "the measured parts left the chassis below its minimum share, so the vehicle's "
            "mass was raised to keep it there"
            if raised
            else "the chassis takes the balance of the target mass"
        ),
    }


def total_mass_kg(prepared: list[PreparedPart]) -> float:
    return sum(part.mass_kg * max(1, len(part.instances)) for part in prepared)


def centre_of_mass(prepared: list[PreparedPart], placements: dict) -> tuple[float, float, float]:
    """The assembly's centre of mass, in chassis space (DEC-043: at each part's own centre).

    Weighed at the points the **runtime** will weigh them, which is the slot position plus the
    part's own centre in its own space — not at the geometry's position in the source model. The
    two are the same for every part except a wheel, whose slot is deliberately a suspension
    length above its axle (:func:`wheel_slot_lift`); computing the centre of mass from the
    geometry put it 22 mm low on both shipped cars and A311 correctly refused the assembly.

    Every *instance* is weighed where it actually sits: a wheel type used on both sides of an
    axle contributes at ``+x`` and at ``-x``, not twice at the canonical side's.

    :param placements: ``partTypeId -> [(slotId, position)]``, as :func:`slot_positions` returns
    """
    total = 0.0
    accumulated = [0.0, 0.0, 0.0]
    for part in prepared:
        centre = part.group.centre
        # Where this part's mass acts within its own mesh (DEC-043). Zero for anything whose
        # origin is already its centre, which is everything but a wheel.
        offset = tuple(centre[axis] - part.origin[axis] for axis in range(3))
        slots = [position for _slot, position in placements.get(part.part_type_id, [])]
        for instance in range(max(1, len(part.instances))):
            if instance < len(slots):
                placed = tuple(slots[instance][axis] + offset[axis] for axis in range(3))
            elif instance == 0:
                placed = centre
            elif instance - 1 < len(part.mirrored_groups):
                placed = part.mirrored_groups[instance - 1].centre
            else:
                placed = (-centre[0], centre[1], centre[2])
            total += part.mass_kg
            for axis in range(3):
                accumulated[axis] += part.mass_kg * placed[axis]
    if total <= 0.0:
        return (0.0, 0.0, 0.0)
    return tuple(value / total for value in accumulated)


# ---- Power ---------------------------------------------------------------------------------


def reference_power_cost(part: PreparedPart, max_hp: float, armor: float, engine_force: float):
    """D05-S5.7's reference formula, which A210 measures an authored cost against."""
    return max(
        0.0,
        0.010 * max_hp + 0.050 * armor + 0.300 * engine_force / 1000.0 - 0.004 * part.mass_kg,
    )


def distribute_power(prepared: list[PreparedPart], references: dict[str, float], budget: float):
    """Share the class's power budget in proportion to each part's reference cost (A312)."""
    total = sum(
        references[part.part_type_id] * max(1, len(part.instances)) for part in prepared
    )
    if total <= 0.0:
        share = budget / max(1, len(prepared))
        for part in prepared:
            part.power_cost = round(share, 3)
        return
    for part in prepared:
        part.power_cost = round(budget * references[part.part_type_id] / total, 3)


def vehicle_class_for(mass_kg: float) -> str:
    for boundary, name in CLASS_BOUNDARIES:
        if mass_kg < boundary:
            return name
    return HEAVIEST_CLASS


def load_class_targets(path: Path) -> dict[str, float]:
    """``assets/balance/classes.json`` — read, never duplicated (the D09-R18 argument)."""
    if not Path(path).is_file():
        raise ManifestError(f"balance table not found: {path}")
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    return {
        entry["classId"]: float(entry.get("powerBudgetTarget", 0.0))
        for entry in document.get("classes", [])
    }


def load_densities(path: Path) -> dict[str, float]:
    """``assets/materials/materials.json`` — the same file the game reads (D09-R18)."""
    if not Path(path).is_file():
        raise ManifestError(f"material table not found: {path}")
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    return {
        entry["materialId"]: float(entry["densityKgPerM3"])
        for entry in document.get("materials", [])
    }


# ---- The documents ---------------------------------------------------------------------------


def build_part_document(
    part: PreparedPart,
    chassis_slots: list[dict] | None,
    stats: dict | None,
    handling: dict | None,
    produced: dict | None = None,
    weapon: dict | None = None,
    light: dict | None = None,
) -> dict:
    """One ``part.json``, exactly as D08-S4.2 specifies it.

    ``produced`` is what the export stage actually managed for this part. When it is present
    it decides what the ``assets`` block claims, because a manifest that promises four morph
    targets a mesh does not carry is a file that passes this tool and fails the asset gate one
    layer further from its cause. When it is absent — a classification-only run — the plan is
    reported instead, which is the honest answer for a part nobody has exported yet.
    """
    treatment = treatment_for(part.label)
    has_morphs = treatment.morphs
    has_shards = bool(treatment.fracture_shards)
    if produced is not None:
        has_morphs = len(produced.get("morphTargets") or []) == 4
        has_shards = bool(produced.get("shards"))
    category = PART_CATEGORY[part.label]
    max_hp = round(max(MIN_MAX_HP, HP_PER_KG[part.destruction_class] * part.mass_kg), 1)
    armor = round(ARMOR_PER_KG.get(category, 0.0) * part.mass_kg, 2)
    break_impulse = round(
        max(
            MIN_BREAK_IMPULSE_NS,
            BREAK_IMPULSE_NS_PER_KG[part.destruction_class] * part.mass_kg,
        ),
        1,
    )

    assets = {
        "visualMesh": "mesh.glb",
        "collisionSource": f"mesh.glb#node={part.part_type_id}_col",
    }
    if has_morphs:
        assets["morphTargets"] = list(MORPH_LEVELS)
    if has_shards:
        assets["shardMesh"] = "shards.glb"
        assets["fractureManifest"] = "fracture_manifest.json"

    document = {
        "schemaVersion": SCHEMA_VERSION,
        "partTypeId": part.part_type_id,
        "displayName": display_name(part),
        "category": category,
        "massKg": round(part.mass_kg, 3),
        "maxHp": max_hp,
        "armorValue": armor,
        "materialId": part.material_id,
        "slotTypeRequired": SLOT_TYPE_REQUIRED[part.label],
        "sizeClass": PART_SIZE_CLASS.get(part.label, "MEDIUM"),
        "powerCost": part.power_cost,
        "breakImpulseN": break_impulse,
        "hangsBeforeFalling": part.label == GLASS,
        "destructionClass": part.destruction_class,
        "stats": stats or {},
        "slots": chassis_slots or [],
        "assets": assets,
        "tags": sorted({tag for tag in (part.label, part.role, "prepared") if tag}),
    }
    if handling:
        document["handling"] = handling
    if treatment.yield_impulse_ns:
        document["yieldImpulseN"] = treatment.yield_impulse_ns
    if light is not None:
        document["light"] = light
    if part.label == WEAPON and weapon is not None:
        # A built-in weapon: geometry the model came with, made able to fire (D15-R41). A
        # modular weapon needs none of this -- it is authored content in the shared library
        # and arrives with its own block.
        document["weapon"] = weapon
    if part.hinge is not None:
        # D15-R30: a hinge is data on the part, not an armature. The runtime animates the
        # slot's local rotation about this axis; nothing here is a second transform hierarchy.
        document["articulation"] = part.hinge.as_dict()
    return document


def display_name(part: PreparedPart) -> str:
    words = [part.label]
    if part.role:
        words.insert(0, part.role.replace("_", " "))
    if part.side == "l":
        words.append("(left)")
    elif part.side == "r":
        words.append("(right)")
    return " ".join(word.replace("_", " ").title() for word in words)


def chassis_stats(mass_kg: float) -> dict:
    """What a prepared vehicle's chassis contributes to the vehicle-wide stats (D05-S4.5)."""
    return {
        "engineForceN": {"add": round(ENGINE_FORCE_N_PER_KG * mass_kg, 1)},
        "enginePowerW": {"add": round(ENGINE_POWER_W_PER_KG * mass_kg, 1)},
        "brakeForceN": {"add": round(BRAKE_FORCE_N_PER_KG * mass_kg, 1)},
    }


def build_slot(part: PreparedPart, slot_id: str, position: tuple[float, float, float]) -> dict:
    """One entry in the chassis's ``slots`` array (D08-S4.2)."""
    return {
        "slotId": slot_id,
        "slotType": SLOT_TYPE_REQUIRED[part.label],
        "localPosition": {
            "x": round(position[0], 4),
            "y": round(position[1], 4),
            "z": round(position[2], 4),
        },
        "localRotationDeg": {"x": 0.0, "y": 0.0, "z": 0.0, "order": "XYZ"},
        "maxMassKg": round(max(1.0, part.mass_kg * 1.5), 1),
        "covers": [],
        "isDetachable": DETACHES[part.label],
    }


def hardpoint_slots(body, total_mass_kg: float) -> list[dict]:
    """The empty mounting points a prepared vehicle offers (D15-R42, :data:`HARDPOINTS`).

    Empty is the point. Nothing is placed on them by this pipeline and the assembly it writes
    references none of them — a hardpoint is a promise that a weapon or a module authored
    elsewhere has somewhere to go, and the vehicle drives perfectly well with all five unused.

    They are declared on the chassis, so the parts that fill them attach to the vehicle's root
    and are validated against ``SlotType.acceptsCategory`` like any other placement (D05-S5.1).
    """
    capacity = round(max(HARDPOINT_MIN_MASS_KG, total_mass_kg * HARDPOINT_MASS_FRACTION), 1)
    turret_capacity = round(max(HARDPOINT_MIN_MASS_KG, total_mass_kg * TURRET_MASS_FRACTION), 1)
    slots = []
    for slot_id, slot_type, fx, fy, fz, size_class in HARDPOINTS:
        rated = turret_capacity if slot_type == "TURRET_MOUNT" else capacity
        slots.append({
            "slotId": slot_id,
            "slotType": slot_type,
            "sizeClass": size_class,
            "localPosition": {
                "x": round(fx * body.half_width, 4),
                "y": round(body.ground_y + fy * body.height, 4),
                "z": round(body.lo[2] + fz * body.length, 4),
            },
            # A flank hardpoint faces outboard, so a weapon fitted there is rolled to lay its own
            # mount face against the bodywork (D17-R25). A weapon's body extends along +Y from its
            # mount face, and Rz(theta) sends +Y to (-sin theta, cos theta, 0) — so the right flank,
            # at +X, needs -90 and the left +90. The opposite pair reads perfectly plausibly and
            # buries the gun inside the door; the capture is what showed it.
            "localRotationDeg": {"x": 0.0, "y": 0.0, "z": FLANK_ROLL_DEG.get(slot_id, 0.0),
                                 "order": "XYZ"},
            "maxMassKg": rated,
            "covers": [],
            "isDetachable": True,
        })
    return slots


def light_block(part: PreparedPart, body) -> dict | None:
    """The ``light`` block for a part the taxonomy labelled ``light`` (D15-R51, D08-R6).

    Cosmetic in the strict sense of G6 — nothing in the simulation reads it — but it is *content*,
    so it is authored here beside everything else the part declares rather than guessed at by the
    renderer from a part's name.

    Two things are derived from the part rather than taken from :data:`LAMPS`. The **direction** is
    the vehicle's front (``+z``, the sense ``VehicleFactory`` builds wheels in) tilted down by the
    role's cut-off, and it is mirrored for a lamp on the rear of the car so a tail light glows
    backwards. The **origin** is the lamp's own outward face — its forward-most point along that
    direction — because a beam starting at the middle of the lens starts inside the bodywork.
    """
    role = part.role if part.role in LAMPS else DEFAULT_LAMP
    lamp = LAMPS[role]
    lo = tuple(min(shell.lo[i] for shell in part.group.shells) for i in range(3))
    hi = tuple(max(shell.hi[i] for shell in part.group.shells) for i in range(3))

    forward = 1.0 if role == "head" else -1.0
    tilt = math.radians(lamp["tiltDownDeg"])
    face = hi[2] if forward > 0 else lo[2]
    return {
        "colorRgb": dict(lamp["colorRgb"]),
        "intensity": lamp["intensity"],
        "coneOuterDeg": lamp["coneOuterDeg"],
        "coneInnerDeg": lamp["coneInnerDeg"],
        "rangeM": lamp["rangeM"],
        "castsBeam": lamp["castsBeam"],
        "directionLocal": {
            "x": 0.0,
            "y": round(-math.sin(tilt), 4),
            "z": round(forward * math.cos(tilt), 4),
        },
        "originLocal": {
            "x": round((lo[0] + hi[0]) * 0.5 - part.origin[0], 4),
            "y": round((lo[1] + hi[1]) * 0.5 - part.origin[1], 4),
            "z": round(face - part.origin[2], 4),
        },
    }


def weapon_block(part: PreparedPart, body) -> dict:
    """The ``weapon`` block for a part the taxonomy labelled ``weapon`` (D15-R41, D08-R5).

    A built-in weapon — a tank's main gun — is geometry that came with the model, so unlike a
    modular weapon there is no authored definition to read. Two things are derived from the
    geometry it is:

    **Which family it is**, from its aspect ratio. A part that is at least
    :data:`labels.BARREL_ASPECT_MIN` times as long as it is wide is a barrel and therefore a
    ``CANNON``; anything shorter is a mount or a pod and is an ``AUTOCANNON``. Those two are the
    only families the geometry can distinguish, and guessing between the other six would be
    inventing content rather than deriving it.

    **Where the muzzle is**, from the part's own forward extent: the far end of the barrel, on
    its centreline, in the part's local space. ``+z`` is the vehicle's front (the sense
    ``VehicleFactory`` builds wheels in), so the muzzle is at the part's ``hi[2]``.
    """
    del body
    lo = tuple(min(shell.lo[i] for shell in part.group.shells) for i in range(3))
    hi = tuple(max(shell.hi[i] for shell in part.group.shells) for i in range(3))
    length = max(1e-6, hi[2] - lo[2])
    width = max(1e-6, hi[0] - lo[0])
    height = max(1e-6, hi[1] - lo[1])
    aspect = length / max(width, height)
    family = "CANNON" if aspect >= BARREL_ASPECT_MIN else "AUTOCANNON"
    return {
        "family": family,
        "ammoCapacity": -1,
        "blastRadiusM": 0.0,
        "rangeM": 0.0,
        "muzzleLocal": {
            "x": round((lo[0] + hi[0]) * 0.5 - part.origin[0], 4),
            "y": round((lo[1] + hi[1]) * 0.5 - part.origin[1], 4),
            "z": round(hi[2] - part.origin[2], 4),
        },
    }


def slot_positions(
    part: PreparedPart,
    stiffness: float = DEFAULT_SUSPENSION_STIFFNESS,
    wheel_count: int = 4,
) -> list[tuple[str, tuple[float, float, float]]]:
    """Every slot this part type fills, with the position each instance sits at.

    A shared wheel type fills two, and the second sits at the axle *it* was measured at rather
    than at the reflection of the first — a real car's two front axles are a few millimetres
    apart, and a wheel placed at the wrong one of them rides at a visible angle.

    :param stiffness: the vehicle's suspension stiffness, and :param wheel_count: how many wheels
        it stands on. Both are properties of the whole vehicle rather than of this part, and both
        are needed for one reason: :func:`wheel_slot_lift`.
    """
    positions = [(part.instances[0], part.origin)] if part.instances else []
    for index, slot in enumerate(part.instances[1:]):
        if index < len(part.mirrored_origins):
            positions.append((slot, part.mirrored_origins[index]))
        else:
            positions.append((slot, (-part.origin[0], part.origin[1], part.origin[2])))
    if part.label == WHEEL:
        # Two wheels on one axle are symmetric about the centreline: that is what a track *is*.
        # Each corner is measured independently (DEC-066) and on real art the two answers differ
        # by millimetres, which is worth keeping. On the Stampede they differed by 6 cm, which is
        # not asymmetry but a corner whose shells were cut differently — and a car whose left
        # wheel is 6 cm further out than its right sits crooked and drives on two wheels.
        canonical = abs(positions[0][1][0]) if positions else 0.0
        mirrored = []
        for index, (slot, position) in enumerate(positions):
            x = position[0]
            if index and abs(abs(x) - canonical) > AXLE_SYMMETRY_TOLERANCE_M:
                x = canonical if x >= 0.0 else -canonical
            mirrored.append((slot, (x, position[1], position[2])))
        positions = mirrored

        lift = wheel_slot_lift(stiffness, wheel_count)
        positions = [
            (slot, (position[0], position[1] + lift, position[2])) for slot, position in positions
        ]
    return positions


def wheel_slot_lift(stiffness: float, wheel_count: int) -> float:
    """How far above the axle a wheel's slot goes, in metres.

    A wheel slot is the **suspension connection point**, not the axle: Bullet hangs the wheel
    ``suspensionRestLengthM`` below the point it is given. Emitting the axle itself would bury
    every wheel 30 cm into the ground.

    Emitting ``axle + restLength`` — which this did until the prepared content shipped — is
    wrong in the other direction, and subtly. That places the connection where the suspension is
    fully **extended**, and no car stands on fully extended suspension: it stands at its static
    ride height, one sag below. So the body settled 8 cm into the road, exactly the sag, and the
    model's own ground plane no longer meant anything.

    The sag is derivable and does not depend on the vehicle's mass. Bullet's suspension force is
    ``stiffness · compression · chassisMass``, and equilibrium is
    ``stiffness · sag · m = m · g / n``, so ``sag = g / (stiffness · n)``: 8.2 cm on four wheels
    at the reference stiffness of 30, which is what was measured.
    """
    if stiffness <= 0.0 or wheel_count <= 0:
        return DEFAULT_WHEEL_HANDLING["suspensionRestLengthM"]
    sag = GRAVITY_M_PER_S2 / (stiffness * wheel_count)
    return DEFAULT_WHEEL_HANDLING["suspensionRestLengthM"] - sag


def stats_for(part: PreparedPart, chassis_stats: dict, researched) -> dict:
    """The ``stats`` block one part carries, with any researched figures folded in (D15-S11).

    Steering is authored on the **front** wheel type only. Every wheel gets grip and springs,
    because every wheel has them; only a steering wheel contributes a steering lock, and D05-S5.6
    phase 3 filters on ``isSteering`` — so putting the lock on all four would double it on a car
    whose rear wheels were ever made to steer.
    """
    if part.is_chassis:
        return dict(chassis_stats)
    if part.label != WHEEL:
        return {}
    block = dict(researched.wheel_stats)
    if part.corner is not None and part.corner.startswith("f"):
        block.update(researched.steering_stats)
    return block


def handling_for(part: PreparedPart, researched) -> dict | None:
    """The ``handling`` block one part carries: the class default, then any researched figures."""
    if part.is_chassis:
        return {**DEFAULT_CHASSIS_HANDLING, **researched.chassis_handling}
    if part.label == WHEEL:
        return {**DEFAULT_WHEEL_HANDLING, **researched.wheel_handling}
    return None


def build_parts_manifest(
    vehicle_type_id: str,
    display: str,
    vehicle_class: str,
    prepared: list[PreparedPart],
    chassis: PreparedPart,
    assembly: dict,
    source: dict,
    hardpoints: list[dict] | None = None,
    produced: dict | None = None,
) -> dict:
    """``manifest.json`` — what this vehicle's parts are, and what each one is worth (D08-R14b).

    It sits **in the parts directory it describes**, beside the twenty-odd part directories
    rather than above them, because it is a description of that set and travels with it.

    It is not a second source of truth and nothing loads it. Every number in it is copied from
    the ``part.json`` written in the same run, and the asset gate validates those. What it is
    for is the question neither a directory listing nor twenty separate files answers: *what did
    the tool decide this vehicle is made of, and why*. A part's label, the cue that produced it,
    its mass and where that mass came from, whether it dents or shatters or does neither, how
    many triangles it cost, whether it hinges — one screen, in one file, sorted.

    That question gets asked every time a prepared vehicle is wrong, and before this file the
    only way to answer it was to re-run the pipeline and read the report from a build directory
    that may not exist any more.
    """
    produced = produced or {}
    rows = []
    for part in prepared:
        result = produced.get(part.part_type_id, {})
        treatment = treatment_for(part.label)
        category = PART_CATEGORY[part.label]
        rows.append({
            "partTypeId": part.part_type_id,
            "displayName": display_name(part),
            "category": category,
            "label": part.label,
            "role": part.role,
            "side": part.side,
            "slots": list(part.instances),
            "instances": max(1, len(part.instances)),
            "massKg": round(part.mass_kg, 3),
            "maxHp": round(max(MIN_MAX_HP, HP_PER_KG[part.destruction_class] * part.mass_kg), 1),
            "armorValue": round(ARMOR_PER_KG.get(category, 0.0) * part.mass_kg, 2),
            "breakImpulseN": round(
                max(MIN_BREAK_IMPULSE_NS,
                    BREAK_IMPULSE_NS_PER_KG[part.destruction_class] * part.mass_kg), 1),
            "powerCost": part.power_cost,
            "materialId": part.material_id,
            "destructionClass": part.destruction_class,
            "detachable": DETACHES[part.label],
            "hinged": part.hinge is not None,
            "geometry": {
                "triangles": part.group.triangles,
                "areaM2": round(sum(shell.area_m2 for shell in part.all_shells), 4),
                "enclosedM3": round(sum(shell.volume_m3 for shell in part.all_shells), 6),
                "shells": len(part.all_shells),
                "originM": {
                    "x": round(part.origin[0], 4),
                    "y": round(part.origin[1], 4),
                    "z": round(part.origin[2], 4),
                },
            },
            "destruction": {
                "morphTargets": list(result.get("morphTargets") or []),
                "shards": int(result.get("shards") or 0),
                "planned": treatment.as_dict(),
                "notes": list(result.get("notes") or []),
            },
        })

    rows.sort(key=lambda row: row["partTypeId"])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "vehicleTypeId": vehicle_type_id,
        "displayName": display,
        "vehicleClass": vehicle_class,
        "generatedBy": "syndicate-prepare (D15-S5.1)",
        "source": source,
        "totals": {
            "partTypes": len(rows),
            "placedParts": sum(row["instances"] for row in rows),
            "totalMassKg": round(total_mass_kg(prepared), 2),
            "chassisMassKg": round(chassis.mass_kg, 3),
            "powerBudget": assembly.get("expected", {}).get("powerBudget", 0.0),
            "triangles": sum(row["geometry"]["triangles"] for row in rows),
            "hingedParts": sum(1 for row in rows if row["hinged"]),
            "fracturedParts": sum(1 for row in rows if row["destruction"]["shards"]),
            "deformableParts": sum(1 for row in rows if row["destruction"]["morphTargets"]),
        },
        "hardpoints": [
            {
                "slotId": slot["slotId"],
                "slotType": slot["slotType"],
                "localPosition": slot["localPosition"],
                "maxMassKg": slot["maxMassKg"],
            }
            for slot in sorted(hardpoints or [], key=lambda slot: slot["slotId"])
        ],
        "parts": rows,
    }


def build_assembly_document(
    vehicle: str,
    display: str,
    prepared: list[PreparedPart],
    chassis: PreparedPart,
    vehicle_class: str,
    placements: dict | None = None,
) -> dict:
    """The ``assembly.json`` of D08-S4.4 (DEC-019: the chassis is a field, not a row).

    :param placements: the slot positions the chassis was authored with, so the declared centre
        of mass is computed at the same points (see :func:`centre_of_mass`). Omitted only by a
        test that does not care where the parts ended up.
    """
    placements = placements if placements is not None else {
        part.part_type_id: slot_positions(part) for part in prepared
    }
    rows = []
    for part in prepared:
        if part is chassis:
            continue
        for slot, _position in placements.get(part.part_type_id, []):
            row = {
                "slotPath": f"root/{slot}",
                "parentSlotPath": "root",
                "parentSlotId": slot,
                "partTypeId": part.part_type_id,
            }
            if part.label == WHEEL:
                # Front wheels steer, rear wheels drive. A prepared vehicle is rear-driven
                # because that is the arrangement whose failure mode — power oversteer — is
                # the one the physics already models; nothing about the art says which it is.
                front = part.corner is not None and part.corner.startswith("f")
                row["overrides"] = {"isSteering": front, "isDriven": not front}
            rows.append(row)

    total = total_mass_kg(prepared)
    com = centre_of_mass(prepared, placements)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "vehicleTypeId": f"vehicle_{vehicle}_01",
        "displayName": display,
        "vehicleClass": vehicle_class,
        "unlockLevel": 0,
        "chassis": chassis.part_type_id,
        "parts": sorted(rows, key=lambda row: row["slotPath"]),
        "cosmetics": {"paintSchemeId": "scheme_default_01"},
        "expected": {
            "totalMassKg": round(total, 2),
            "powerBudget": round(
                sum(part.power_cost * max(1, len(part.instances)) for part in prepared), 2
            ),
            "comLocal": {
                "x": round(com[0], 4),
                "y": round(com[1], 4),
                "z": round(com[2], 4),
            },
        },
    }
