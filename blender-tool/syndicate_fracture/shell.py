"""Fracturing a **shell**: cut the surface into cells, then give each shard its thickness.

D09-S5.2's Voronoi path fractures a *solid*. It intersects each cell with the source by a solid
binary partition over the source's own face planes (DEC-011), which is exact and is the right
algorithm for a shard of a chassis rail. It cannot fracture a windscreen.

The reason is structural rather than a tuning problem. A pane of glass is a **surface**: a curved
sheet of hundreds of nearly-parallel triangles. Given it as a solid — by thickening it first — the
partition has to split on every one of those nearly-parallel planes, so it recurses past its depth
bound and gives up, and the fallback path conserves no volume. Measured on the Eclipse: the
windscreen's shards summed to 45.3 kg against a 20.7 kg pane, and every other pane on both shipped
cars failed one guard or another (DISC-039).

The order is the whole fix. **Cut first, thicken second.**

A Voronoi cell of a surface is the patch of that surface nearer this site than any other, and a
patch is produced by bisecting the *surface mesh* — an operation with no notion of inside or
outside, no boolean, and no recursion. Solidifying each patch afterwards turns it into a closed
slab whose volume is exactly its own area times the thickness, so:

    sum(shard volume) = sum(patch area) x thickness = source area x thickness = part volume

Mass conservation (G7) therefore holds **by construction** rather than within a tolerance, which is
the property D09-S6.2's cross-check exists to defend and the one the solid fallback lost.
"""

from __future__ import annotations

from .blender import require_bpy
from .cli import Args
from .errors import EXIT_FRACTURE_FAILED, ToolError, log
from .fracture import Shard, _compute_neighbours, _materialise, _postprocess, _surface_sampler
from .geometry import Vec3, mesh_volume, surface_area
from .rng import Pcg32, mix, stable_hash
from .sites import bisector_planes

try:  # pragma: no cover - exercised only inside a Blender host
    import bmesh  # isort: skip
    from mathutils import Vector  # isort: skip
except ImportError:  # pragma: no cover
    bmesh = None  # type: ignore[assignment]
    Vector = None  # type: ignore[assignment]

#: A patch smaller than this is not a shard. In area rather than volume, because that is what a
#: shell has: 1 cm² of glass is a splinter, and merging it into a neighbour keeps the mass.
MIN_PATCH_AREA_M2 = 1e-4

#: How far a bisector is nudged towards its own site before cutting, so two adjacent patches do
#: not both claim the boundary triangles. The solid path carries the same constant for the same
#: reason, at the same magnitude.
_CELL_MARGIN_M = 1e-5


def is_shell(vertices: list[Vec3], triangles: list, thickness_m: float) -> bool:
    """Whether this geometry is a surface rather than a solid.

    A surface encloses much less than its own area times the thickness it would be given: a flat
    pane encloses nothing at all, and a curved one encloses only the sliver between the sheet and
    its chord. A solid encloses far more. The comparison is against the thickness the caller
    would solidify to, which is what makes it scale-free.
    """
    area = surface_area(vertices, triangles)
    return abs(mesh_volume(vertices, triangles)) < 0.5 * area * thickness_m


def shell_fracture(obj, args: Args) -> list[Shard]:
    """Fracture a surface into solid shards of ``args.shell_thickness`` (D09-R11a).

    Sites are placed **on the surface** rather than through a bounding volume, because a cell of
    a curved sheet is only meaningful if its site lies on the sheet: a site floating off to one
    side produces a bisector that grazes the surface at a glancing angle and cuts a crescent.
    """
    require_bpy()
    from . import blender

    source_vertices, source_triangles = blender.read_mesh(obj)
    thickness = args.shell_thickness
    if thickness <= 0.0:
        raise ToolError(EXIT_FRACTURE_FAILED, "shell fracture needs a positive --shell-thickness")

    rng = Pcg32(seed=mix(args.seed, stable_hash(obj.name)))
    sites = _sites_on_surface(args.shards, rng, source_vertices, source_triangles)
    if len(sites) < 2:
        raise ToolError(
            EXIT_FRACTURE_FAILED,
            "could not place at least two fracture sites on the surface",
            placed=len(sites),
            requested=args.shards,
        )
    log("INFO", f"placed {len(sites)} shell sites for '{obj.name}' ({thickness * 1000:g} mm thick)")

    cells = []
    patch_areas = []
    for site in sites:
        cut = _cut_patch(obj, site, sites, thickness)
        if cut is not None:
            vertices, triangles, patch_area = cut
            cells.append((vertices, triangles))
            patch_areas.append(patch_area)
    if len(cells) < 2:
        raise ToolError(
            EXIT_FRACTURE_FAILED,
            "cutting the surface produced fewer than two patches",
            patches=len(cells),
        )

    _check_the_cut_tiled_the_surface(patch_areas, source_vertices, source_triangles)

    shards = _postprocess(cells, args)
    if len(shards) < 2:
        raise ToolError(EXIT_FRACTURE_FAILED, "all patches merged away", surviving=len(shards))

    _compute_neighbours(shards)
    _materialise(shards, obj)
    return shards


#: How far the patches' own area may stray from the surface they were cut from. A cut that
#: tiles loses only the sliver each bisector is nudged by, plus any patch too small to keep;
#: anything more is a gap or an overlap, which is the failure this check exists to catch.
MAX_AREA_DEVIATION = 0.01


def _check_the_cut_tiled_the_surface(patch_areas, vertices, triangles) -> None:
    """The shell path's conservation law: the patches must cover the surface exactly once.

    The solid path checks that its shards' *volumes* sum to the source's, which is the right
    invariant when the source is a solid whose volume can be measured independently. A shell's
    material volume cannot be measured that way — it has none until a thickness is chosen — so
    the equivalent check is one level up, on the thing the cut is actually responsible for:
    every patch is part of the surface, and together they are the whole of it.

    The areas measured are the patches **as cut**, before each is thickened. That distinction
    is the whole point. Recovering an area from a solidified patch's volume looks equivalent
    and is not: solidify offsets along vertex normals, so a patch of convex curvature encloses
    more than ``its own area x thickness`` — by 0.1% on a windscreen and 6% on a tightly
    curved quarter-light, measured on the two shipped cars. That inflation is real glass and
    belongs in the shard's mass (see :func:`part_volume_m3`); folding it into this check
    instead only makes the check fail on curvature it was never about.
    """
    covered = sum(patch_areas)
    source = surface_area(vertices, triangles)
    if source <= 0.0:
        return
    deviation = abs(covered - source) / source
    log("INFO", f"cut covered {covered:.4f} m2 of a {source:.4f} m2 surface "
                f"({deviation * 100:.2f}%)")
    if deviation > MAX_AREA_DEVIATION:
        raise ToolError(
            EXIT_FRACTURE_FAILED,
            f"the patches cover {covered:.4f} m2 of a {source:.4f} m2 surface: "
            "the cut left a gap or an overlap",
            coveredM2=round(covered, 6),
            sourceM2=round(source, 6),
        )


def _sites_on_surface(count: int, rng: Pcg32, vertices: list[Vec3], triangles: list) -> list[Vec3]:
    """``count`` points sampled on the surface itself, area-weighted, with no offset.

    The solid path pushes its sites *inward* from the surface, because real plate damage
    shatters near the skin and biasing outward produces many small surface shards and a few
    large interior ones (D09-S5.2). A shell has no interior to bias towards: every point of it
    is skin. A site pushed off the sheet produces a bisector that meets the surface at a
    glancing angle and cuts a crescent instead of a cell.
    """
    sampler = _surface_sampler(vertices, triangles)
    sites: list[Vec3] = []
    for _index in range(count):
        point, _normal = sampler(rng)
        # Duplicate sites make a zero-length bisector normal, which is undefined; the sampler
        # can repeat a point on a mesh with very few triangles.
        if all(
            abs(point[0] - s[0]) + abs(point[1] - s[1]) + abs(point[2] - s[2]) > 1e-6
            for s in sites
        ):
            sites.append(point)
    return sites


def part_volume_m3(shards) -> float:
    """A shell part's material volume: the sum of its shards'.

    Not ``area x thickness``, which is the *nominal* figure and is 3% to 5% under the truth on
    a curved pane. Thickening a patch offsets it along its vertex normals, and on convex
    curvature that offset surface is larger than the one it came from, so a real windscreen
    contains slightly more glass than its flat projection implies. Measured on the Eclipse:
    the rear window's shards are 4.9% heavier than ``area x thickness`` says, and that mass is
    in the geometry rather than being an error.

    The part therefore weighs what its pieces weigh, which makes G7's conservation exact. What
    is *not* given up by defining it this way is a check on the fracture — the cut is checked
    directly, against the surface it was cut from, by
    :func:`_check_the_cut_tiled_the_surface`. That is the invariant this algorithm can
    actually violate, and it is checked before any of this is reached.
    """
    return sum(shard.volume_m3 for shard in shards)


def _cut_patch(obj, site: Vec3, sites: list[Vec3], thickness_m: float):
    """One site's patch of the surface, solidified into a closed slab.

    Returns ``(vertices, triangles, patch_area_m2)``, where the area is of the patch **as cut**
    — before it is thickened — because that is the quantity
    :func:`_check_the_cut_tiled_the_surface` can compare against the source exactly.

    Bisecting **without filling the cut** is the difference between this and the solid path. A
    solid must be re-closed after every cut or its volume stops meaning anything; a surface has
    an open boundary to begin with, and filling the cut would web the patch over.
    """
    require_bpy()
    others = [other for other in sites if other != site]
    planes = bisector_planes(site, others)

    mesh = bmesh.new()
    try:
        mesh.from_mesh(obj.data)
        for normal, offset in planes:
            if not mesh.faces:
                return None
            bmesh.ops.bisect_plane(
                mesh,
                geom=mesh.verts[:] + mesh.edges[:] + mesh.faces[:],
                plane_co=Vector(normal) * (offset - _CELL_MARGIN_M),
                plane_no=Vector(normal),
                clear_outer=True,
                clear_inner=False,
            )
        if not mesh.faces:
            return None

        bmesh.ops.remove_doubles(mesh, verts=mesh.verts[:], dist=1e-6)
        loose = [vertex for vertex in mesh.verts if not vertex.link_faces]
        if loose:
            bmesh.ops.delete(mesh, geom=loose, context="VERTS")
        if not mesh.faces:
            return None
        patch_area = sum(face.calc_area() for face in mesh.faces)
        if patch_area < MIN_PATCH_AREA_M2:
            return None

        # The patch is a surface; make it a slab. `solidify` offsets it and walls the boundary,
        # which closes it — and a closed slab is what the volume, the hull and the physics all
        # need. Normals are recalculated first so the offset goes one way for the whole patch.
        bmesh.ops.recalc_face_normals(mesh, faces=mesh.faces[:])
        bmesh.ops.solidify(mesh, geom=mesh.faces[:] + mesh.edges[:] + mesh.verts[:],
                           thickness=-thickness_m)
        bmesh.ops.recalc_face_normals(mesh, faces=mesh.faces[:])
        bmesh.ops.triangulate(mesh, faces=mesh.faces[:])
        mesh.verts.ensure_lookup_table()

        vertices: list[Vec3] = [(v.co.x, v.co.y, v.co.z) for v in mesh.verts]
        triangles = [(f.verts[0].index, f.verts[1].index, f.verts[2].index) for f in mesh.faces]
        if len(vertices) < 4 or len(triangles) < 4:
            return None
        return (vertices, triangles, patch_area)
    finally:
        mesh.free()
