"""Turning classified islands into the per-part assets D08-S4.2 expects.

Joining, origin placement, the collision hull and the export. The one thing here that is a
decision rather than a mechanism is where each part's origin goes, and it is
:func:`origin_for` — a wheel that does not rotate about its axle is the most visible art
bug a driving game can ship.
"""

from __future__ import annotations

import math
from pathlib import Path

try:  # pragma: no cover - exercised only inside a Blender host
    import bpy  # isort: skip
    import bmesh  # isort: skip
    from mathutils import Matrix, Vector  # isort: skip
except ImportError:  # pragma: no cover
    bpy = bmesh = Matrix = Vector = None  # type: ignore[assignment]

#: Export settings. ``export_yup`` is the Blender-Z-up to game-Y-up conversion and is
#: performed exactly once in the project (D00-R16) — the fracture tool's exporter carries
#: the identical comment for the identical reason.
GLTF_SETTINGS = {
    "export_format": "GLB",
    "export_yup": True,
    "export_apply": True,
    "export_materials": "EXPORT",
    "export_cameras": False,
    "export_lights": False,
    "export_animations": False,
    "export_skins": False,  # poses are already baked; a skin here would re-introduce DISC-016
    "export_morph": True,
    "export_draco_mesh_compression_enable": False,
    "export_texcoords": True,
    "export_normals": True,
}

#: Vertex budget for a collision hull (D06-S5.2 ``MAX_HULL_VERTICES``).
MAX_HULL_VERTICES = 64


def join(islands, name: str):
    """Joins islands into one object named ``name``. Returns it, or None if there are none."""
    return join_objects([i.obj for i in islands], name)


def join_objects(objects, name: str):
    """Joins Blender objects into one named ``name``. Returns it, or None if there are none.

    Split out from :func:`join` for :mod:`syndicate_prepare`, which groups shells rather than
    islands and has no ``Island`` to unwrap. The two tools must join identically or a part cut
    by one and a part cut by the other would sit in different places.
    """
    objects = [obj for obj in objects if obj is not None]
    if not objects:
        return None
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]
    if len(objects) > 1:
        bpy.ops.object.join()
    joined = bpy.context.view_layer.objects.active
    joined.name = name
    joined.data.name = name

    # Unparenting has to preserve the world transform. A part's placement lives partly in
    # its own matrix and partly in its parent's, and clearing `parent` alone keeps only the
    # first — the geometry silently jumps by whatever the parent contributed, and every
    # measurement afterwards is of a part in the wrong place. That is not a hypothetical:
    # it moved every wheel to the mirror of its own axle before this line existed.
    world = joined.matrix_world.copy()
    joined.parent = None
    joined.matrix_world = world

    bpy.ops.object.select_all(action="DESELECT")
    joined.select_set(True)
    bpy.context.view_layer.objects.active = joined
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return joined


def recentre_on(obj, point_world) -> None:
    """Moves the geometry so ``point_world`` lands on the origin, and bakes the move in.

    A wheel's origin is its axle: Bullet spins a wheel about its part origin, so a wheel
    whose geometry is offset from that origin orbits the vehicle instead of rotating. A
    chassis's origin is its own centreline at ground level, which is the space D08-S4.2's
    slot ``localPosition`` values are authored in.

    **Moving the geometry, not the origin.** Blender's own "set origin" leaves the geometry
    in place and compensates with an object transform, which is right for an artist and
    wrong for this: the exporter writes that transform out as a node transform, and the
    game's reader composes node transforms down from the scene root (DEC-035) — so it puts
    the wheel straight back where it was and the part is off-axle by exactly its own axle
    position. Applying the transform afterwards is what makes the two agree, and it is the
    reason this function ends with ``transform_apply`` rather than a comment about origins.
    """
    obj.matrix_world = Matrix.Translation(-Vector(point_world)) @ obj.matrix_world
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def build_collision_hull(obj, name: str):
    """A convex hull over the part, as the ``<partTypeId>_col`` node of D08-R3.

    Convex because that is what the physics takes (D06-R6): every dynamic shape in this game
    is a convex hull or a compound of them, so a collision node that were not convex would
    be silently hulled at load and the asset would lie about its own shape.
    """
    points = _support_points(obj.data.vertices, MAX_HULL_VERTICES)
    source = bmesh.new()
    for p in points:
        source.verts.new(p)
    source.verts.ensure_lookup_table()
    hull = bmesh.ops.convex_hull(source, input=source.verts)
    bmesh.ops.delete(source, geom=hull["geom_unused"] + hull["geom_interior"], context="VERTS")
    mesh = bpy.data.meshes.new(name)
    source.to_mesh(mesh)
    source.free()
    # Smooth-shaded so the exporter shares vertices instead of splitting one per face. A
    # flat-shaded 64-point hull exports as ~350 vertices, all of them duplicates, and the
    # runtime then builds its convex shape from 350 points to arrive at the same 64. The
    # normals themselves are meaningless here: a collision node is never drawn.
    for polygon in mesh.polygons:
        polygon.use_smooth = True
    collision = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(collision)
    collision.matrix_world = obj.matrix_world.copy()
    return collision


def _support_points(vertices, budget: int) -> list:
    """The extreme vertices along ``budget`` evenly spread directions.

    Support sampling rather than edge collapse, for the reason DISC-009 records about
    ``btShapeHull``: this is exactly what Bullet does to a shape at load, so sampling here
    means the hull that ships is the hull the physics uses, instead of a finer one Bullet
    will quietly reduce to its own 42 directions anyway.

    It is also bounded by construction. Collapsing edges until a 205,000-vertex chassis
    hull fits 64 vertices is thousands of iterations and a different answer for every
    change to the mesh; this is one pass and a function of the geometry alone (G3).

    Directions come from a Fibonacci sphere, which spreads them far more evenly than
    latitude-longitude — that clusters at the poles and would sample a wheel's tread
    heavily while missing its sidewalls.
    """
    coords = [v.co.copy() for v in vertices]
    if len(coords) <= budget:
        return coords

    # The six axis directions first, and not as a nicety: they are what make the hull's
    # bounding box exactly the part's. A wheel's radius is read off that box by both the
    # asset checks and `VehicleFactory` (DEC-022), so a hull that is a few millimetres
    # short on +Y gives Bullet a wheel smaller than the tyre and parks the car in the road.
    directions = [
        Vector((1, 0, 0)),
        Vector((-1, 0, 0)),
        Vector((0, 1, 0)),
        Vector((0, -1, 0)),
        Vector((0, 0, 1)),
        Vector((0, 0, -1)),
    ]
    golden = math.pi * (3.0 - math.sqrt(5.0))
    remaining = max(0, budget - len(directions))
    for i in range(remaining):
        y = 1.0 - (2.0 * i) / max(1, remaining - 1)
        r = math.sqrt(max(0.0, 1.0 - y * y))
        theta = golden * i
        directions.append(Vector((math.cos(theta) * r, y, math.sin(theta) * r)))

    chosen: dict[int, object] = {}
    for direction in directions:
        best, best_dot = 0, None
        for index, co in enumerate(coords):
            d = co.dot(direction)
            if best_dot is None or d > best_dot:
                best, best_dot = index, d
        chosen[best] = coords[best]
    return list(chosen.values())


def export_part(visual, collision, out_dir: Path) -> Path:
    """Writes ``mesh.glb`` holding the visual mesh and its collision node."""
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "mesh.glb"
    bpy.ops.object.select_all(action="DESELECT")
    visual.select_set(True)
    if collision is not None:
        collision.select_set(True)
    bpy.context.view_layer.objects.active = visual
    bpy.ops.export_scene.gltf(filepath=str(path), use_selection=True, **GLTF_SETTINGS)
    return path
