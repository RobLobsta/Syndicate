"""Exact convex decomposition of a source solid (D09-S5.2, DEV-004).

The exact fracture path needs the source expressed as *half-spaces*. A Voronoi cell is an
intersection of half-spaces, so ``cell ∩ source`` is a single polytope intersection when —
and only when — the source is convex. A non-convex source has no such representation, which
is why it fell back to cutting the mesh, and why a hollow box came out measuring six times
its own mass (DEV-004).

A source expressed as a **union of disjoint convex pieces** restores the property:
``cell ∩ source`` becomes the union of ``cell ∩ piece`` over the pieces, every term of
which is again an exact polytope intersection, and no two terms can overlap because no two
pieces do.

The decomposition is a solid binary space partition over the source's own face planes. Every
leaf region of the tree lies on one definite side of every plane it descends through, so no
boundary face crosses its interior and it is either wholly solid or wholly empty; a solid
leaf is, by construction, the intersection of the half-spaces along its root path. That is
exact — no hull fitting, no approximation, no Steiner points, and no mesh boolean, which is
the operation DEV-005 recorded as unusable at these operand ratios.

Pure Python with no ``bpy`` import, for the same reason ``geometry`` is: the decomposition is
the part most likely to be wrong, and it is unit-testable here without a Blender host.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .geometry import (
    Aabb,
    Polytope,
    Tri,
    Vec3,
    aabb_of,
    add,
    cross,
    dot,
    intersect_halfspaces,
    length,
    mesh_volume,
    normalize,
    quantise,
    scale,
    sub,
)

#: Cap on solid leaves. A BSP over N distinct planes is O(2^N) leaves in the worst case, and
#: the fracture stage then costs one polytope intersection per (site, piece) pair. A part
#: needing more pieces than this is not a part this tool should be silently spending minutes
#: on — the caller falls back and says so.
MAX_PIECES = 512

#: Cap on tree depth, which bounds recursion independently of the leaf cap: a pathological
#: source can descend a long way before producing its first leaf.
MAX_DEPTH = 96

#: How far the reconstructed volume may differ from the source's before the decomposition is
#: rejected. The pieces are exact polytopes, so agreement is normally at float precision;
#: anything at 1e-4 is a real structural error (an unbounded leaf, a mis-signed face) and not
#: accumulated rounding. Deliberately far tighter than the 2% G7 mass tolerance, because this
#: is the check that stands between a wrong decomposition and a plausible-looking manifest.
VOLUME_TOLERANCE_FRAC = 1e-4

#: Plane-distance epsilon, as a fraction of the source's largest extent.
_EPSILON_FRAC = 1e-9

#: A leaf polytope thinner than this is a numerical artefact of redundant constraints rather
#: than material, and is dropped. 1 mm^3 of steel is 8 mg; the volume check below is what
#: guarantees the dropped total stays negligible.
_MIN_PIECE_VOLUME_M3 = 1e-12

#: How many distinct candidate planes the splitting heuristic evaluates per node. Evaluating
#: every candidate is quadratic in the polygon count and buys very little past the first
#: couple of dozen.
_CANDIDATE_PLANES = 24

#: Relative cost of splitting a face versus leaving the two subtrees unbalanced. Splits are
#: what make the tree grow, so they are weighted well above balance.
_SPLIT_WEIGHT = 8

_COPLANAR = 0
_FRONT = 1
_BACK = 2
_SPANNING = _FRONT | _BACK

#: ``(points, normal, offset)`` — a boundary polygon and the outward plane it lies in.
_Polygon = tuple[list[Vec3], Vec3, float]


@dataclass(frozen=True)
class ConvexPiece:
    """One convex region of the decomposition, as half-spaces and as a mesh.

    ``planes`` is the representation the fracture stage consumes: each entry is
    ``(normal, offset)`` meaning ``dot(normal, p) <= offset``. The mesh and its measurements
    are carried alongside because the caller needs the AABB to cull pieces against a cell,
    and the volume to verify the decomposition before trusting any of it.
    """

    planes: list[tuple[Vec3, float]]
    vertices: list[Vec3]
    triangles: list[Tri]
    volume_m3: float
    aabb: Aabb


@dataclass(frozen=True)
class Decomposition:
    """The result, usable only when ``reason`` is empty.

    A failure carries its reason rather than raising, because the caller's response is to log
    it and take the older, weaker path — not to abort the run.
    """

    pieces: list[ConvexPiece]
    reason: str
    volume_m3: float
    source_volume_m3: float

    @property
    def ok(self) -> bool:
        return not self.reason

    @property
    def volume_error_frac(self) -> float:
        if self.source_volume_m3 <= 0.0:
            return 1.0
        return abs(self.volume_m3 - self.source_volume_m3) / self.source_volume_m3


class _AbandonedError(Exception):
    """Raised inside the recursion when a cap is hit; carries the reason for the caller."""


def convex_decomposition(
    vertices: list[Vec3],
    triangles: list[Tri],
    max_pieces: int = MAX_PIECES,
    tolerance: float = VOLUME_TOLERANCE_FRAC,
) -> Decomposition:
    """Decompose a closed, outward-wound solid into disjoint convex pieces.

    A convex source comes back as a single piece, which makes this a strict generalisation of
    the convex path rather than an alternative to it.

    The result is verified before it is returned: the pieces' volumes must sum to the source's
    own to within ``tolerance``. A BSP is only exact for a *closed, consistently wound* mesh,
    and nothing upstream proves the source is one — stage 1 checks the volume is positive,
    which a mesh with an inverted shell can still be. The sum is the cheap, total check that
    catches every way that assumption can fail at once.
    """
    source_volume = mesh_volume(vertices, triangles)
    if source_volume <= 0.0:
        return Decomposition([], "source has no volume", 0.0, source_volume)

    bounds = aabb_of(vertices)
    epsilon = _EPSILON_FRAC * max(bounds.max_extent, 1.0)

    polygons = _source_polygons(vertices, triangles, epsilon)
    if not polygons:
        return Decomposition([], "source has no non-degenerate faces", 0.0, source_volume)

    leaves: list[list[tuple[Vec3, float]]] = []
    try:
        _partition(polygons, [], leaves, epsilon, max_pieces, 0)
    except _AbandonedError as abandoned:
        return Decomposition([], str(abandoned), 0.0, source_volume)

    pieces = _materialise(leaves, bounds)
    if not pieces:
        return Decomposition([], "decomposition produced no solid regions", 0.0, source_volume)

    total = math.fsum(piece.volume_m3 for piece in pieces)
    result = Decomposition(pieces, "", total, source_volume)
    if result.volume_error_frac > tolerance:
        return Decomposition(
            [],
            f"pieces measure {total:.6f} m3 against a source of {source_volume:.6f} m3 "
            f"({100 * result.volume_error_frac:.4f}% apart)",
            total,
            source_volume,
        )
    return result


# --- The BSP ---------------------------------------------------------------------------


def _source_polygons(vertices: list[Vec3], triangles: list[Tri], epsilon: float) -> list[_Polygon]:
    """The source's triangles as plane-carrying polygons, degenerate ones dropped."""
    polygons: list[_Polygon] = []
    for i, j, k in triangles:
        a, b, c = vertices[i], vertices[j], vertices[k]
        normal = cross(sub(b, a), sub(c, a))
        if length(normal) <= epsilon * epsilon:
            continue
        normal = normalize(normal)
        polygons.append(([a, b, c], normal, dot(normal, a)))
    return polygons


def _partition(
    polygons: list[_Polygon],
    constraints: list[tuple[Vec3, float]],
    leaves: list[list[tuple[Vec3, float]]],
    epsilon: float,
    max_pieces: int,
    depth: int,
) -> None:
    """Split ``polygons`` recursively, appending one constraint set per solid leaf.

    The orientation convention is the whole argument: every polygon's normal points out of
    the material, so the region *behind* a face plane is material and the region in front of
    it is not. A subregion with no boundary polygon left in it is therefore homogeneous, and
    which of the two it is follows from which side of its parent's plane it is on.
    """
    if depth > MAX_DEPTH:
        raise _AbandonedError(f"decomposition exceeded a depth of {MAX_DEPTH}")

    # A region whose remaining faces already bound a convex solid is a leaf: its own planes
    # are the constraint set, and splitting it further would only carve one convex piece into
    # several. Without this the recursion descends once per face plane even for a convex
    # source — a 48-segment cylinder would come back as a stack of slabs, and a sphere would
    # exhaust the depth cap — and the pieces, though correct, would multiply the fracture
    # stage's cost by their number for no gain.
    own = _bounding_planes(polygons, epsilon)
    if own is not None:
        if len(leaves) >= max_pieces:
            raise _AbandonedError(f"decomposition exceeded {max_pieces} pieces")
        leaves.append([*constraints, *own])
        return

    normal, offset = _select_plane(polygons, epsilon)
    front, back = _split_all(polygons, normal, offset, epsilon)

    # Behind the plane: `dot(normal, p) <= offset`. The plane was taken from a polygon, so at
    # least one face lies in it facing forwards, which is what makes "behind" the solid side.
    behind = [*constraints, (normal, offset)]
    if back:
        _partition(back, behind, leaves, epsilon, max_pieces, depth + 1)
    elif len(leaves) < max_pieces:
        leaves.append(behind)
    else:
        raise _AbandonedError(f"decomposition exceeded {max_pieces} pieces")

    if front:
        ahead = [*constraints, (scale(normal, -1.0), -offset)]
        _partition(front, ahead, leaves, epsilon, max_pieces, depth + 1)


def _bounding_planes(
    polygons: list[_Polygon], epsilon: float
) -> list[tuple[Vec3, float]] | None:
    """The region's face planes when they bound a convex solid, else ``None``.

    Convex means every face lies behind every face's plane, which is tested over the deduped
    vertices rather than over every polygon corner — a curved shell has three times as many
    corners as vertices, and this test runs at every node.

    The early return on the first violation is what keeps it affordable: a non-convex region
    almost always fails within the first few planes, and a convex one pays the full cost once
    and then stops recursing entirely.
    """
    planes: list[tuple[Vec3, float]] = []
    seen_planes: set[tuple[int, int, int, int]] = set()
    points: list[Vec3] = []
    seen_points: set[tuple[int, int, int]] = set()
    for polygon_points, normal, offset in polygons:
        key = (*quantise(normal, 1e-6), math.floor(offset / 1e-6 + 0.5))
        if key not in seen_planes:
            seen_planes.add(key)
            planes.append((normal, offset))
        for point in polygon_points:
            point_key = quantise(point, 1e-9)
            if point_key not in seen_points:
                seen_points.add(point_key)
                points.append(point)

    for normal, offset in planes:
        for point in points:
            if dot(normal, point) - offset > epsilon:
                return None
    return planes


def _select_plane(polygons: list[_Polygon], epsilon: float) -> tuple[Vec3, float]:
    """The candidate plane that splits the fewest faces, then balances the tree best.

    Candidates come only from the polygons themselves, which is what bounds the tree: no node
    introduces a plane its subtree has not already seen, and a chosen plane's own faces are
    consumed by the split, so the number of distinct planes strictly decreases with depth and
    the recursion is guaranteed to terminate.
    """
    candidates: list[tuple[Vec3, float]] = []
    seen: set[tuple[int, int, int, int]] = set()
    for _points, normal, offset in polygons:
        key = (*quantise(normal, 1e-6), math.floor(offset / 1e-6 + 0.5))
        if key in seen:
            continue
        seen.add(key)
        candidates.append((normal, offset))
        if len(candidates) >= _CANDIDATE_PLANES:
            break

    best = candidates[0]
    best_score = math.inf
    for normal, offset in candidates:
        splits = fronts = backs = 0
        for points, _normal, _offset in polygons:
            side = _classify(points, normal, offset, epsilon)
            if side == _SPANNING:
                splits += 1
            elif side == _FRONT:
                fronts += 1
            elif side == _BACK:
                backs += 1
        score = splits * _SPLIT_WEIGHT + abs(fronts - backs)
        # Strict improvement only: ties keep the earlier candidate, so the choice depends on
        # the polygon order and not on float comparison noise (G11).
        if score < best_score:
            best_score = score
            best = (normal, offset)
    return best


def _split_all(
    polygons: list[_Polygon], normal: Vec3, offset: float, epsilon: float
) -> tuple[list[_Polygon], list[_Polygon]]:
    front: list[_Polygon] = []
    back: list[_Polygon] = []
    for points, poly_normal, poly_offset in polygons:
        side = _classify(points, normal, offset, epsilon)
        if side == _COPLANAR:
            # A face lying in the split plane bounds neither subregion's interior, so it is
            # consumed here — that consumption is what makes the recursion terminate. The
            # exception is a face pointing the other way, which means material on the *front*
            # side at that patch (two blocks meeting along a shared plane, solid on opposite
            # sides of it). Dropping that one would let its region be classified as empty.
            if dot(poly_normal, normal) < 0.0:
                front.append((points, poly_normal, poly_offset))
            continue
        if side == _FRONT:
            front.append((points, poly_normal, poly_offset))
            continue
        if side == _BACK:
            back.append((points, poly_normal, poly_offset))
            continue
        ahead, behind = _split_polygon(points, normal, offset, epsilon)
        if ahead is not None:
            front.append((ahead, poly_normal, poly_offset))
        if behind is not None:
            back.append((behind, poly_normal, poly_offset))
    return front, back


def _classify(points: list[Vec3], normal: Vec3, offset: float, epsilon: float) -> int:
    combined = _COPLANAR
    for point in points:
        distance = dot(normal, point) - offset
        if distance > epsilon:
            combined |= _FRONT
        elif distance < -epsilon:
            combined |= _BACK
    return combined


def _split_polygon(
    points: list[Vec3], normal: Vec3, offset: float, epsilon: float
) -> tuple[list[Vec3] | None, list[Vec3] | None]:
    """Cut a convex polygon by a plane, returning its front and back parts.

    A vertex on the plane belongs to both parts, which is what keeps the two fragments
    sharing an exact edge rather than each rounding their own copy of it.
    """
    distances = [dot(normal, point) - offset for point in points]
    front: list[Vec3] = []
    back: list[Vec3] = []
    count = len(points)
    for i in range(count):
        j = (i + 1) % count
        current, following = points[i], points[j]
        d_current, d_next = distances[i], distances[j]

        if d_current >= -epsilon:
            front.append(current)
        if d_current <= epsilon:
            back.append(current)
        if (d_current > epsilon and d_next < -epsilon) or (
            d_current < -epsilon and d_next > epsilon
        ):
            t = d_current / (d_current - d_next)
            crossing = add(current, scale(sub(following, current), t))
            front.append(crossing)
            back.append(crossing)

    return (front if len(front) >= 3 else None, back if len(back) >= 3 else None)


# --- Leaves to pieces --------------------------------------------------------------------


def _materialise(leaves: list[list[tuple[Vec3, float]]], bounds: Aabb) -> list[ConvexPiece]:
    """Turn each leaf's constraint set into a bounded polytope.

    The seed box is the source's own AABB exactly, neither padded nor inflated. Every solid
    leaf lies inside the solid and therefore inside its bounding box, so the box constrains
    nothing real; padding it would inflate every piece that reaches the surface, and padding
    it *generously* would let a leaf that is wrongly unbounded come back as a plausible piece
    instead of showing up in the volume check.
    """
    seed = Polytope.box(bounds.min, bounds.max)
    pieces: list[ConvexPiece] = []
    for constraints in leaves:
        poly = intersect_halfspaces(seed, constraints)
        if poly.is_empty():
            continue
        triangles = poly.triangles()
        if len(poly.vertices) < 4 or len(triangles) < 4:
            continue
        volume = mesh_volume(poly.vertices, triangles)
        if volume <= _MIN_PIECE_VOLUME_M3:
            continue
        pieces.append(
            ConvexPiece(
                planes=constraints,
                vertices=poly.vertices,
                triangles=triangles,
                volume_m3=volume,
                aabb=aabb_of(poly.vertices),
            )
        )
    return pieces


__all__ = [
    "MAX_DEPTH",
    "MAX_PIECES",
    "VOLUME_TOLERANCE_FRAC",
    "ConvexPiece",
    "Decomposition",
    "convex_decomposition",
]
