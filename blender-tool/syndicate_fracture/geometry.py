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

    Incremental insertion rests on one invariant: the faces a new point can see form a
    topological disc, so replacing them with a fan to that point leaves a closed surface.
    On well-conditioned input that holds automatically. On a 5 mm glass shard — hundreds of
    points sitting *on* each other's face planes — it does not, and the two ways it fails
    both used to corrupt the hull silently. See :func:`_repair_visible_cap` and the
    transactional insertion below.
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
        visible_flags = _repair_visible_cap(faces, visible_flags)

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

        # Insert transactionally: build the whole candidate surface, and adopt it only if it
        # is still closed. Appending the fan in place instead is what let a single dropped
        # triangle wreck the hull — `_make_face` returns None when the new point is collinear
        # with a horizon edge, which on a slab of glass happens routinely, and the skipped
        # triangle leaves a hole. Every later visibility test then reads through that hole,
        # and the result is not a convex polyhedron at all: measured on a windscreen shard,
        # 30 vertices carrying 71 triangles where Euler allows 56, with four of its own
        # vertices lying 22 mm outside it (DISC-040).
        #
        # Rolling the point back instead leaves the hull slightly small rather than invalid,
        # which is a shortfall `build_hull` already measures and inflates away. On the two
        # shipped cars this fires once in 24 shards and costs one micron of enclosure.
        kept = [f for f, is_visible in zip(faces, visible_flags, strict=True) if not is_visible]
        fan = [_make_face(a, b, point) for a, b in horizon]
        if not horizon or any(face is None for face in fan):
            continue
        candidate = kept + [face for face in fan if face is not None]
        if not _is_closed_surface(candidate):
            continue
        faces = candidate

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


def _undirected_edge(edge: tuple[Vec3, Vec3]) -> tuple[Vec3, Vec3]:
    """An edge keyed independently of which face traversed it."""
    a, b = edge
    return (a, b) if a <= b else (b, a)


def _is_closed_surface(faces) -> bool:
    """Whether every edge is shared by exactly two faces — the polyhedron invariant."""
    used: dict[tuple[Vec3, Vec3], int] = {}
    for face in faces:
        for edge in _face_edges(face):
            key = _undirected_edge(edge)
            used[key] = used.get(key, 0) + 1
    return bool(used) and all(count == 2 for count in used.values())


def _face_components(faces, indices: list[int]) -> list[list[int]]:
    """Connected components of ``indices``, joined across shared edges.

    Components come out in the order their lowest-indexed face appears and each is sorted,
    so the caller's choice among them is the same on every run (G11).
    """
    by_edge: dict[tuple[Vec3, Vec3], list[int]] = {}
    for index in indices:
        for edge in _face_edges(faces[index]):
            by_edge.setdefault(_undirected_edge(edge), []).append(index)

    seen: set[int] = set()
    components: list[list[int]] = []
    for start in indices:
        if start in seen:
            continue
        component: list[int] = []
        stack = [start]
        seen.add(start)
        while stack:
            current = stack.pop()
            component.append(current)
            for edge in _face_edges(faces[current]):
                for neighbour in by_edge[_undirected_edge(edge)]:
                    if neighbour not in seen:
                        seen.add(neighbour)
                        stack.append(neighbour)
        components.append(sorted(component))
    return components


def _minor_components(components: list[list[int]]) -> list[list[int]]:
    """Every component except the largest; ties break on the lowest face index."""
    if len(components) <= 1:
        return []
    largest = max(
        range(len(components)), key=lambda i: (len(components[i]), -components[i][0])
    )
    return [component for i, component in enumerate(components) if i != largest]


#: Passes of cap repair. Each pass can only merge regions, so it converges quickly; the
#: bound is what stops a pathological set from oscillating between the two corrections.
_CAP_REPAIR_PASSES = 4


def _repair_visible_cap(faces, flags: list[bool]) -> list[bool]:
    """Force the visible set to be a single topological disc.

    Seen from a point outside a convex polyhedron, the visible faces form one disc and the
    hidden faces form the complementary disc; the horizon between them is a single closed
    cycle. Everything downstream assumes it.

    Round-off breaks that assumption whenever points sit on each other's face planes. A face
    in the middle of the cap comes out at -1e-12 instead of +1e-12 and reads as hidden,
    punching a hole in the disc; the horizon then has two cycles and the fan built across it
    is self-intersecting. Measured on the Eclipse's side window this happened at 16 of 44
    insertions on one shard alone.

    The repair reads the two sides as what they must be. Any hidden region that is not the
    main one is a hole in the cap, so it joins the cap; any visible region that is not the
    main one is a speck detached from it, so it leaves. Applying both in that order — holes
    first, because filling one can also reconnect the cap — leaves exactly two regions, and
    two complementary discs on a sphere always meet along one cycle.

    Note that this is a repair of the *classification*, not of the geometry: the faces are
    untouched, and a correctly classified cap passes through unchanged.
    """
    for _pass in range(_CAP_REPAIR_PASSES):
        changed = False
        hidden = [i for i, is_visible in enumerate(flags) if not is_visible]
        if not hidden:
            return flags
        for component in _minor_components(_face_components(faces, hidden)):
            for index in component:
                flags[index] = True
            changed = True

        if all(flags):
            return flags
        visible = [i for i, is_visible in enumerate(flags) if is_visible]
        for component in _minor_components(_face_components(faces, visible)):
            for index in component:
                flags[index] = False
            changed = True

        if not changed:
            return flags
    return flags


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

    hull = convex_hull(reduced)
    if not hull[1]:
        # Every sampled extreme landed on one plane, so the reduced set has no volume and
        # `convex_hull` refuses it. This is what a **flat** part does: the rim of a pane is
        # planar and carries every extreme except along the one direction that sees its
        # camber, and thirty-two directions spread over a sphere will not land inside the
        # couple of degrees where 3 mm of camber beats 700 mm of width. The source is not
        # degenerate — the 96-point hull of that pane measures 0.0128 m³ — so the repair is
        # to put back the points the sampling could not see: the two farthest from the plane
        # everything else sits on.
        reduced.extend(_offplane_extremes(points, reduced))
        hull = convex_hull(reduced)
    if not hull[1]:
        return convex_hull(points)
    return hull


def _offplane_extremes(points: list[Vec3], planar: list[Vec3]) -> list[Vec3]:
    """The points of ``points`` farthest to either side of the plane ``planar`` lies in.

    Returns at most two, and an empty list if no plane can be fitted — which means the
    caller's set is collinear rather than coplanar and no single point can rescue it.
    """
    origin = planar[0]
    normal = None
    for i in range(1, len(planar)):
        for j in range(i + 1, len(planar)):
            candidate = cross(sub(planar[i], origin), sub(planar[j], origin))
            if length(candidate) > 1e-12:
                normal = normalize(candidate)
                break
        if normal is not None:
            break
    if normal is None:
        return []

    above = max(points, key=lambda p: dot(sub(p, origin), normal))
    below = min(points, key=lambda p: dot(sub(p, origin), normal))
    extremes = []
    for point in (above, below):
        if abs(dot(sub(point, origin), normal)) > 1e-9:
            extremes.append(point)
    return extremes


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


# --- Convex polytopes from half-spaces -------------------------------------------------


class Polytope:
    """A closed convex polyhedron as ordered face loops over a shared vertex list.

    Faces are stored as loops rather than triangles because clipping needs the loop: a
    plane crosses a convex face in exactly two points, so the clipped face is again one
    loop, and the new points across all faces form exactly one closed cap loop. Triangles
    would lose that structure and put the caller back to guessing how to fill the cut —
    which is the failure mode that made bisect-and-fill wrong on curved sources (DEV-005).
    """

    __slots__ = ("faces", "vertices")

    def __init__(self, vertices: list[Vec3], faces: list[list[int]]) -> None:
        self.vertices = vertices
        self.faces = faces

    @staticmethod
    def box(lo: Vec3, hi: Vec3) -> Polytope:
        """An axis-aligned box, faces wound counter-clockwise seen from outside."""
        vertices: list[Vec3] = [
            (lo[0], lo[1], lo[2]),
            (hi[0], lo[1], lo[2]),
            (hi[0], hi[1], lo[2]),
            (lo[0], hi[1], lo[2]),
            (lo[0], lo[1], hi[2]),
            (hi[0], lo[1], hi[2]),
            (hi[0], hi[1], hi[2]),
            (lo[0], hi[1], hi[2]),
        ]
        faces = [
            [0, 3, 2, 1],
            [4, 5, 6, 7],
            [0, 1, 5, 4],
            [1, 2, 6, 5],
            [2, 3, 7, 6],
            [3, 0, 4, 7],
        ]
        return Polytope(vertices, faces)

    def is_empty(self) -> bool:
        return not self.faces or len(self.vertices) < 4

    def triangles(self) -> list[Tri]:
        """Fan-triangulate every face. Valid because each face is convex."""
        out: list[Tri] = []
        for face in self.faces:
            for i in range(1, len(face) - 1):
                out.append((face[0], face[i], face[i + 1]))
        return out

    def volume(self) -> float:
        return mesh_volume(self.vertices, self.triangles())

    def compact(self) -> Polytope:
        """Drop vertices no face references, renumbering what remains."""
        used = sorted({index for face in self.faces for index in face})
        remap = {old: new for new, old in enumerate(used)}
        return Polytope(
            [self.vertices[i] for i in used],
            [[remap[i] for i in face] for face in self.faces],
        )


def clip_polytope(poly: Polytope, normal: Vec3, offset: float, epsilon: float = 1e-9) -> Polytope:
    """Clip to the half-space ``dot(normal, p) <= offset``.

    Sutherland-Hodgman per face, then one cap face closing the cut.

    The cap is built by sorting the on-plane points angularly about their own centroid,
    rather than by chaining cut segments head to tail. Chaining looks natural and is fragile:
    when a vertex lies *exactly* on the plane — which happens constantly, because every clip
    after the first meets vertices the previous clip placed there — that vertex is both the
    end of one segment and the start of the next, and deciding which face "enters" and which
    "leaves" needs case analysis that is wrong more often than it is right. The cut of a
    convex polytope is a convex polygon, so an angular sort is exact and needs no cases.
    """
    if poly.is_empty():
        return poly

    distances = [dot(normal, v) - offset for v in poly.vertices]
    if all(d <= epsilon for d in distances):
        return poly  # wholly inside: the plane does not cut
    if all(d >= -epsilon for d in distances):
        return Polytope([], [])  # wholly outside

    vertices = list(poly.vertices)
    # Points on the cut plane are shared between adjacent faces, so they are deduplicated on
    # a lattice — otherwise the cap would have several copies of each corner.
    shared: dict[tuple[int, int, int], int] = {}

    def intern(point: Vec3) -> int:
        key = quantise(point, 1e-9)
        existing = shared.get(key)
        if existing is not None:
            return existing
        shared[key] = len(vertices)
        vertices.append(point)
        return shared[key]

    on_plane: set[int] = set()
    faces: list[list[int]] = []

    for face in poly.faces:
        kept: list[int] = []
        count = len(face)
        for i in range(count):
            current = face[i]
            following = face[(i + 1) % count]
            d_current = distances[current]
            d_next = distances[following]

            if d_current <= epsilon:
                # A vertex already sitting on the plane is a cap corner too, and must be
                # interned so both faces sharing it reference the same index.
                if abs(d_current) <= epsilon:
                    index = intern(poly.vertices[current])
                    kept.append(index)
                    on_plane.add(index)
                else:
                    kept.append(current)

            crosses = (d_current < -epsilon and d_next > epsilon) or (
                d_current > epsilon and d_next < -epsilon
            )
            if crosses:
                t = d_current / (d_current - d_next)
                edge = sub(poly.vertices[following], poly.vertices[current])
                crossing = intern(add(poly.vertices[current], scale(edge, t)))
                kept.append(crossing)
                on_plane.add(crossing)

        deduped = _dedupe_loop(kept)
        if len(deduped) >= 3:
            faces.append(deduped)

    cap = _cap_face(vertices, on_plane, normal)
    if cap is not None:
        faces.append(cap)

    if len(faces) < 4:
        return Polytope([], [])
    return Polytope(vertices, faces).compact()


def _cap_face(vertices: list[Vec3], on_plane: set[int], normal: Vec3) -> list[int] | None:
    """The polygon closing a clip, wound counter-clockwise seen from outside.

    ``u`` and ``v`` are chosen so that ``u x v == normal``; sorting by ``atan2(.v, .u)`` then
    runs counter-clockwise about ``+normal``, which is the outward direction for the cap.
    Getting this backwards inverts the face and makes the polytope's signed volume negative.
    """
    if len(on_plane) < 3:
        return None
    indices = sorted(on_plane)
    centre = vertex_average([vertices[i] for i in indices])

    seed = (0.0, 0.0, 1.0) if abs(normal[2]) < 0.9 else (1.0, 0.0, 0.0)
    u = normalize(cross(seed, normal))
    if length(u) == 0.0:
        return None
    v = cross(normal, u)

    def angle(i: int) -> float:
        radial = sub(vertices[i], centre)
        return math.atan2(dot(radial, v), dot(radial, u))

    ordered = sorted(indices, key=angle)
    return ordered if len(ordered) >= 3 else None


def intersect_halfspaces(
    seed: Polytope, planes: list[tuple[Vec3, float]], epsilon: float = 1e-9
) -> Polytope:
    """Clip ``seed`` by every half-space in turn.

    The seed bounds the result: a half-space intersection is unbounded in general, and this
    is where that is resolved — by starting from a box big enough to contain anything the
    caller cares about rather than by a separate clamping pass.
    """
    poly = seed
    for normal, offset in planes:
        poly = clip_polytope(poly, normal, offset, epsilon)
        if poly.is_empty():
            return poly
    return poly


def face_planes(
    vertices: list[Vec3], triangles: list[Tri], epsilon: float = 1e-9
) -> list[tuple[Vec3, float]]:
    """Outward face planes of a mesh, deduplicated.

    For a convex mesh these half-spaces *are* the solid, which is what lets a convex source
    be intersected with a Voronoi cell exactly, with no mesh boolean anywhere.
    """
    seen: dict[tuple[int, int, int, int], tuple[Vec3, float]] = {}
    for i, j, k in triangles:
        a, b, c = vertices[i], vertices[j], vertices[k]
        normal = cross(sub(b, a), sub(c, a))
        if length(normal) < epsilon:
            continue
        normal = normalize(normal)
        offset = dot(normal, a)
        key = (*quantise(normal, 1e-6), math.floor(offset / 1e-6 + 0.5))
        seen.setdefault(key, (normal, offset))
    return list(seen.values())


def is_convex(vertices: list[Vec3], triangles: list[Tri], tolerance: float = 1e-6) -> bool:
    """True when every vertex lies on the inner side of every face plane.

    Decides whether the exact polytope path applies. A false negative merely costs the
    slower path; a false positive would silently carve a non-convex part as if it were
    solid, so the tolerance is scaled to the mesh and the test is over *all* vertices.
    """
    planes = face_planes(vertices, triangles)
    if not planes:
        return False
    extent = aabb_of(vertices).max_extent or 1.0
    slack = tolerance * extent
    for normal, offset in planes:
        for v in vertices:
            if dot(normal, v) - offset > slack:
                return False
    return True


def _dedupe_loop(loop: list[int]) -> list[int]:
    out: list[int] = []
    for index in loop:
        if not out or out[-1] != index:
            out.append(index)
    if len(out) > 1 and out[0] == out[-1]:
        out.pop()
    return out


def _chain_loop(segments: list[tuple[int, int]]) -> list[int] | None:
    """Chain directed segments head-to-tail into one closed loop."""
    if not segments:
        return None
    following = {}
    for a, b in segments:
        if a in following:
            return None  # a branching boundary is not a simple loop
        following[a] = b

    start = segments[0][0]
    loop = [start]
    current = start
    for _ in range(len(following)):
        nxt = following.get(current)
        if nxt is None:
            return None
        if nxt == start:
            return loop
        loop.append(nxt)
        current = nxt
    return None
