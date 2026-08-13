"""Merging tiny shells, and grouping the rest into parts (D15-S5.3, D15-R17 to D15-R20).

Two stages that look similar and answer different questions.

**Merging** (D15-R17) deals with the fact that two-thirds to three-quarters of the shells on a
real car are bolts, screws and single grille strands. Treating each as a part produces
thousands of meaningless parts and destroys the triangle-share statistics the report depends
on, so anything below :data:`labels.MIN_SHELL_TRIANGLES` is absorbed into its nearest labelled
neighbour.

**Grouping** (D15-R18) turns labelled shells into parts by ``(label, side, index)`` and
explicitly *not* by spatial clustering. Bounding-box clustering was measured and rejected:
with any padding sufficient to join a door skin to its inner card, it joins the entire car
into one cluster, because every panel's box overlaps every neighbour's.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass, field

from .labels import MIN_SHELL_TRIANGLES, SIDE_DEADBAND_M
from .shell import Shell


@dataclass
class Part:
    """A group of shells that will become one exported part.

    :param label: the D15-S4.1 label every shell in the group carries
    :param side: ``l``, ``r`` or ``c`` (D15-R19)
    :param index: distinguishes two parts with the same label, role and side — the front and
        rear doors on one flank, upper and lower grilles
    :param role: the refinement :mod:`syndicate_prepare.roles` put on those shells — ``door``
        rather than merely ``panel``. Part of the grouping key, because two panels with
        different roles are never the same part however close together they sit.
    :param shells: the shells that make it up, in ascending shell index
    """

    label: str
    side: str
    index: int
    role: str | None = None
    shells: list[Shell] = field(default_factory=list)

    @property
    def name(self) -> str:
        role = f"_{self.role}" if self.role else ""
        return f"{self.label}{role}_{self.side}{self.index:02d}"

    @property
    def triangles(self) -> int:
        return sum(shell.triangles for shell in self.shells)

    @property
    def lo(self) -> tuple[float, float, float]:
        return tuple(min(shell.lo[i] for shell in self.shells) for i in range(3))

    @property
    def hi(self) -> tuple[float, float, float]:
        return tuple(max(shell.hi[i] for shell in self.shells) for i in range(3))

    @property
    def centre(self) -> tuple[float, float, float]:
        lo, hi = self.lo, self.hi
        return tuple((lo[i] + hi[i]) * 0.5 for i in range(3))

    @property
    def materials(self) -> list[str]:
        return sorted({shell.material for shell in self.shells if shell.material})


def merge_small_shells(shells: list[Shell]) -> int:
    """Absorbs shells below the triangle floor into their nearest labelled neighbour (D15-R17).

    "Nearest" is by centroid distance among shells that survived the floor. A bolt is merged
    into the panel it is bolted to because that panel is the closest large thing to it, which
    is both the right answer and the cheap one.

    The merge is recorded on the small shell rather than performed on the geometry: the
    export stage joins by group, so pointing a shell at its host is all that has to happen
    here, and it keeps AC-D15-4 checkable — every triangle still belongs to exactly one part.

    :return: how many shells were merged
    """
    survivors = [shell for shell in shells if shell.triangles >= MIN_SHELL_TRIANGLES]
    small = [shell for shell in shells if shell.triangles < MIN_SHELL_TRIANGLES]
    if not survivors or not small:
        return 0

    for shell in small:
        nearest = min(
            survivors,
            # Distance first, then the shell index, so two equidistant hosts resolve the same
            # way on every run (D15-R30).
            key=lambda candidate: (shell.distance_to(candidate), candidate.index),
        )
        shell.merged_into = nearest.index
        shell.label = nearest.label
    return len(small)


def side_of(x: float) -> str:
    """``l``, ``r`` or ``c`` from a centroid's ``x`` against the deadband (D15-R19).

    Left is negative ``x``, which follows from D00-R16's right-handed frame with the nose at
    ``+z``: standing behind the car looking forward, ``-x`` is on your left.
    """
    if x < -SIDE_DEADBAND_M:
        return "l"
    if x > SIDE_DEADBAND_M:
        return "r"
    return "c"


def group_into_parts(shells: list[Shell], twins: dict[int, Shell]) -> list[Part]:
    """Groups labelled shells into parts by ``(label, role, side, index)`` (D15-R18).

    Within one ``(label, side)`` the shells are split into as many parts as the *label*
    warrants: a car has four wheels and two doors a side and one bonnet, and the way to tell
    those apart without spatial clustering is to sort along the axis the duplicates are spread
    on — ``z`` for wheels and doors, which run front to back — and cut where the gap between
    consecutive shells exceeds the shells' own size.

    Shells merged into a host are attached to the host's part, never to one of their own, so
    every triangle lands in exactly one part (AC-D15-4).
    """
    hosts = {shell.index: shell for shell in shells if shell.merged_into is None}

    buckets: dict[tuple[str, str, str, str], list[Shell]] = {}
    for shell in shells:
        host = shell if shell.merged_into is None else hosts.get(shell.merged_into)
        if host is None:
            host = shell
        # A shell captured into a wheel corner is grouped by that corner, not by its
        # position along the car: the whole point of the capture is that these pieces belong
        # together, and a length split would put a brake disc and its own hub in two parts.
        key = (host.label, host.role or "", side_of(host.centroid[0]), host.corner or "")
        buckets.setdefault(key, []).append(shell)

    parts: list[Part] = []
    for (label, role, side, corner), members in sorted(buckets.items()):
        clusters = [members] if corner else _split_along_length(members)
        for index, cluster in enumerate(clusters):
            part = Part(label=label, side=side, index=index, role=role or None)
            part.shells = sorted(cluster, key=lambda shell: shell.index)
            parts.append(part)

    # Mirror pairing is authoritative for side (D15-R20): a shell whose twin took the other
    # side confirms the split rather than changing it, so nothing is done here beyond
    # recording that the pairing exists. What it would change — merging two mirrored parts
    # into one part type with two instances — is the exporter's decision, not this one's.
    del twins
    return sorted(parts, key=lambda part: (part.label, part.role or "", part.side, part.index))


def _split_along_length(shells: list[Shell]) -> list[list[Shell]]:
    """Cuts a ``(label, side)`` bucket into runs separated by a gap larger than the pieces.

    The wheels on one side of a car are two tight clusters of shells with two metres between
    them; a door skin and its inner card are two shells with centimetres between them. One
    rule separates both cases without knowing which it is looking at, and without the
    bounding-box clustering D15-R18 rejects.
    """
    if len(shells) <= 1:
        return [shells]
    ordered = sorted(shells, key=lambda shell: (shell.centroid[2], shell.index))
    typical = _median(shell.longest_extent for shell in ordered)
    threshold = max(0.25, typical * 1.5)

    clusters: list[list[Shell]] = [[ordered[0]]]
    for previous, current in itertools.pairwise(ordered):
        if current.centroid[2] - previous.centroid[2] > threshold:
            clusters.append([current])
        else:
            clusters[-1].append(current)
    return clusters


def _median(values) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) * 0.5


def angular_coverage_deg(points, axle_centre, sectors: int) -> float:
    """How much of the circle a set of points occupies about an axle (D15-R21, D15-R24).

    Measured from the **points**, never from a bounding box: a five-spoke rim's box has four
    corners like everything else's, so a box-based measure cannot tell a spoked wheel from a
    caliper (D15-R24).

    :param points: ``(x, y, z)`` vertices in game space
    :param axle_centre: the axle's ``(x, y, z)`` position. Only ``y`` and ``z`` are read —
        ``x`` is the axis of rotation, along which a bearing is undefined — but the whole
        point is passed so that callers hand over the axle they measured rather than a
        two-element slice of it that is easy to build in the wrong order.
    :param sectors: how many equal sectors the circle is divided into
    """
    return len(occupied_sectors(points, axle_centre, sectors)) * (360.0 / max(1, sectors))


#: Degrees of slack when a bearing lands on the far side of the 360° wrap. See the comment in
#: :func:`occupied_sectors` for what it costs to leave it out.
BEARING_EPSILON_DEG = 1e-6


def occupied_sectors(points, axle_centre, sectors: int) -> set[int]:
    """Which sectors about the axle a set of points falls in."""
    occupied: set[int] = set()
    step = 360.0 / max(1, sectors)
    for point in points:
        dy = point[1] - axle_centre[1]
        dz = point[2] - axle_centre[2]
        if (dy * dy + dz * dz) < 4e-4:
            # Vertices on the axis have no bearing; the angle there is undefined and
            # numerically wild.
            continue
        bearing = math.degrees(math.atan2(dy, dz)) % 360.0
        if bearing >= 360.0 - BEARING_EPSILON_DEG:
            # A vertex sitting exactly on +z has a bearing of zero, and a bearing of zero
            # computed from an axle centre that is 3e-17 off comes out *negative* and wraps to
            # 359.999…, which lands in the last sector instead of the first. On a lug-nut
            # pattern that single misplaced sector is the difference between a group that is
            # four-fold symmetric and one that is not symmetric at all.
            bearing = 0.0
        occupied.add(int(bearing // step))
    return occupied


def rotational_symmetry_order(points, axle_centre, sectors: int) -> int:
    """The largest ``n`` for which the group maps onto itself under a turn of ``360/n``.

    This is D15-R22's rule as it is *stated* — "a wheel is symmetric under rotation by 360/n
    and every piece maps onto another piece of the same kind" — where
    :func:`angular_coverage_deg` is its proxy. The two agree on everything continuous: a tyre,
    a rim and a brake disc all cover the full circle and are symmetric under any turn.

    They disagree on exactly the case R22 uses to explain itself. Five lug nuts occupy five
    sectors out of twenty-four, so coverage calls them 75° and sends them to the hub — while
    the set is plainly invariant under a fifth of a turn, which is what "rotates with the
    wheel" means. Coverage is what a *solid of revolution* has; this is what a *pattern* has,
    and a wheel is made of both.

    :return: the symmetry order, or ``1`` for a group with no rotational symmetry
    """
    occupied = occupied_sectors(points, axle_centre, sectors)
    if not occupied:
        return 1
    best = 1
    for order in range(2, sectors + 1):
        if sectors % order:
            continue
        shift = sectors // order
        if all(((sector + shift) % sectors) in occupied for sector in occupied):
            best = max(best, order)
    return best
