"""Mesh measurement: volume, centroid, inertia, extents, convex hulls.

Pure Python over plain vertex/triangle lists, with no ``bpy`` import, for two reasons. It
is unit-testable without Blender, which is what ``:blender-tool:unitTest`` runs. And the
harness implements the same formulas independently in Java (D14-S5.4) — D09-S6.2 says
agreement between two implementations is the evidence that both are right, which is only
true if neither is a thin wrapper over the same library call.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass

Vec3 = tuple[float, float, float]
Tri = tuple[int, int, int]


def sub(a: Vec3, b: Vec3) -> Vec3:
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def add(a: Vec3, b: Vec3) -> Vec3:
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def scale(a: Vec3, k: float) -> Vec3:
    return (a[0] * k, a[1] * k, a[2] * k)


def dot(a: Vec3, b: Vec3) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def cross(a: Vec3, b: Vec3) -> Vec3:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def length(a: Vec3) -> float:
    return math.sqrt(dot(a, a))


def distance(a: Vec3, b: Vec3) -> float:
    return length(sub(a, b))


def normalize(a: Vec3) -> Vec3:
    n = length(a)
    return (0.0, 0.0, 0.0) if n == 0.0 else scale(a, 1.0 / n)


def is_finite(v: Vec3) -> bool:
    return all(math.isfinite(c) for c in v)


@dataclass(frozen=True)
class Aabb:
    """An axis-aligned bounding box."""

    min: Vec3
    max: Vec3

    @property
    def extent(self) -> Vec3:
        return sub(self.max, self.min)

    @property
    def max_extent(self) -> float:
        return max(self.extent)

    @property
    def min_extent(self) -> float:
        return min(self.extent)

    @property
    def center(self) -> Vec3:
        return scale(add(self.min, self.max), 0.5)

    @property
    def volume(self) -> float:
        e = self.extent
        return e[0] * e[1] * e[2]


def aabb_of(vertices: list[Vec3]) -> Aabb:
    if not vertices:
        raise ValueError("cannot bound an empty vertex list")
    lo = tuple(min(v[i] for v in vertices) for i in range(3))
    hi = tuple(max(v[i] for v in vertices) for i in range(3))
    return Aabb(lo, hi)  # type: ignore[arg-type]


def vertex_average(vertices: list[Vec3]) -> Vec3:
    """The mean vertex position.

    For a convex hull this is always strictly inside, which makes it the safe reference for
    deciding which way a face normal should point — unlike the volume centroid, which is
    only correct once the winding it is being used to establish is already correct.
    """
    if not vertices:
        return (0.0, 0.0, 0.0)
    total = (0.0, 0.0, 0.0)
    for v in vertices:
        total = add(total, v)
    return scale(total, 1.0 / len(vertices))


def mesh_volume(vertices: list[Vec3], triangles: list[Tri]) -> float:
    """Closed-mesh volume by the divergence theorem (D09-S6.2).

    Returns the absolute value, so a mesh whose normals were authored inward still measures
    positive. Winding is checked separately, where a wrong result is diagnosable; silently
    returning a negative volume here would surface as a negative mass much later.
    """
    v6 = 0.0
    for i, j, k in triangles:
        a, b, c = vertices[i], vertices[j], vertices[k]
        v6 += dot(a, cross(b, c))
    return abs(v6) / 6.0


def mesh_centroid(vertices: list[Vec3], triangles: list[Tri]) -> Vec3:
    """Volume centroid by the same decomposition (D09-S6.2).

    Falls back to the vertex average when the signed volume is degenerate. A tetrahedral
    decomposition of a zero-volume mesh divides by zero; the average is at least a finite
    point in the right neighbourhood for the error message that follows.
    """
    num = (0.0, 0.0, 0.0)
    den = 0.0
    for i, j, k in triangles:
        a, b, c = vertices[i], vertices[j], vertices[k]
        vol = dot(a, cross(b, c)) / 6.0
        num = add(num, scale(add(add(a, b), c), vol * 0.25))
        den += vol
    if abs(den) < 1e-12:
        n = float(len(vertices)) or 1.0
        total = (0.0, 0.0, 0.0)
        for v in vertices:
            total = add(total, v)
        return scale(total, 1.0 / n)
    return scale(num, 1.0 / den)


def inertia_diagonal(vertices: list[Vec3], triangles: list[Tri], mass: float) -> Vec3:
    """Diagonal of the inertia tensor about the centroid, at uniform density.

    Uses the tetrahedron decomposition from the origin, accumulating the full tensor and
    then shifting to the centroid by the parallel-axis theorem. Only the diagonal is
    reported because that is what the manifest carries and what Bullet consumes
    (D06-S5.7); the off-diagonal terms are small for the roughly-symmetric parts this
    project ships, and a body whose principal axes differ materially from its local axes
    is a content problem rather than something to encode.
    """
    volume = mesh_volume(vertices, triangles)
    if volume <= 0.0 or mass <= 0.0:
        return (0.0, 0.0, 0.0)

    # Canonical covariance of the unit tetrahedron (Blow & Binstock).
    ident = (
        (1 / 60.0, 1 / 120.0, 1 / 120.0),
        (1 / 120.0, 1 / 60.0, 1 / 120.0),
        (1 / 120.0, 1 / 120.0, 1 / 60.0),
    )
    covariance = [[0.0] * 3 for _ in range(3)]
    total_signed = 0.0

    for i, j, k in triangles:
        a, b, c = vertices[i], vertices[j], vertices[k]
        det = dot(a, cross(b, c))
        total_signed += det / 6.0
        edges = (a, b, c)
        for r in range(3):
            for s in range(3):
                acc = 0.0
                for p in range(3):
                    for q in range(3):
                        acc += ident[p][q] * edges[p][r] * edges[q][s]
                covariance[r][s] += det * acc

    if abs(total_signed) < 1e-12:
        return (0.0, 0.0, 0.0)

    density = mass / abs(total_signed)
    sign = 1.0 if total_signed > 0 else -1.0
    covariance = [[c * density * sign for c in row] for row in covariance]

    # Shift to the centroid: C_centroid = C_origin - m * (r r^T).
    com = mesh_centroid(vertices, triangles)
    for r in range(3):
        for s in range(3):
            covariance[r][s] -= mass * com[r] * com[s]

    trace = covariance[0][0] + covariance[1][1] + covariance[2][2]
    return (
        trace - covariance[0][0],
        trace - covariance[1][1],
        trace - covariance[2][2],
    )


def triangle_area(a: Vec3, b: Vec3, c: Vec3) -> float:
    return 0.5 * length(cross(sub(b, a), sub(c, a)))


def surface_area(vertices: list[Vec3], triangles: list[Tri]) -> float:
    return sum(triangle_area(vertices[i], vertices[j], vertices[k]) for i, j, k in triangles)


def vertex_normals(vertices: list[Vec3], triangles: list[Tri]) -> list[Vec3]:
    """Area-weighted vertex normals.

    Area weighting rather than a plain average, so a vertex surrounded by many small
    triangles on one side and one large triangle on the other still gets a normal pointing
    the way the surface actually faces — which matters for morph displacement, where a
    wrong normal dents outward.
    """
    normals: list[Vec3] = [(0.0, 0.0, 0.0)] * len(vertices)
    accum = [list(n) for n in normals]
    for i, j, k in triangles:
        a, b, c = vertices[i], vertices[j], vertices[k]
        face = cross(sub(b, a), sub(c, a))  # magnitude is 2x the area: the weight
        for index in (i, j, k):
            accum[index][0] += face[0]
            accum[index][1] += face[1]
            accum[index][2] += face[2]
    return [normalize((n[0], n[1], n[2])) for n in accum]


def quantise(v: Vec3, step: float = 1e-5) -> tuple[int, int, int]:
    """Snap a point to a lattice, for order-stable sorting (D09-R10, G11).

    Sorting raw floats would make shard order depend on differences far below any
    meaningful tolerance, which is precisely how a "deterministic" pipeline produces two
    different shard numberings for the same input.
    """
    return tuple(math.floor(c / step + 0.5) for c in v)  # type: ignore[return-value]


# --- Convex hulls ----------------------------------------------------------------------


def convex_hull(points: list[Vec3], epsilon: float = 1e-9) -> tuple[list[Vec3], list[Tri]]:
    """A 3D convex hull by incremental insertion.

    Implemented here rather than through ``bmesh.ops.convex_hull`` so hull vertex counts
    and the enclosure check are verifiable in a unit test without Blender, and so the
    tool's answer is computed the same way on every Blender version (G11).

    Returns the hull's vertices and its outward-facing triangles. Degenerate input — fewer
    than four points, or all points coplanar — returns an empty hull, which the caller
    reports as ``HULL_FAILED`` rather than silently shipping a flat collision shape.
    """
    unique: list[Vec3] = []
    seen: set[tuple[int, int, int]] = set()
    for p in points:
        key = quantise(p, 1e-7)
        if key not in seen:
            seen.add(key)
            unique.append(p)
    if len(unique) < 4:
        return ([], [])

    initial = _initial_tetrahedron(unique, epsilon)
    if initial is None:
        return ([], [])

    faces = initial
    for point in unique:
        visible_flags = [_signed_distance(f, point) > epsilon for f in faces]
        if not any(visible_flags):
            continue

        # The horizon is where the visible region ends: a *directed* edge of a visible face
        # whose reverse does not also belong to a visible face. Directed matters — the new
        # face must inherit the horizon edge's winding, or its normal points inward and
        # every later visibility test against it is backwards. That is the failure that
        # silently drops a cube corner and reports a volume of 2/3.
        directed: set[tuple[Vec3, Vec3]] = set()
        for face, is_visible in zip(faces, visible_flags, strict=True):
            if is_visible:
                directed.update(_face_edges(face))
        horizon = [(a, b) for (a, b) in directed if (b, a) not in directed]

        faces = [f for f, is_visible in zip(faces, visible_flags, strict=True) if not is_visible]
        for a, b in horizon:
            face = _make_face(a, b, point)
            if face is not None:
                faces.append(face)
        if not faces:
            return ([], [])

    hull_points: list[Vec3] = []
    index_of: dict[tuple[int, int, int], int] = {}
    triangles: list[Tri] = []
    for face in faces:
        tri = []
        for vertex in face[:3]:
            key = quantise(vertex, 1e-7)
            if key not in index_of:
                index_of[key] = len(hull_points)
                hull_points.append(vertex)
            tri.append(index_of[key])
        triangles.append((tri[0], tri[1], tri[2]))

    # Orient every face outward from an interior point, so `mesh_volume` and the enclosure
    # test agree about which side is inside.
    #
    # The interior point is the vertex average, not `mesh_centroid`: the volume centroid is
    # only meaningful once the winding is already consistent, which is the very thing being
    # established here. Using it made face normals inherit whatever winding the incremental
    # construction happened to produce, and a single inverted plane makes `hull_planes`
    # report points inside the hull as being a third of a metre outside it.
    centroid = vertex_average(hull_points)
    oriented: list[Tri] = []
    for i, j, k in triangles:
        a, b, c = hull_points[i], hull_points[j], hull_points[k]
        normal = cross(sub(b, a), sub(c, a))
        if dot(normal, sub(a, centroid)) < 0.0:
            oriented.append((i, k, j))
        else:
            oriented.append((i, j, k))
    return (hull_points, oriented)


def _initial_tetrahedron(
    points: list[Vec3], epsilon: float
) -> list[tuple[Vec3, Vec3, Vec3, Vec3]] | None:
    """Four non-coplanar points, chosen by maximising extent then distance."""
    a = min(points, key=lambda p: (p[0], p[1], p[2]))
    b = max(points, key=lambda p: distance(p, a))
    if distance(a, b) < epsilon:
        return None
    c = max(points, key=lambda p: length(cross(sub(b, a), sub(p, a))))
    if length(cross(sub(b, a), sub(c, a))) < epsilon:
        return None
    normal = cross(sub(b, a), sub(c, a))
    d = max(points, key=lambda p: abs(dot(normal, sub(p, a))))
    if abs(dot(normal, sub(d, a))) < epsilon:
        return None

    faces = []
    for tri in ((a, b, c), (a, c, d), (a, d, b), (b, d, c)):
        face = _make_face(*tri)
        if face is None:
            return None
        # Point every face away from the fourth vertex, giving an outward-facing seed.
        apex = next(p for p in (a, b, c, d) if p not in tri)
        if _signed_distance(face, apex) > 0.0:
            face = _make_face(tri[0], tri[2], tri[1])
            if face is None:
                return None
        faces.append(face)
    return faces


def _make_face(a: Vec3, b: Vec3, c: Vec3) -> tuple[Vec3, Vec3, Vec3, Vec3] | None:
    normal = cross(sub(b, a), sub(c, a))
    if length(normal) < 1e-18:
        return None
    return (a, b, c, normalize(normal))


def _face_edges(face: tuple[Vec3, Vec3, Vec3, Vec3]) -> list[tuple[Vec3, Vec3]]:
    a, b, c = face[0], face[1], face[2]
    return [(a, b), (b, c), (c, a)]


def _signed_distance(face: tuple[Vec3, Vec3, Vec3, Vec3], point: Vec3) -> float:
    return dot(face[3], sub(point, face[0]))


def hull_planes(hull_points: list[Vec3], triangles: list[Tri]) -> list[tuple[Vec3, float]]:
    """The hull's outward face planes as ``(normal, offset)`` with ``dot(n,p) <= offset``."""
    planes: list[tuple[Vec3, float]] = []
    for i, j, k in triangles:
        a, b, c = hull_points[i], hull_points[j], hull_points[k]
        normal = normalize(cross(sub(b, a), sub(c, a)))
        if length(normal) == 0.0:
            continue
        planes.append((normal, dot(normal, a)))
    return planes


def max_outside_distance(
    hull_points: list[Vec3], triangles: list[Tri], sources: list[Vec3]
) -> float:
    """How far the worst source vertex lies outside the hull, in metres.

    Zero or negative means the hull encloses everything. Hull simplification is allowed to
    shave a hair off (D09-S5.5 bounds it by ``HULL_ENCLOSE_M``), so the caller compares
    this against a tolerance rather than against zero.
    """
    planes = hull_planes(hull_points, triangles)
    if not planes:
        return math.inf
    worst = -math.inf
    for point in sources:
        outside = max(dot(normal, point) - offset for normal, offset in planes)
        worst = max(worst, outside)
    return worst


def simplify_hull(
    hull_points: list[Vec3], triangles: list[Tri], max_verts: int
) -> tuple[list[Vec3], list[Tri]]:
    """Greedy vertex removal down to a vertex budget (D09-S5.5).

    At each step drops the vertex whose removal costs the least volume, then re-hulls.

    D09-S5.5 writes this as minimising the *increase* in volume, which is the right rule
    for simplifying a concave outline — dropping a vertex there fills in a notch. A convex
    hull has no notches: removing any vertex can only shrink it. Minimising the increase
    therefore selects the single most destructive removal at every step, which is what
    drove the cylinder's hull 0.61 m inside its own source. Maximising the remaining volume
    is the same intent ("change the shape as little as possible") applied to the convex
    case. See DEV-003.

    The greedy loop re-hulls once per candidate per step, so it costs roughly O(n^3) and is
    only affordable for a small reduction. Above that threshold the hull is first reduced by
    direction sampling — keep the extreme vertex along each of ``max_verts`` evenly spread
    directions — which is O(n * max_verts), deterministic, and gives a hull that already
    tracks the shape closely. A 362-vertex sphere hull takes milliseconds that way and
    minutes greedily.
    """
    points = list(hull_points)
    tris = triangles
    if len(points) - max_verts > _GREEDY_REDUCTION_LIMIT:
        points, tris = _reduce_by_direction_sampling(points, max_verts)
        if not tris:
            return ([], [])
    while len(points) > max_verts and len(points) > 4:
        best_index = -1
        best_volume = -math.inf
        for index in range(len(points)):
            candidate = points[:index] + points[index + 1 :]
            c_points, c_tris = convex_hull(candidate)
            if not c_tris:
                continue
            volume = mesh_volume(c_points, c_tris)
            if volume > best_volume:
                best_volume = volume
                best_index = index
        if best_index < 0:
            break
        points = points[:best_index] + points[best_index + 1 :]
        points, tris = convex_hull(points)
        if not tris:
            return ([], [])
    return (points, tris)


#: How many vertices the greedy loop may remove before direction sampling takes over.
#: Each greedy step costs a full re-hull per candidate, so the cost grows cubically.
_GREEDY_REDUCTION_LIMIT = 8


def _reduce_by_direction_sampling(points: list[Vec3], target: int) -> tuple[list[Vec3], list[Tri]]:
    """Keep the extreme vertex along each of ``target`` evenly spread directions.

    Directions come from a Fibonacci sphere, which spreads them near-uniformly with no
    randomness — the ordering and therefore the resulting hull are identical on every run
    (G11). Selecting extremes guarantees the kept vertices are all genuine hull vertices,
    so the reduced hull is a subset hull: strictly inside the original, which is what the
    inflate step afterwards corrects for.
    """
    if len(points) <= target:
        return convex_hull(points)

    golden = math.pi * (3.0 - math.sqrt(5.0))
    kept: dict[int, Vec3] = {}
    for i in range(target):
        z = 1.0 - (2.0 * i + 1.0) / target
        r = math.sqrt(max(0.0, 1.0 - z * z))
        theta = golden * i
        direction = (r * math.cos(theta), r * math.sin(theta), z)
        best = max(points, key=lambda p, d=direction: dot(p, d))
        kept.setdefault(hash(quantise(best, 1e-7)), best)

    reduced = list(kept.values())
    if len(reduced) < 4:
        return convex_hull(points)
    return convex_hull(reduced)


def inflate_hull(
    hull_points: list[Vec3], triangles: list[Tri], margin: float
) -> tuple[list[Vec3], list[Tri]]:
    """Grow a hull about its centroid so every face plane moves out by at least ``margin``.

    Simplifying a convex hull necessarily leaves it *inside* its source — the cylinder's
    64-vertex budget cannot span a 48-segment ring without cutting the corners off. The
    fix is to re-inflate, which is also what Bullet does with its own collision margin.

    Scaling about the centroid by ``1 + margin / r_min``, where ``r_min`` is the closest
    any face plane comes to the centroid, moves *every* plane out by at least ``margin``:
    a plane at distance ``r`` moves by ``(s-1) * r >= (s-1) * r_min = margin``. That makes
    enclosure a guarantee rather than something to check and hope for, at the cost of
    over-inflating the far side by ``margin * r_max / r_min``.
    """
    if margin <= 0.0 or not triangles:
        return (hull_points, triangles)
    centroid = vertex_average(hull_points)
    planes = hull_planes(hull_points, triangles)
    if not planes:
        return (hull_points, triangles)
    r_min = min(offset - dot(normal, centroid) for normal, offset in planes)
    if r_min <= 1e-12:
        return (hull_points, triangles)
    factor = 1.0 + margin / r_min
    grown = [add(centroid, scale(sub(p, centroid), factor)) for p in hull_points]
    return convex_hull(grown)


def point_in_convex(point: Vec3, planes: list[tuple[Vec3, float]], epsilon: float = 1e-9) -> bool:
    return all(dot(normal, point) - offset <= epsilon for normal, offset in planes)


def pairwise(items: list[Vec3]):
    """Every unordered pair, for site-spacing tests."""
    return itertools.combinations(items, 2)
