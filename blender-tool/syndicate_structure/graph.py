"""The slot graph, which for a structure *is* its support chain (D16-S7.2).

Every part above band 0 hangs off the part below it that actually carries it, and "actually
carries it" is decided by footprint overlap: the parent is the one whose shadow the child stands
on most. That single rule is what makes D07-S5.7's existing detachment trigger — a part whose
parent is gone or destroyed — collapse a structure correctly, with no collapse code anywhere.

Naming is derived and stable. A part's id is ``struct_<name>_<role>_01``, where the role is
``base`` for band 0 and ``tier<n>`` above it, with a letter suffix where a band holds more than
one part. Derived rather than authored so that re-cutting a model produces the same ids, and
suffixed left-to-right so that ``_a`` is always the same side of the structure between runs (G3).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .bands import Component

#: Suffixes for the second and later parts in one band. Runs out at four, which is
#: :data:`~syndicate_structure.bands.MAX_COMPONENTS_PER_BAND`.
SUFFIXES = ("a", "b", "c", "d")


@dataclass
class PartPlan:
    """One part of a structure, before anything has been exported.

    :param part_type_id: the asset id, ``^[a-z][a-z0-9_]{2,63}$`` (D00-R21)
    :param role: the derived role name, which is what a ``parts.json`` override keys on
    :param band: which band it came from, 0 at the ground
    :param component: the source geometry it is made of
    :param parent_id: the part carrying it, or ``None`` for the root
    :param slot_id: the slot on the parent it occupies, empty for the root
    :param origin: its own origin in game space — its footprint centre, at its lowest point
    """

    part_type_id: str
    role: str
    band: int
    component: Component
    parent_id: str | None = None
    slot_id: str = ""
    origin: tuple[float, float, float] = (0.0, 0.0, 0.0)
    material_id: str = "concrete"
    destruction_class: str = "STRUCTURAL"
    mass_kg: float = 0.0
    triangles: int = 0
    weapon: dict | None = None
    notes: list[str] = field(default_factory=list)


def role_names(bands: list[list[Component]]) -> list[list[str]]:
    """The role name for every part, band by band."""
    out = []
    for index, band in enumerate(bands):
        stem = "base" if index == 0 else f"tier{index}"
        if len(band) == 1:
            out.append([stem])
        else:
            out.append([f"{stem}_{SUFFIXES[i]}" for i in range(len(band))])
    return out


Box = tuple[float, float, float, float]


def _overlap_area(a: Box, b: Box) -> float:
    width = min(a[2], b[2]) - max(a[0], b[0])
    depth = min(a[3], b[3]) - max(a[1], b[1])
    return max(0.0, width) * max(0.0, depth)


def _centre(box: Box) -> tuple[float, float]:
    return ((box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0)


def choose_parent(child: Component, candidates: list[PartPlan]) -> PartPlan:
    """The part below that carries this one: most footprint overlap, nearest centre to break ties.

    The tie-break is not decoration. A turret's yoke sits centred over a turntable that is one
    part, so the overlap decides it; but the two pods above the yoke overlap it identically, and
    without a second criterion which pod got which parent would depend on dictionary order.
    """
    box = child.footprint
    best = None
    best_key = None
    cx, cz = _centre(box)
    for candidate in candidates:
        overlap = _overlap_area(box, candidate.component.footprint)
        px, pz = _centre(candidate.component.footprint)
        distance = (px - cx) ** 2 + (pz - cz) ** 2
        key = (-overlap, distance, candidate.part_type_id)
        if best_key is None or key < best_key:
            best, best_key = candidate, key
    return best


def part_origin(component: Component) -> tuple[float, float, float]:
    """A part's origin: the centre of its footprint, at its lowest point.

    D08-R2 wants the origin at the attachment point, and for a part that stands on another the
    attachment point is where it lands. Putting it at the centroid instead would make every slot
    transform carry half the part's height and make no diff between two cuts readable.
    """
    x, z = _centre(component.footprint)
    return (x, component.lowest_y, z)


def plan(bands: list[list[Component]], structure_name: str) -> list[PartPlan]:
    """Name every part, then hang each on the one that carries it.

    Returned bottom-up, and within a band left-to-right, which is the order everything
    downstream iterates in (G3).
    """
    names = role_names(bands)
    plans: list[PartPlan] = []
    previous: list[PartPlan] = []
    for index, band in enumerate(bands):
        current: list[PartPlan] = []
        for position, component in enumerate(band):
            role = names[index][position]
            part = PartPlan(
                part_type_id=f"struct_{structure_name}_{role}_01",
                role=role,
                band=index,
                component=component,
                origin=part_origin(component),
            )
            if previous:
                parent = choose_parent(component, previous)
                part.parent_id = parent.part_type_id
                part.slot_id = role
            current.append(part)
        plans.extend(current)
        previous = current
    return plans


def slot_local(parent: PartPlan, child: PartPlan) -> tuple[float, float, float]:
    """Where the child's slot sits in the parent's local frame."""
    return (
        child.origin[0] - parent.origin[0],
        child.origin[1] - parent.origin[1],
        child.origin[2] - parent.origin[2],
    )


def slot_path_of(plans: list[PartPlan], part: PartPlan) -> str:
    """The full slot path from the root, as ``assembly.json`` records it (D08-S4.4)."""
    by_id = {p.part_type_id: p for p in plans}
    chain = []
    walk = part
    while walk.parent_id is not None:
        chain.append(walk.slot_id)
        walk = by_id[walk.parent_id]
    return "/".join(["root", *reversed(chain)])
