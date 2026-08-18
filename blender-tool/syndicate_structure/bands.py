"""Cutting a structure into the parts whose slot graph is its support chain (D16-S7.1).

Pure geometry over measured pieces: no ``bpy`` here, so the cut is unit-testable without a
Blender host, which matters because the cut is the only decision in this package that is not
somebody else's code.

Two passes, in this order and not the other one:

1. **Bands along Y.** A structure's support chain is vertical, so the chain is bands and each
   band's parent is the band below it.
2. **Components within a band.** Two things standing side by side at the same height are two
   parts — the left pod and the right pod of a turret, a gantry's two legs — because either can
   be shot away while the other holds. Within a band that is one lump, there is one part.

Doing it the other way round (components first, then split each by height) finds the same parts
on a turret and the wrong ones on a building, where every floor is connected to the one above it
and the whole block is a single component.
"""

from __future__ import annotations

from dataclasses import dataclass, field

#: Metres of structure per band, before rounding. Chosen so that the shapes this project has —
#: a 31 m tower, a 17 m and a 22 m block, a 2 m tree — land on 5, 3, 4 and 1 bands respectively,
#: which is the granularity at which each one's collapse reads as a collapse rather than as a
#: single object vanishing or as a pile of confetti.
BAND_TARGET_M = 6.0

#: Never more than this many bands, whatever the height. Every band is an entity, a body, a
#: network id and a directory; a structure is cover to fight around, not a Jenga tower.
MAX_BANDS = 6

#: Never more than this many parts standing side by side in one band, for the same reason.
MAX_COMPONENTS_PER_BAND = 4

#: Two pieces belong to the same component if their footprints come within this of each other,
#: in metres. Not zero: adjacent pieces of one authored object frequently share an edge to
#: within float noise and just as frequently miss by a millimetre.
COMPONENT_GAP_M = 0.05

#: A component holding less than this share of its band's surface is not a part; it is detail,
#: and it is merged into the neighbour it is nearest. Without it a turret's cable runs and
#: handrails each become a part with a body, a hull and a health bar.
MIN_COMPONENT_AREA_FRACTION = 0.06


@dataclass(frozen=True)
class Piece:
    """One source object, measured in game space (Y up, metres).

    :param name: the object's name in the source model, kept for the report and for overrides
    :param lo: the lower corner of its axis-aligned bounds
    :param hi: the upper corner
    :param triangles: its triangle count, for the D08-R2 budget
    :param area_m2: its surface area, which is what its mass is computed from (DEC-067)
    :param volume_m3: the volume it encloses, which caps that mass
    :param material: the source material name, before the style pass renames anything
    """

    name: str
    lo: tuple[float, float, float]
    hi: tuple[float, float, float]
    triangles: int
    area_m2: float
    volume_m3: float
    material: str

    @property
    def centre_y(self) -> float:
        return (self.lo[1] + self.hi[1]) / 2.0

    @property
    def footprint(self) -> tuple[float, float, float, float]:
        """``(minX, minZ, maxX, maxZ)`` — the shadow this piece casts on the ground."""
        return (self.lo[0], self.lo[2], self.hi[0], self.hi[2])


@dataclass(eq=False)
class Component:
    """A set of pieces that will become one part."""

    pieces: list[Piece] = field(default_factory=list)

    @property
    def area_m2(self) -> float:
        return sum(p.area_m2 for p in self.pieces)

    @property
    def footprint(self) -> tuple[float, float, float, float]:
        return (
            min(p.lo[0] for p in self.pieces),
            min(p.lo[2] for p in self.pieces),
            max(p.hi[0] for p in self.pieces),
            max(p.hi[2] for p in self.pieces),
        )

    @property
    def lowest_y(self) -> float:
        return min(p.lo[1] for p in self.pieces)


def band_count(height_m: float, target_m: float = BAND_TARGET_M, cap: int = MAX_BANDS) -> int:
    """How many bands a structure of this height is cut into."""
    if height_m <= 0.0:
        return 1
    return max(1, min(cap, round(height_m / target_m)))


def band_edges(lowest_y: float, highest_y: float, count: int) -> list[float]:
    """The ``count + 1`` Y values bounding equal bands, lowest first."""
    span = highest_y - lowest_y
    return [lowest_y + span * i / count for i in range(count + 1)]


def assign_bands(pieces: list[Piece], edges: list[float]) -> list[list[Piece]]:
    """Each piece into the band its **centroid** falls in.

    Centroid rather than either extreme: a piece spanning a boundary has to go somewhere, and
    the half it has more of is the half it belongs to. A structure's tall members — a turret's
    legs, a lift shaft — are exactly the pieces this decides, and putting them in the band they
    mostly occupy is what keeps a leg with the base it is part of.
    """
    banded: list[list[Piece]] = [[] for _ in range(len(edges) - 1)]
    for piece in pieces:
        index = 0
        for i in range(len(edges) - 1):
            if piece.centre_y >= edges[i]:
                index = i
        banded[index].append(piece)
    return banded


Box = tuple[float, float, float, float]


def _overlaps(a: Box, b: Box, gap: float) -> bool:
    return (
        a[0] - gap <= b[2]
        and b[0] - gap <= a[2]
        and a[1] - gap <= b[3]
        and b[1] - gap <= a[3]
    )


def components(pieces: list[Piece], gap_m: float = COMPONENT_GAP_M) -> list[Component]:
    """Single-link clustering of a band's pieces on their footprints.

    Footprints rather than meshes: a real connectivity test would need the geometry, and two
    pieces of a structure that stand over the same ground are load-bearing on each other whether
    or not their triangles touch. The failure this avoids is the opposite one — a turret's four
    legs, which share no vertex, coming out as one part because they are all "the base".

    Deterministic: pieces are processed in the order given, and callers sort them (G3).
    """
    remaining = list(pieces)
    out: list[Component] = []
    while remaining:
        seed = remaining.pop(0)
        group = Component([seed])
        box = list(seed.footprint)
        grew = True
        while grew:
            grew = False
            for piece in list(remaining):
                if _overlaps(tuple(box), piece.footprint, gap_m):
                    remaining.remove(piece)
                    group.pieces.append(piece)
                    fp = piece.footprint
                    box = [
                        min(box[0], fp[0]), min(box[1], fp[1]),
                        max(box[2], fp[2]), max(box[3], fp[3]),
                    ]
                    grew = True
        out.append(group)
    return out


def _centre(box: Box) -> tuple[float, float]:
    return ((box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0)


def _nearest(target: Component, others: list[Component]) -> Component:
    tx, tz = _centre(target.footprint)
    def distance(component: Component) -> float:
        cx, cz = _centre(component.footprint)
        return (cx - tx) ** 2 + (cz - tz) ** 2

    return min(others, key=distance)


def merge_detail(
    found: list[Component],
    min_area_fraction: float = MIN_COMPONENT_AREA_FRACTION,
    cap: int = MAX_COMPONENTS_PER_BAND,
) -> list[Component]:
    """Fold detail and overflow into the neighbouring part, largest-first.

    Two rules, applied in that order: a component below ``min_area_fraction`` of the band is
    detail, and once the detail is gone anything past ``cap`` is still too many. Both merge into
    the *nearest* surviving component rather than the largest, because a handrail belongs to the
    thing it is bolted to and not to whatever happens to be heaviest.
    """
    if len(found) <= 1:
        return found
    ordered = sorted(found, key=lambda c: -c.area_m2)
    total = sum(c.area_m2 for c in ordered)
    keep = [c for c in ordered if total <= 0.0 or c.area_m2 / total >= min_area_fraction]
    if not keep:
        keep = ordered[:1]
    # Identity, not equality: `Component` is a dataclass, so two components holding equal piece
    # lists compare equal and `in` would drop the wrong one.
    kept_ids = {id(c) for c in keep}
    for orphan in [c for c in ordered if id(c) not in kept_ids]:
        _nearest(orphan, keep).pieces.extend(orphan.pieces)
    while len(keep) > cap:
        smallest = min(keep, key=lambda c: c.area_m2)
        keep.remove(smallest)
        _nearest(smallest, keep).pieces.extend(smallest.pieces)
    return sorted(keep, key=lambda c: _centre(c.footprint))


def edges_for(
    pieces: list[Piece], target_m: float = BAND_TARGET_M, cap: int = MAX_BANDS
) -> list[float]:
    """The band planes for a set of pieces, from their overall height.

    Separate from :func:`cut` because the geometry is bisected at these planes before it is
    sorted into them (:mod:`~syndicate_structure.split`), so the planes have to be known one
    stage earlier than the sort that uses them.
    """
    if not pieces:
        return [0.0, 0.0]
    lowest = min(p.lo[1] for p in pieces)
    highest = max(p.hi[1] for p in pieces)
    return band_edges(lowest, highest, band_count(highest - lowest, target_m, cap))


def cut(pieces: list[Piece], edges: list[float]) -> list[list[Component]]:
    """The whole cut: bands bottom-up, each band's parts left-to-right.

    Band 0 is always **one** component, whatever the clustering found. A structure has exactly one
    root (D16-R18) and it is what holds the rest off the ground; four separate legs, none of which
    holds up anything on its own, is not a support chain but four structures.
    """
    if not pieces:
        return []
    ordered = sorted(pieces, key=lambda p: (p.lo[1], p.lo[0], p.lo[2], p.name))
    out: list[list[Component]] = []
    for index, band in enumerate(assign_bands(ordered, edges)):
        if not band:
            continue
        found = merge_detail(components(band))
        if index == 0 and len(found) > 1:
            root = Component([p for c in found for p in c.pieces])
            found = [root]
        out.append(found)
    return out
