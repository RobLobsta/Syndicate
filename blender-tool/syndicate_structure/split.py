"""Cutting the geometry at the band planes, so a monolithic building has floors (D16-S7.1).

:mod:`~syndicate_structure.bands` sorts *pieces* into bands. That is enough for a structure the
artist already modelled in pieces — a turret is 134 objects and every one of them sits at one
height — and it is not enough for the case that matters most: a building arrives as **one object
seventeen metres tall**, so sorting by centroid puts the whole block in the middle band and
produces a single part. Shooting the bottom of it would then delete the entire block, which is
neither a collapse nor cover.

So the geometry is bisected first. Each object that spans more than one band becomes one object
per band it crosses, cut at the band planes and capped where the cut opened it. After that the
sort is exact by construction, and the two halves of the cut — where the plane is, and what ends
up on which side of it — are decided in one place instead of two.

Capping matters for more than tidiness: an open shell has no enclosed volume, and the enclosure
is what caps a part's mass (:mod:`~syndicate_structure.mass`). An uncapped floor would weigh its
full surface reading with nothing to bound it.
"""

from __future__ import annotations

try:  # pragma: no cover - exercised only inside a Blender host
    import bmesh  # isort: skip
    import bpy  # isort: skip
    from mathutils import Vector  # isort: skip
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bpy = bmesh = Vector = None  # type: ignore[assignment]

#: How far inside a band an object's geometry must reach before that band gets a copy of it, in
#: metres. Without it, a floor slab that grazes the plane by a micron produces an empty shell
#: with a name, a hull and a health bar.
SLIVER_M = 0.02


def spans(lo: float, hi: float, edges: list[float]) -> list[int]:
    """Which bands a `[lo, hi]` extent has real geometry in."""
    out = []
    for i in range(len(edges) - 1):
        low, high = max(lo, edges[i]), min(hi, edges[i + 1])
        if high - low > SLIVER_M:
            out.append(i)
    return out


def _bisect(obj, height_m: float, keep_above: bool) -> None:
    """Cut `obj` at world height `height_m` in game Y, dropping one side and capping the hole.

    Game Y is Blender Z, and the object is in world space by the time this runs (every transform
    was applied at export), so the plane is a Blender Z plane and no frame conversion is needed.
    """
    mesh = bmesh.new()
    mesh.from_mesh(obj.data)
    mesh.transform(obj.matrix_world)
    result = bmesh.ops.bisect_plane(
        mesh,
        geom=list(mesh.verts) + list(mesh.edges) + list(mesh.faces),
        plane_co=Vector((0.0, 0.0, height_m)),
        plane_no=Vector((0.0, 0.0, 1.0)),
        # `inner` is the side the normal points away from and `outer` is the side it points
        # towards, so keeping what is above the plane means clearing the *inner* half. Getting
        # this the other way round produces bands that hold the geometry of their neighbours and
        # a middle band that is empty, which is exactly as wrong and much harder to see.
        clear_inner=keep_above,
        clear_outer=not keep_above,
    )
    cut_edges = [e for e in result["geom_cut"] if isinstance(e, bmesh.types.BMEdge)]
    if cut_edges:
        # `holes_fill` rather than `edgenet_fill`: the cut is a closed loop through a closed
        # shell, which is precisely the case the first one is for, and the second one produces
        # a fan across concave sections.
        bmesh.ops.holes_fill(mesh, edges=cut_edges)
    mesh.transform(obj.matrix_world.inverted())
    mesh.to_mesh(obj.data)
    mesh.free()
    obj.data.update()


def split_at_bands(edges: list[float]) -> int:
    """Replace every object spanning more than one band with one object per band.

    Returns how many objects the scene gained. Deterministic: objects are processed in name
    order and each copy is named ``<object>__b<n>``, so two runs of the tool over one model
    produce the same object set in the same order (G3).
    """
    if len(edges) <= 2:
        return 0
    gained = 0
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    for obj in sorted(meshes, key=lambda o: o.name):
        if not obj.data.vertices:
            continue
        world = obj.matrix_world
        heights = [(world @ v.co).z for v in obj.data.vertices]
        crossed = spans(min(heights), max(heights), edges)
        if len(crossed) <= 1:
            continue
        for band in crossed:
            copy = obj.copy()
            copy.data = obj.data.copy()
            copy.name = f"{obj.name}__b{band}"
            bpy.context.scene.collection.objects.link(copy)
            if band > 0:
                _bisect(copy, edges[band], keep_above=True)
            if band < len(edges) - 2:
                _bisect(copy, edges[band + 1], keep_above=False)
            gained += 1
        bpy.data.objects.remove(obj, do_unlink=True)
        gained -= 1
    bpy.context.view_layer.update()
    return gained
