"""Stage 5: shells sharing a label become one sub-part (D17-S5.7).

Two rules make this more than a ``groupby``. A **mirrored pair** becomes two sub-parts distinguished
by side, because a gun carriage's left and right trunnion cheeks are two things that can be shot off
independently. A **rotational set** becomes one sub-part containing every member, because a ring of
gear teeth is one gear.

The third rule is D17-R41, and it is the one that makes this pipeline work on real gun models at
all: most of them are modelled *without* whatever they bolt to, so there is no ``mount`` to find and
one is synthesised.

Nothing here touches Blender.
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass, field

from .labels import (
    AREAL_DENSITY_KG_PER_M2,
    DEFAULT_MATERIAL,
    DESTRUCTION_CLASS,
    FURNITURE,
    MAX_SUBPARTS_PER_WEAPON,
    MOUNT,
    PART_CATEGORY,
    RECEIVER,
    SIDED_LABELS,
    SLOT_TYPE_REQUIRED,
    UNCLASSIFIED,
)


@dataclass
class SubPart:
    """One sub-part of a weapon: a label, the shells that make it, and what it weighs.

    :param name: the label plus a side suffix where it has one — ``barrel``, ``mount_l``
    :param part_type_id: the asset id it exports as, ``<weaponId>_<name>``
    :param shells: the shell indices joined into it
    :param origin: where its own origin sits, set by the seam rule in :mod:`syndicate_weapon.graph`
    :param synthesised: true for a mount this pipeline invented rather than found (D17-R41)
    """

    label: str
    name: str
    shells: list = field(default_factory=list)
    part_type_id: str = ""
    lo: tuple = (0.0, 0.0, 0.0)
    hi: tuple = (0.0, 0.0, 0.0)
    centroid: tuple = (0.0, 0.0, 0.0)
    area_m2: float = 0.0
    volume_m3: float = 0.0
    triangles: int = 0
    mass_kg: float = 0.0
    origin: tuple = (0.0, 0.0, 0.0)
    synthesised: bool = False
    notes: list = field(default_factory=list)

    @property
    def size(self) -> tuple:
        return tuple(self.hi[i] - self.lo[i] for i in range(3))

    @property
    def category(self) -> str:
        return PART_CATEGORY[self.label]

    @property
    def slot_type_required(self) -> str:
        return SLOT_TYPE_REQUIRED[self.label]

    @property
    def destruction_class(self) -> str:
        return DESTRUCTION_CLASS[self.label]

    @property
    def material_id(self) -> str:
        return DEFAULT_MATERIAL[self.label]

    def as_dict(self) -> dict:
        return {
            "partTypeId": self.part_type_id,
            "label": self.label,
            "name": self.name,
            "shells": len(self.shells),
            "triangles": self.triangles,
            "massKg": round(self.mass_kg, 4),
            "origin": {"x": round(self.origin[0], 5), "y": round(self.origin[1], 5),
                       "z": round(self.origin[2], 5)},
            "synthesised": self.synthesised,
            "notes": self.notes,
        }


def group(shells, bore, weapon_id: str) -> list[SubPart]:
    """Turns labelled shells into sub-parts (D17-R39, D17-R3, D17-R4).

    Order of operations matters: ``unclassified`` merges into the receiver *first*, so that a model
    whose receiver the ensemble could not name still ends up with one; the mount is resolved
    *after*, so that a promoted second mount (D17-E7) is decided against the final set.
    """
    by_label: dict[str, list] = defaultdict(list)
    for shell in shells:
        label = RECEIVER if shell.label == UNCLASSIFIED else shell.label
        by_label[label].append(shell)

    parts: list[SubPart] = []
    for label in sorted(by_label):
        members = by_label[label]
        for name, group_shells in _split_sides(label, members, bore):
            parts.append(_build(label, name, group_shells))

    parts = _resolve_mount(parts, bore)
    parts.sort(key=lambda p: (p.label, p.name))
    for part in parts:
        part.part_type_id = f"{weapon_id}_{part.name}"
    return parts


def _split_sides(label, members, bore):
    """A mirrored pair becomes two sub-parts by side; everything else stays one (D17-R39).

    Side is decided on the sign of the offset perpendicular to the bore, projected onto the model's
    own left-right axis. A pair whose members straddle that plane splits; a group that all sits on
    one side does not, because it is not a pair — it is one thing that happens to be off-centre.

    Only :data:`SIDED_LABELS` may split at all. A gun has one receiver, one breech and one bore, and
    splitting those by side produced a ``receiver_l`` and a ``receiver_r`` on the shipped machine
    gun — which is not a weapon, and which no amount of tolerance tuning would have fixed because
    the geometry really is on both sides of the centreline.
    """
    if len(members) < 2 or label not in SIDED_LABELS:
        return [(label, members)]
    offsets = {s.index: _side_offset(s.centroid, bore) for s in members}
    left = [s for s in members if offsets[s.index] < -1e-4]
    right = [s for s in members if offsets[s.index] > 1e-4]
    if not left or not right:
        return [(label, members)]
    centre = [s for s in members if abs(offsets[s.index]) <= 1e-4]
    # Anything on the centreline joins the larger side rather than becoming a third part.
    if centre:
        (left if len(left) >= len(right) else right).extend(centre)
    return [(f"{label}_l", left), (f"{label}_r", right)]


def _side_offset(point, bore) -> float:
    """Signed distance from the plane containing the bore axis and world up."""
    axis = bore.axis
    up = (0.0, 1.0, 0.0) if abs(axis[1]) < 0.9 else (0.0, 0.0, 1.0)
    right = _normalise(_cross(up, axis))
    delta = tuple(point[i] - bore.origin[i] for i in range(3))
    return sum(delta[i] * right[i] for i in range(3))


def _build(label: str, name: str, shells) -> SubPart:
    part = SubPart(label=label, name=name, shells=[s.index for s in shells])
    part.lo = tuple(min(s.lo[i] for s in shells) for i in range(3))
    part.hi = tuple(max(s.hi[i] for s in shells) for i in range(3))
    part.triangles = sum(s.triangles for s in shells)
    part.area_m2 = sum(s.area_m2 for s in shells)
    part.volume_m3 = sum(s.volume_m3 for s in shells)
    total = float(sum(s.triangles for s in shells)) or 1.0
    part.centroid = tuple(
        sum(s.centroid[i] * s.triangles for s in shells) / total for i in range(3)
    )
    part.mass_kg = mass_of(part)
    return part


def mass_of(part: SubPart) -> float:
    """Surface area times a per-label areal density, capped by enclosed volume (D17-R52, DEC-067).

    A gun is not a shell, which is the one place this departs from the vehicle pipeline's numbers:
    a barrel is a solid tube of steel and a car door is a 1 mm skin, so the densities in
    :data:`labels.AREAL_DENSITY_KG_PER_M2` are an order of magnitude higher. The volume cap is
    DEC-067's and is what stops a crumpled high-area shell from weighing more than the solid block
    it would fit inside.
    """
    areal = AREAL_DENSITY_KG_PER_M2[part.label]
    mass = part.area_m2 * areal
    # Steel at 7850 kg/m^3, against the *bounding box* rather than the mesh volume: a hollow model
    # has no usable enclosed volume, and the box is the honest upper bound on what could be there.
    box = max(1e-9, part.size[0] * part.size[1] * part.size[2])
    cap = box * 7850.0
    if mass > cap:
        part.notes.append(f"mass capped by enclosing volume: {mass:.1f} kg -> {cap:.1f} kg")
        return cap
    return mass


def _resolve_mount(parts: list[SubPart], bore) -> list[SubPart]:
    """Exactly one mount, promoted or synthesised (D17-R4, D17-E7, D17-E8, D17-R41)."""
    mounts = [p for p in parts if p.label == MOUNT]
    if len(mounts) > 1:
        # D17-E7: the larger is the mount; the rest become furniture, which is what a second
        # mount-shaped lump on a gun almost always is — a shield bracket or a carry handle.
        mounts.sort(key=lambda p: p.area_m2, reverse=True)
        for extra in mounts[1:]:
            extra.label = FURNITURE
            extra.name = extra.name.replace(MOUNT, FURNITURE, 1)
            extra.mass_kg = mass_of(extra)
            extra.notes.append("relabelled from mount: a weapon has exactly one (D17-R4)")
        return parts
    if mounts:
        return parts
    return [*parts, _synthesise_mount(parts, bore)]


def _synthesise_mount(parts, bore) -> SubPart:
    """A mounting boss under the receiver's rear underside (D17-R41).

    Most gun models are modelled without whatever they bolt to, so this is the common path rather
    than the exceptional one. It is generated as **real geometry** later, by the Blender stage, so
    that it renders, collides and fractures like every other part — a mount that existed only in
    ``part.json`` would be a weapon that floats.
    """
    anchor = next((p for p in parts if p.label == RECEIVER), None) or (parts[0] if parts else None)
    if anchor is None:
        lo = hi = (0.0, 0.0, 0.0)
        centroid = (0.0, 0.0, 0.0)
        width = depth = 0.1
    else:
        width = max(0.04, anchor.size[0] * 0.9)
        depth = max(0.04, anchor.size[2] * 0.45)
        height = max(0.03, anchor.size[1] * 0.6)
        centre_x = (anchor.lo[0] + anchor.hi[0]) * 0.5
        # Behind the anchor's middle along the bore, and below it: where a pintle or a cradle goes.
        back = bore.coordinate_of(anchor.centroid) - depth * 0.2
        centre = tuple(bore.origin[i] + back * bore.axis[i] for i in range(3))
        centroid = (centre_x, anchor.lo[1] - height * 0.5, centre[2])
        lo = (centre_x - width / 2, anchor.lo[1] - height, centroid[2] - depth / 2)
        hi = (centre_x + width / 2, anchor.lo[1], centroid[2] + depth / 2)

    part = SubPart(label=MOUNT, name=MOUNT, shells=[], synthesised=True)
    part.lo, part.hi, part.centroid = lo, hi, centroid
    part.triangles = 12
    size = part.size
    part.area_m2 = 2.0 * (size[0] * size[1] + size[1] * size[2] + size[0] * size[2])
    part.volume_m3 = size[0] * size[1] * size[2]
    part.mass_kg = mass_of(part)
    part.notes.append("synthesised: the source model carries no mount (D17-R41)")
    return part


def check_count(parts) -> None:
    """D17-R40: more sub-parts than the mount can offer slots for is a taxonomy failure to read."""
    if len(parts) > MAX_SUBPARTS_PER_WEAPON:
        raise ValueError(
            f"{len(parts)} sub-parts exceeds MAX_SUBPARTS_PER_WEAPON ({MAX_SUBPARTS_PER_WEAPON}); "
            "this is a taxonomy failure to be read rather than a file to be trimmed (D17-R40)"
        )


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _normalise(v):
    length = math.sqrt(sum(c * c for c in v))
    if length < 1e-12:
        return (1.0, 0.0, 0.0)
    return (v[0] / length, v[1] / length, v[2] / length)
