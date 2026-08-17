"""The Blender boundary: scene loading, mesh extraction, and bmesh helpers.

Every ``bpy`` and ``bmesh`` import in the tool is confined to this module and the stage
modules that genuinely need to build geometry. That keeps the measurement code in
``geometry.py`` testable without Blender, which is what ``:blender-tool:unitTest`` runs.

The tool supports two hosts, because they are the same Blender:

* ``blender --background --factory-startup --python -m syndicate_fracture`` — the D09-R1
  invocation.
* ``python3 -m syndicate_fracture`` with the ``bpy`` PyPI module, which is Blender built as
  a Python extension. See DEV-002: no ``blender`` executable is available in every
  environment the tool must run in, and the module ships the identical 4.2 codebase.
"""

from __future__ import annotations

from pathlib import Path

from .errors import EXIT_BLENDER_ERROR, EXIT_INPUT_INVALID, ToolError
from .geometry import Tri, Vec3

try:  # pragma: no cover - exercised only inside a Blender host
    # `bpy` must be imported first: as a PyPI module it is what registers `bmesh` and
    # `mathutils` as importable, so the alphabetical order an import sorter would prefer
    # fails with a bare ModuleNotFoundError for `bmesh`.
    import bpy  # isort: skip
    import bmesh  # isort: skip
    from mathutils import Vector  # isort: skip

    HAVE_BPY = True
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bmesh = None  # type: ignore[assignment]
    bpy = None  # type: ignore[assignment]
    Vector = None  # type: ignore[assignment]
    HAVE_BPY = False


def require_bpy() -> None:
    """Fail with exit 70 rather than an ``ImportError`` traceback (D09-S4.3)."""
    if not HAVE_BPY:
        raise ToolError(
            EXIT_BLENDER_ERROR,
            "bpy is unavailable: run inside 'blender --background' or install the bpy module",
        )


def blender_version() -> str:
    require_bpy()
    return bpy.app.version_string


def reset_scene() -> None:
    """An empty scene, equivalent to ``--factory-startup`` for the data we touch (D09-R3).

    The module host has no ``--factory-startup`` flag, so the same guarantee is obtained by
    wiping the default scene explicitly. Without it, the startup cube would be picked up as
    a mesh object and silently fractured alongside the real input.
    """
    require_bpy()
    bpy.ops.wm.read_factory_settings(use_empty=True)


def load_input(path: Path) -> None:
    """Load a ``.blend`` or ``.glb`` into an empty scene (D09-R2, exit 65)."""
    require_bpy()
    if not path.is_file():
        raise ToolError(EXIT_INPUT_INVALID, f"input not found: {path}", path=str(path))

    suffix = path.suffix.lower()
    reset_scene()
    try:
        if suffix == ".blend":
            bpy.ops.wm.open_mainfile(filepath=str(path))
        elif suffix in (".glb", ".gltf"):
            bpy.ops.import_scene.gltf(filepath=str(path))
        else:
            raise ToolError(
                EXIT_INPUT_INVALID,
                f"unsupported input format '{suffix}': expected .blend, .glb or .gltf",
                path=str(path),
            )
    except RuntimeError as exc:
        raise ToolError(
            EXIT_INPUT_INVALID, f"could not load {path}: {exc}", path=str(path)
        ) from exc


def save_blend(path: Path) -> None:
    """Write the processed scene to ``path`` — the ``--keep-blend`` of D09-S4.2.

    The flag was in the contract, parsed, and read by nothing (DISC-068). What it is for is
    opening the scene the tool actually produced when a fracture looks wrong: the shard
    objects, their materials and the collision source are all still there, and inspecting them
    beats inferring from a manifest.

    Written into the staging directory like every other output, so a run that fails
    verification leaves no ``.blend`` behind either (D09-R2, atomicity).
    """
    require_bpy()
    path.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=str(path), copy=True, compress=True)


def mesh_objects(name: str | None = None) -> list:
    """Mesh objects to process, in name order (D09-S5.1: deterministic order).

    Collision proxies (``*_col``, D08-R3) are excluded: they are inputs to hull generation,
    not parts to be fractured, and fracturing one would produce a second shard set that
    nothing references.
    """
    require_bpy()
    objects = [
        obj
        for obj in bpy.data.objects
        if obj.type == "MESH"
        and not obj.name.endswith("_col")
        and not obj.name.startswith("shard_")
    ]
    if name is not None:
        objects = [obj for obj in objects if obj.name == name]
        if not objects:
            raise ToolError(EXIT_INPUT_INVALID, f"no mesh object named '{name}'", object=name)
    if not objects:
        raise ToolError(EXIT_INPUT_INVALID, "input contains no mesh objects")
    return sorted(objects, key=lambda o: o.name)


def find_object(name: str):
    require_bpy()
    return bpy.data.objects.get(name)


def apply_transforms(obj) -> None:
    """Bake the object's transform into its mesh data (D08-R2).

    Every downstream measurement — volume, centroid, inertia, the manifest's local
    transforms — is in mesh-local space. An object carrying an unapplied scale would report
    the mass of its unscaled geometry, which is a silent factor error in the vehicle's
    total mass.
    """
    require_bpy()
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def read_mesh(obj) -> tuple[list[Vec3], list[Tri]]:
    """Triangulated vertices and faces in mesh-local space.

    Triangulation happens on a throwaway bmesh copy rather than on the object, so reading a
    mesh never modifies it — the intact mesh must reach the exporter with its authored
    quads, since triangulating it would change the topology hash and defeat G11.
    """
    require_bpy()
    bm = bmesh.new()
    try:
        bm.from_mesh(obj.data)
        bmesh.ops.triangulate(bm, faces=bm.faces[:])
        bm.verts.ensure_lookup_table()
        vertices: list[Vec3] = [(v.co.x, v.co.y, v.co.z) for v in bm.verts]
        triangles: list[Tri] = []
        for face in bm.faces:
            loop = face.verts
            triangles.append((loop[0].index, loop[1].index, loop[2].index))
        return vertices, triangles
    finally:
        bm.free()


def material_id_of(obj) -> str | None:
    """The object's first material slot name, which D09-S6.2 uses as the material id."""
    require_bpy()
    for slot in obj.material_slots:
        if slot.material is not None:
            return slot.material.name
    return None


def new_mesh_object(name: str, vertices: list[Vec3], faces: list[list[int]], template=None):
    """Create a mesh object from raw geometry, linked into the scene collection."""
    require_bpy()
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([Vector(v) for v in vertices], [], faces)
    mesh.validate(verbose=False)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    if template is not None:
        for slot in template.material_slots:
            if slot.material is not None:
                obj.data.materials.append(slot.material)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def delete_objects(objects) -> None:
    require_bpy()
    for obj in list(objects):
        data = obj.data
        bpy.data.objects.remove(obj, do_unlink=True)
        if data is not None and data.users == 0:
            bpy.data.meshes.remove(data)


def select_only(objects) -> None:
    require_bpy()
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        obj.select_set(True)
    if objects:
        bpy.context.view_layer.objects.active = objects[0]
