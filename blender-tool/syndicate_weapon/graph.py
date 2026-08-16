"""Stage 6: the weapon's slot graph, and the seam rule that stops the joins showing (D17-S5.8).

The graph itself is small — D17-R42's tree, resolved against whichever sub-parts a model actually
yielded. The seam rule is the substance of this module and the whole of the answer to "their
attachment should be a smooth transition with no sloppy seams".

**A slot goes at the centroid of the contact region between parent and child** (D17-R44), not at
either part's centroid and not at the midpoint of their bounding boxes. Two parts that touch along a
ring join at the ring's centre. Using either centroid puts the join wherever the geometry happens to
be heaviest, which is how a barrel ends up inserted a centimetre into its own receiver or floating a
centimetre out of it — and both of those are exactly what a sloppy seam looks like.

Nothing here touches Blender: it works on sampled vertices, which the measurement stage already
carries on every :class:`syndicate_prepare.shell.Shell`.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .labels import MOUNT, PARENT_LABEL, SEAM_CONTACT_REACH, SEAM_REACH_PASSES


@dataclass
class Seam:
    """One parent-child join, and whether it was found from real contact.

    :param gap_m: the nearest approach between the two parts. Zero where they touch or overlap,
    which
        is the normal case; non-zero is a modelled clearance and is reported rather than corrected
    :param contact_points: how many parent vertices lay within reach of the child. **Zero means no
        contact was found at any reach**, which is the one condition that fails the check: a join
        placed between two parts that do not meet is a guess
    :param reach_used: the reach multiplier the contact was found at, so a join found at the widest
        pass reads as the weak join it is
    """

    parent: str
    child: str
    position: tuple
    gap_m: float
    contact_points: int
    reach_used: float = 0.0
    note: str = ""

    @property
    def is_closed(self) -> bool:
        """True when the join sits on contact the source model actually has (D17-R44)."""
        return self.contact_points >= 3

    def as_dict(self) -> dict:
        return {
            "parent": self.parent,
            "child": self.child,
            "position": {"x": round(self.position[0], 5), "y": round(self.position[1], 5),
                         "z": round(self.position[2], 5)},
            "gapM": round(self.gap_m, 6),
            "contactPoints": self.contact_points,
            "reachUsed": round(self.reach_used, 5),
            "onContact": self.is_closed,
            "note": self.note,
        }


@dataclass
class Node:
    """One sub-part's place in the tree."""

    part: object
    parent: object | None
    slot_id: str
    slot_path: str


def build(parts, vertices_by_shell, bore_length_m: float = 1.0) -> tuple[list[Node], list[Seam]]:
    """Parents every sub-part, positions its slot on the contact region, and re-origins it.

    ``vertices_by_shell`` maps a shell index to its sampled vertices in the model's current frame.
    A synthesised mount has no shells and falls back to its own bounds, which is correct: it was
    generated to sit against the receiver, so its contact region is its own top face.

    **Parenting follows support** (D17-R43), and this is where that stops being a slogan. The
    taxonomy proposes a parent — a muzzle goes on a barrel — and if the two do not actually touch,
    the child is re-parented to the nearest sub-part it *does* touch. A model whose sight floats
    above its receiver on a rail that the ensemble called furniture should hang the sight off the
    furniture, because that is what holds it up and therefore what takes it away when it is shot
    off.
    """
    root = _root(parts)
    nodes: list[Node] = [Node(part=root, parent=None, slot_id="", slot_path="")]
    seams: list[Seam] = []
    placed = {id(root)}

    # Breadth-first from the root, so a child is always positioned after its parent has been —
    # which matters because a slot is expressed in the parent's frame.
    frontier = [root]
    while frontier:
        parent = frontier.pop(0)
        for child in _children_of(parent, parts, placed):
            seam = measure_seam(parent, child, vertices_by_shell, bore_length_m)
            slot_id = _slot_id(child)
            parent_node = next(n for n in nodes if n.part is parent)
            path = f"{parent_node.slot_path}/{slot_id}" if parent_node.slot_path else slot_id
            nodes.append(Node(part=child, parent=parent, slot_id=slot_id, slot_path=path))
            seams.append(seam)
            # D17-R44.2: the child's origin *is* the join point, so its rotation is about the join
            # and not about wherever its geometry happened to be centred. Articulation makes an
            # origin in the wrong place visible immediately.
            child.origin = seam.position
            placed.add(id(child))
            frontier.append(child)

    # Anything the taxonomy walk could not place — its proposed parent is absent from this model —
    # is attached to whichever placed part it touches most closely (D17-R43). Without this a model
    # with a muzzle and no barrel simply loses its muzzle.
    for orphan in sorted((p for p in parts if id(p) not in placed), key=lambda p: p.name):
        host, seam = _nearest_touching(orphan, [n.part for n in nodes], vertices_by_shell,
                                      bore_length_m)
        if host is None:
            continue
        host_node = next(n for n in nodes if n.part is host)
        slot_id = _slot_id(orphan)
        path = f"{host_node.slot_path}/{slot_id}" if host_node.slot_path else slot_id
        seam.note = (seam.note + "; " if seam.note else "") + (
            f"re-parented onto {host.name}: the taxonomy's {PARENT_LABEL.get(orphan.label)} is "
            "absent from this model")
        nodes.append(Node(part=orphan, parent=host, slot_id=slot_id, slot_path=path))
        seams.append(seam)
        orphan.origin = seam.position
        placed.add(id(orphan))

    root.origin = _mount_face(root, vertices_by_shell)
    return nodes, seams


def _nearest_touching(orphan, candidates, vertices_by_shell, bore_length_m):
    """The placed part whose contact with ``orphan`` is closest, and the seam that join makes."""
    best_host, best_seam = None, None
    for host in sorted(candidates, key=lambda p: p.name):
        if host is orphan:
            continue
        seam = measure_seam(host, orphan, vertices_by_shell, bore_length_m)
        if best_seam is None or (seam.is_closed, -seam.gap_m) > (best_seam.is_closed,
        -best_seam.gap_m):
            best_host, best_seam = host, seam
    return best_host, best_seam


def _root(parts):
    for part in parts:
        if part.label == MOUNT:
            return part
    return parts[0]


def _children_of(parent, parts, placed):
    """Sub-parts whose taxonomy parent is this one and that are not yet placed (D17-R42, R43).

    Sorted by name so two runs produce the same tree and the same slot paths (G3).
    """
    out = []
    for part in sorted(parts, key=lambda p: p.name):
        if id(part) in placed:
            continue
        wanted = PARENT_LABEL.get(part.label)
        if wanted is None:
            continue
        if wanted == parent.label:
            out.append(part)
    return out


def _slot_id(child) -> str:
    """A slot id matching D08-R6's grammar: lowercase, and derived from what goes in it."""
    cleaned = "".join(c if c.isalnum() else "_" for c in child.name.lower())
    return f"sub_{cleaned}"[:32]


def measure_seam(parent, child, vertices_by_shell, bore_length_m: float = 1.0) -> Seam:
    """The centroid of the contact region between two sub-parts (D17-R44.1).

    The contact region is every parent vertex within reach of a child vertex, where the reach is
    :data:`SEAM_CONTACT_REACH` as a fraction of the weapon's own length. Three widening passes,
    because real art models clearances and a join with a 3 mm gap is still a join — the pass that
    succeeded is reported so a contact found only at the widest reach reads as the weak join it is.

    Placing the slot on the *contact* rather than on either centroid is the whole rule. Two parts
    that meet along a ring join at the ring's centre; using either centroid puts the join wherever
    the geometry happens to be heaviest, which is how a barrel ends up inserted a centimetre into
    its
    own receiver or floating a centimetre out of it.
    """
    parent_points = _points_of(parent, vertices_by_shell)
    child_points = _points_of(child, vertices_by_shell)
    if not parent_points or not child_points:
        position = _midpoint(parent, child)
        return Seam(parent.name, child.name, position, 0.0, 0, 0.0,
                    "no sampled geometry; slot placed at the bounding-box midpoint")

    base = SEAM_CONTACT_REACH * max(1e-4, bore_length_m)
    for multiplier in SEAM_REACH_PASSES:
        reach = base * multiplier
        contact = _contact_points(parent_points, child_points, reach)
        if len(contact) >= 3:
            note = "" if multiplier == SEAM_REACH_PASSES[0] else (
                f"contact found only at {reach * 1000:.0f} mm reach")
            return Seam(parent.name, child.name, _mean(contact),
                        _gap(parent_points, child_points), len(contact), reach, note)

    # No contact at any reach: the two parts genuinely do not meet. The nearest pair of points is
    # still the most defensible join, and `contact_points == 0` is what WEAP-004 acts on.
    near_parent, near_child, distance = _nearest_pair(parent_points, child_points)
    position = tuple((near_parent[i] + near_child[i]) / 2.0 for i in range(3))
    return Seam(parent.name, child.name, position, distance, 0, base * SEAM_REACH_PASSES[-1],
                f"parts do not touch; nearest approach {distance * 1000:.1f} mm")


def _points_of(part, vertices_by_shell):
    points = []
    for index in part.shells:
        points.extend(vertices_by_shell.get(index, ()))
    if not points:
        # A synthesised part: use the corners of its own box, which is what it was generated as.
        lo, hi = part.lo, part.hi
        points = [
            (x, y, z)
            for x in (lo[0], hi[0])
            for y in (lo[1], hi[1])
            for z in (lo[2], hi[2])
        ]
    return points


def _contact_points(parent_points, child_points, reach):
    """Parent points within ``reach`` of any child point.

    Quadratic, and deliberately so: both sides are capped at 96 sampled vertices per shell, so the
    worst realistic case is a few hundred thousand distance tests per seam — microseconds — and a
    spatial index here would be code to maintain for no measurable gain.
    """
    reach_sq = reach * reach
    out = []
    for p in parent_points:
        for c in child_points:
            dx, dy, dz = p[0] - c[0], p[1] - c[1], p[2] - c[2]
            if dx * dx + dy * dy + dz * dz <= reach_sq:
                out.append(p)
                break
    return out


def _gap(parent_points, child_points) -> float:
    """The nearest approach between the two parts. Zero when they interpenetrate or touch."""
    return _nearest_pair(parent_points, child_points)[2]


def _nearest_pair(a_points, b_points):
    best = (a_points[0], b_points[0], float("inf"))
    for a in a_points:
        for b in b_points:
            d = math.dist(a, b)
            if d < best[2]:
                best = (a, b, d)
    return best


def _mount_face(root, vertices_by_shell) -> tuple:
    """The mount's own origin: the centre of the face it bolts to the vehicle with (D17-R25).

    The underside, because a weapon sits on its mounting rather than hanging from it. Putting the
    origin anywhere else is what makes a fitted weapon float or sink into the bodywork, and it is
    the single largest source of the seams this document exists to prevent.
    """
    points = _points_of(root, vertices_by_shell)
    if not points:
        return ((root.lo[0] + root.hi[0]) / 2.0, root.lo[1], (root.lo[2] + root.hi[2]) / 2.0)
    floor = min(p[1] for p in points)
    band = [p for p in points if p[1] <= floor + 0.006]
    if len(band) < 3:
        band = points
    centre = _mean(band)
    return (centre[0], floor, centre[2])


def _mean(points) -> tuple:
    n = float(len(points))
    return tuple(sum(p[i] for p in points) / n for i in range(3))


def _midpoint(parent, child) -> tuple:
    return tuple(((parent.centroid[i] + child.centroid[i]) / 2.0) for i in range(3))
