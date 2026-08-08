"""Stage 2: Voronoi fracturing (D09-S5.2).

D09-S5.2 drives Blender's Cell Fracture add-on, which wraps voro++. That add-on is not part
of Blender's core distribution as of 4.2 — it moved to the extensions platform and is absent
from both a stock install and the ``bpy`` module. Rather than depend on a download, the cells
are constructed directly: a Voronoi cell *is* the intersection of the half-spaces bounded by
the perpendicular bisectors to every other site, so clipping a copy of the source mesh by
those planes yields the same cell the add-on would, intersected with the source in the same
step. See DEV-002 for the recorded deviation.

That identity holds only while the source is convex, so a non-convex source is first split
into disjoint convex pieces by :mod:`decompose`; ``cell ∩ source`` is then the union of the
cell's exact intersection with each piece. See DEV-004.

Everything else D09-S5.2 requires is unchanged: sites come from the tool's PCG32, are sorted
lexicographically before use, and shards are re-sorted by quantised centroid afterwards
(D09-R10), sub-minimum cells are merged rather than dropped (D09-R11), and there is no
recursion (D09-R9).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from . import blender, decompose
from .blender import Vector, bmesh, require_bpy
from .cli import Args
from .errors import EXIT_FRACTURE_FAILED, ToolError, log
from .geometry import (
    Aabb,
    Polytope,
    Tri,
    Vec3,
    aabb_of,
    add,
    distance,
    face_planes,
    hull_planes,
    intersect_halfspaces,
    is_convex,
    mesh_centroid,
    mesh_volume,
    normalize,
    quantise,
    scale,
    sub,
    surface_area,
    triangle_area,
    vertex_normals,
)
from .rng import Pcg32, mix, stable_hash
from .sites import bisector_planes, impact_biased_sites, surface_biased_sites, uniform_sites

# Cells are clipped a hair inside their true bisector so neighbours do not share exactly
# coplanar faces. Bullet's convex hulls handle touching faces badly — two shards resting on
# a shared plane jitter against each other — and the gap is far below any visible scale.
#
# The value is 10 um rather than the more obvious 100 um because the margin costs volume in
# proportion to the total cut area, and mass conservation (G7) is checked at 2%. A 2 x 1 x
# 0.1 m plate cut into 16 cells has enough cut area that a 100 um margin loses 2.5% of the
# part's mass — a real fracture failing a real check, for a gap nothing can see.
_CELL_MARGIN_M = 1e-5


@dataclass
class Shard:
    """One fractured cell, with everything the manifest and the exporter need."""

    index: int
    name: str
    obj: object = None
    vertices: list[Vec3] = field(default_factory=list)
    triangles: list[Tri] = field(default_factory=list)
    volume_m3: float = 0.0
    mass_kg: float = 0.0
    centroid: Vec3 = (0.0, 0.0, 0.0)
    aabb: Aabb | None = None
    hull_vertex_count: int = 0
    neighbors: list[int] = field(default_factory=list)

    @property
    def vertex_count(self) -> int:
        return len(self.vertices)

    @property
    def face_count(self) -> int:
        return len(self.triangles)


def voronoi_fracture(obj, args: Args) -> list[Shard]:
    """Fracture ``obj`` into shards (D09-S5.2). Raises ``ToolError(68)`` on failure."""
    require_bpy()

    source_vertices, source_triangles = blender.read_mesh(obj)
    bbox = aabb_of(source_vertices)
    rng = Pcg32(seed=mix(args.seed, stable_hash(obj.name)))

    sites = _generate_sites(args, rng, bbox, source_vertices, source_triangles)
    if len(sites) < 2:
        raise ToolError(
            EXIT_FRACTURE_FAILED,
            "could not place at least two fracture sites",
            placed=len(sites),
            requested=args.shards,
        )
    log("INFO", f"placed {len(sites)} fracture sites for '{obj.name}' (mode {args.shard_mode})")

    cells = _build_cells(obj, sites, bbox, source_vertices, source_triangles)
    cells = [cell for cell in cells if cell is not None]
    if not cells:
        raise ToolError(EXIT_FRACTURE_FAILED, "clipping produced no cells")

    shards = _postprocess(cells, args)
    if len(shards) < 2:
        raise ToolError(
            EXIT_FRACTURE_FAILED,
            "all cells merged away; lower --min-shard-volume",
            surviving=len(shards),
            minShardVolume=args.min_shard_volume,
        )

    _compute_neighbours(shards)
    _materialise(shards, obj)
    return shards


# --- Site generation -------------------------------------------------------------------


def _generate_sites(
    args: Args,
    rng: Pcg32,
    bbox: Aabb,
    vertices: list[Vec3],
    triangles: list[Tri],
) -> list[Vec3]:
    inside = _inside_predicate(vertices, triangles)
    if args.shard_mode == "uniform":
        return uniform_sites(args.shards, bbox, rng, inside)
    if args.shard_mode == "surface_biased":
        sampler = _surface_sampler(vertices, triangles)
        return surface_biased_sites(args.shards, bbox, rng, sampler)
    impact = args.impact_point or bbox.center
    return impact_biased_sites(args.shards, bbox, rng, impact, _clamp_into(bbox))


def _inside_predicate(vertices: list[Vec3], triangles: list[Tri]):
    """A point-in-mesh test by ray parity along +X.

    Exact for the watertight meshes the tool requires (validated in stage 1), and it needs no
    spatial structure for meshes of this size. The ray direction is fixed rather than random
    so the answer never changes between runs (G11); a vertex-grazing ray is the known failure
    mode, and the tie-break below resolves it consistently by treating the ray as passing
    just above each vertex.
    """

    def inside(point: Vec3) -> bool:
        crossings = 0
        py, pz = point[1], point[2]
        for i, j, k in triangles:
            a, b, c = vertices[i], vertices[j], vertices[k]
            hit = _ray_x_hits_triangle(point, py, pz, a, b, c)
            if hit is not None and hit > point[0]:
                crossings += 1
        return crossings % 2 == 1

    return inside


def _ray_x_hits_triangle(
    point: Vec3, py: float, pz: float, a: Vec3, b: Vec3, c: Vec3
) -> float | None:
    """Where a +X ray through ``(py, pz)`` crosses a triangle, projected onto the YZ plane."""
    ay, az = a[1] - py, a[2] - pz
    by, bz = b[1] - py, b[2] - pz
    cy, cz = c[1] - py, c[2] - pz

    d1 = ay * bz - az * by
    d2 = by * cz - bz * cy
    d3 = cy * az - cz * ay
    if not ((d1 >= 0 and d2 >= 0 and d3 >= 0) or (d1 <= 0 and d2 <= 0 and d3 <= 0)):
        return None
    total = d1 + d2 + d3
    if abs(total) < 1e-18:
        return None
    # Barycentric weights in the projected plane give the X of the crossing directly.
    return (d2 * a[0] + d3 * b[0] + d1 * c[0]) / total


def _surface_sampler(vertices: list[Vec3], triangles: list[Tri]):
    """Area-weighted uniform sampling over the surface, with the local normal."""
    areas = [triangle_area(vertices[i], vertices[j], vertices[k]) for i, j, k in triangles]
    total = sum(areas) or 1.0
    cumulative: list[float] = []
    running = 0.0
    for area in areas:
        running += area / total
        cumulative.append(running)
    normals = vertex_normals(vertices, triangles)

    def sample(rng: Pcg32) -> tuple[Vec3, Vec3]:
        target = rng.next_float()
        index = next((n for n, c in enumerate(cumulative) if c >= target), len(triangles) - 1)
        i, j, k = triangles[index]
        # Square-root warp keeps the sample uniform over the triangle's area rather than
        # bunching it toward the first vertex.
        u = rng.next_float()
        v = rng.next_float()
        su = u**0.5
        w = (1.0 - su, su * (1.0 - v), su * v)
        point = add(
            add(scale(vertices[i], w[0]), scale(vertices[j], w[1])), scale(vertices[k], w[2])
        )
        normal = normalize(
            add(add(scale(normals[i], w[0]), scale(normals[j], w[1])), scale(normals[k], w[2]))
        )
        return point, normal

    return sample


def _clamp_into(bbox: Aabb):
    def clamp(point: Vec3) -> Vec3:
        return tuple(  # type: ignore[return-value]
            min(max(point[axis], bbox.min[axis]), bbox.max[axis]) for axis in range(3)
        )

    return clamp


# --- Cell construction -----------------------------------------------------------------


def _build_cells(obj, sites: list[Vec3], bbox: Aabb, vertices: list[Vec3], triangles: list[Tri]):
    """Every Voronoi cell, intersected with the source.

    Three paths, in order of preference:

    * **Convex source** — the source *is* a set of half-spaces (its own face planes), so the
      cell and the source intersect as one half-space intersection. That is exact: no mesh
      cutting, no fill, no boolean, and the result is a Voronoi cell by construction rather
      than by approximation. This is what DEV-005 asked for.
    * **Non-convex source, decomposable** — the source is first decomposed into disjoint
      convex pieces (D09-S5.2, ``decompose``), and the cell is intersected with each piece by
      the same exact construction. ``cell ∩ source`` is then the union of ``cell ∩ piece``
      over the pieces, exact for the same reason. This is what resolves DEV-004.
    * **Non-convex source, not decomposable** — falls back to cutting the mesh, which is
      approximate on a curved source (DEV-005) and wrong on a cavity (DEV-004). Reaching it
      means the decomposition reported *why* it could not run, which is logged.
    """
    if is_convex(vertices, triangles):
        planes = face_planes(vertices, triangles)
        log("INFO", f"source is convex ({len(planes)} face planes): using exact half-space cells")
        return [_cell_exact(site, sites, bbox, [planes]) for site in sites]

    result = decompose.convex_decomposition(vertices, triangles)
    if result.ok:
        log(
            "INFO",
            f"source is non-convex: decomposed into {len(result.pieces)} convex pieces "
            f"({result.volume_m3:.6f} m3, {100 * result.volume_error_frac:.6f}% from source)",
        )
        pieces = [piece.planes for piece in result.pieces]
        boxes = [piece.aabb for piece in result.pieces]
        return [_cell_exact(site, sites, bbox, pieces, boxes) for site in sites]

    log("WARN", f"convex decomposition unavailable ({result.reason}): cutting the mesh instead")
    return [_clip_cell(obj, site, sites) for site in sites]


def _cell_exact(
    site: Vec3,
    sites: list[Vec3],
    bbox: Aabb,
    pieces: list[list[tuple[Vec3, float]]],
    boxes: list[Aabb] | None = None,
):
    """``cell(site) ∩ source`` as an exact convex polytope, or a union of them.

    ``pieces`` is the source as convex regions, each a list of half-spaces. A convex source is
    the one-piece case; a decomposed one contributes a polytope per piece the cell reaches,
    and the shard is their union. The pieces are disjoint, so the union's volume is the sum of
    theirs and no part of the source is counted twice — which is exactly what the mesh-cutting
    path could not guarantee across a cavity.

    Bisectors are applied first: they cut the seed box down to a small cell in a few planes,
    so the several hundred source planes that follow mostly hit the "wholly inside" early-out
    and cost one dot product per vertex. The per-piece AABBs cull most pieces before even
    that, once there is more than one.

    The cell margin insets the *bisectors* only. Insetting the source planes too would shrink
    the part itself, losing volume at the outer surface where there is no neighbour to
    separate from — and insetting a piece's internal planes would open a gap *inside* a shard,
    where the two sides of a decomposition cut have to meet exactly.
    """
    bisectors = [
        (normal, offset - _CELL_MARGIN_M)
        for normal, offset in bisector_planes(site, [s for s in sites if s != site])
    ]

    pad = max(bbox.max_extent, 1.0)
    seed = Polytope.box(
        tuple(bbox.min[i] - pad for i in range(3)),  # type: ignore[arg-type]
        tuple(bbox.max[i] + pad for i in range(3)),  # type: ignore[arg-type]
    )
    cell = intersect_halfspaces(seed, bisectors)
    if cell.is_empty():
        return None
    cell_box = aabb_of(cell.vertices)

    vertices: list[Vec3] = []
    triangles: list[Tri] = []
    for index, planes in enumerate(pieces):
        if boxes is not None and not _aabb_touch(cell_box, boxes[index], 0.0):
            continue
        poly = intersect_halfspaces(cell, planes)
        if poly.is_empty():
            continue
        part = poly.triangles()
        if len(poly.vertices) < 4 or len(part) < 4:
            continue
        base = len(vertices)
        vertices.extend(poly.vertices)
        triangles.extend((i + base, j + base, k + base) for i, j, k in part)

    if len(vertices) < 4 or len(triangles) < 4:
        return None
    return (vertices, triangles)


def _clip_cell(obj, site: Vec3, sites: list[Vec3]):
    """A copy of the source mesh clipped to ``site``'s Voronoi cell.

    Each bisector is applied with ``bisect_plane(clear_outer=True)`` and the cut is filled,
    so the result stays a closed solid at every step — which is what makes the volume
    measurement that follows meaningful. Clipping the *source mesh* rather than an unbounded
    cell is what performs the boolean intersection D09-S5.2 delegates to the add-on.
    """
    require_bpy()
    others = [s for s in sites if s != site]
    planes = bisector_planes(site, others)

    bm = bmesh.new()
    try:
        bm.from_mesh(obj.data)
        for normal, offset in planes:
            if not bm.faces:
                return None
            plane_co = scale(normal, offset - _CELL_MARGIN_M)
            result = bmesh.ops.bisect_plane(
                bm,
                geom=bm.verts[:] + bm.edges[:] + bm.faces[:],
                plane_co=plane_co,
                plane_no=normal,
                clear_outer=True,
                clear_inner=False,
            )
            cut = [g for g in result["geom_cut"] if isinstance(g, bmesh.types.BMEdge)]
            if cut:
                # `triangle_fill` rather than `holes_fill`: the cut boundary of a mesh with
                # an internal cavity is two nested loops in one plane, and `holes_fill`
                # bridges them into a single face that swallows the cavity — which is how a
                # hollow box measured six times its own mass. A constrained triangulation
                # in the cut plane treats the inner loop as a hole, which is what it is.
                # `normal` names the cut plane, so the triangulation is planar rather than
                # a best-fit through a boundary the solver would otherwise have to guess.
                bmesh.ops.triangle_fill(
                    bm,
                    edges=cut,
                    use_beauty=False,
                    use_dissolve=False,
                    normal=Vector(normal),
                )
        if not bm.faces:
            return None

        bmesh.ops.remove_doubles(bm, verts=bm.verts[:], dist=1e-5)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
        bmesh.ops.triangulate(bm, faces=bm.faces[:])
        bm.verts.ensure_lookup_table()

        vertices: list[Vec3] = [(v.co.x, v.co.y, v.co.z) for v in bm.verts]
        triangles: list[Tri] = [
            (f.verts[0].index, f.verts[1].index, f.verts[2].index) for f in bm.faces
        ]
        if len(vertices) < 4 or len(triangles) < 4:
            return None
        return (vertices, triangles)
    finally:
        bm.free()


def _postprocess(cells: list[tuple[list[Vec3], list[Tri]]], args: Args) -> list[Shard]:
    """Merge slivers, then order and name deterministically (D09-R10, D09-R11)."""
    measured = []
    for vertices, triangles in cells:
        volume = mesh_volume(vertices, triangles)
        measured.append((volume, vertices, triangles))

    kept = [m for m in measured if m[0] >= args.min_shard_volume]
    merged = [m for m in measured if m[0] < args.min_shard_volume]
    if merged:
        # D09-R11: merged into the nearest survivor, never dropped and never mass-clamped.
        # Dropping would break mass conservation (G7); clamping the mass would break the
        # volume x density relation the whole pipeline rests on (D06-R3).
        log("INFO", f"merging {len(merged)} sub-minimum cells into their nearest neighbours")
        for volume, vertices, triangles in merged:
            if not kept:
                break
            centroid = mesh_centroid(vertices, triangles)
            target = min(kept, key=lambda k: distance(mesh_centroid(k[1], k[2]), centroid))
            index = kept.index(target)
            base_count = len(target[1])
            combined_vertices = target[1] + vertices
            combined_triangles = target[2] + [
                (i + base_count, j + base_count, k + base_count) for i, j, k in triangles
            ]
            kept[index] = (
                target[0] + volume,
                combined_vertices,
                combined_triangles,
            )

    # Sorted by quantised centroid, never by creation order: the clipping order is a
    # function of site order, and relying on it would make shard numbering fragile in
    # exactly the way D09-R10 warns about.
    kept.sort(key=lambda k: quantise(mesh_centroid(k[1], k[2])))

    shards: list[Shard] = []
    for index, (volume, vertices, triangles) in enumerate(kept):
        shards.append(
            Shard(
                index=index,
                name=f"shard_{index:03d}",
                vertices=vertices,
                triangles=triangles,
                volume_m3=volume,
                centroid=mesh_centroid(vertices, triangles),
                aabb=aabb_of(vertices),
            )
        )
    return shards


def _compute_neighbours(shards: list[Shard]) -> None:
    """Adjacency by AABB touch, recorded in the manifest as informational (D09-R8).

    Exact shared-face adjacency is not recoverable after the cell margin separates
    neighbours, so proximity stands in for it. The field feeds nothing in the game today —
    it exists for future progressive fracture — so an approximate answer is the right cost.
    """
    for shard in shards:
        assert shard.aabb is not None
        neighbours = []
        for other in shards:
            if other.index == shard.index or other.aabb is None:
                continue
            if _aabb_touch(shard.aabb, other.aabb, _CELL_MARGIN_M * 20):
                neighbours.append(other.index)
        shard.neighbors = sorted(neighbours)


def _aabb_touch(a: Aabb, b: Aabb, slack: float) -> bool:
    return all(a.min[i] - slack <= b.max[i] and b.min[i] - slack <= a.max[i] for i in range(3))


def _materialise(shards: list[Shard], template) -> None:
    """Create the Blender objects the exporter will write out."""
    for shard in shards:
        faces = [list(tri) for tri in shard.triangles]
        shard.obj = blender.new_mesh_object(shard.name, shard.vertices, faces, template=template)


def source_metrics(vertices: list[Vec3], triangles: list[Tri]) -> dict[str, float]:
    """Handy summary for logs and the dry-run plan."""
    return {
        "volumeM3": mesh_volume(vertices, triangles),
        "surfaceAreaM2": surface_area(vertices, triangles),
        "vertices": float(len(vertices)),
        "triangles": float(len(triangles)),
    }


__all__ = [
    "Shard",
    "hull_planes",
    "source_metrics",
    "sub",
    "voronoi_fracture",
]
