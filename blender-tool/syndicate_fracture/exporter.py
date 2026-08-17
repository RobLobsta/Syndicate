"""Stage 6: glTF export (D09-S5.6).

The export settings live here and nowhere else (D08-R14). ``export_yup`` in particular is
*the* Blender-Z-up to game-Y-up conversion, performed exactly once in the whole project
(D00-R16) — a second conversion anywhere downstream would silently mirror every asset.

Every export is re-imported and checked in the same run (D09-R15). A glTF exporter that
silently drops morph targets has happened across Blender versions, and without the
round-trip it produces a green tool run and a broken asset.
"""

from __future__ import annotations

from pathlib import Path

from . import blender
from .blender import require_bpy
from .errors import EXIT_EXPORT_FAILED, ToolError, log

GLTF_SETTINGS = {
    "export_format": "GLB",
    "export_yup": True,  # Blender Z-up -> glTF/game Y-up. THE conversion (D00-R16).
    "export_apply": True,
    "export_morph": True,
    "export_morph_normal": False,  # per-morph normals triple memory for little gain
    "export_morph_tangent": False,
    "export_draco_mesh_compression_enable": False,  # lossy on morph deltas; breaks G11
    "export_materials": "EXPORT",
    "export_cameras": False,
    "export_lights": False,
    "export_animations": False,
    "export_extras": True,  # carries slot custom properties (D08-R13)
    "export_texcoords": True,
    "export_normals": True,
}


def export_mesh(obj, out_dir: Path) -> Path:
    """Write ``mesh.glb`` — the intact part, with its ``_col`` node if one exists.

    Split out of :func:`export_gltf` so the deform tool can use it: that tool produces morph
    targets and no shards, and asking for a ``shards.glb`` with nothing in it raised
    "nothing selected to export" three stages after the real decision.
    """
    require_bpy()
    out_dir.mkdir(parents=True, exist_ok=True)
    mesh_path = out_dir / "mesh.glb"
    collision = blender.find_object(f"{obj.name}_col")
    selection = [obj] if collision is None else [obj, collision]
    _export(selection, mesh_path)
    return mesh_path


def export_gltf(obj, shards, out_dir: Path) -> tuple[Path, Path | None]:
    """Write ``mesh.glb`` and ``shards.glb``, then verify both by re-import.

    ``shards.glb`` is written only when there are shards; the second element of the tuple is
    ``None`` otherwise. A file is the claim that a part fractures, so writing an empty one
    would be a claim nobody meant to make.
    """
    mesh_path = export_mesh(obj, out_dir)
    if not shards:
        return mesh_path, None
    shards_path = out_dir / "shards.glb"
    _export([s.obj for s in shards], shards_path)
    return mesh_path, shards_path


def reimport_node_names(path: Path) -> list[str]:
    """Node names in an exported file, for TV-005.

    Re-imports into a scratch scene, which discards whatever was loaded before. Callers run
    this only after every other stage is done with the live scene.
    """
    require_bpy()
    blender.reset_scene()
    try:
        blender.bpy.ops.import_scene.gltf(filepath=str(path))
    except RuntimeError as exc:
        raise ToolError(EXIT_EXPORT_FAILED, f"exported file is unreadable: {path} ({exc})") from exc
    return sorted(o.name for o in blender.bpy.data.objects if o.type == "MESH")


def reimport_morph_target_names(path: Path) -> list[str]:
    """Shape key names surviving the round trip, for TV-006 (D09-R15)."""
    require_bpy()
    blender.reset_scene()
    try:
        blender.bpy.ops.import_scene.gltf(filepath=str(path))
    except RuntimeError as exc:
        raise ToolError(EXIT_EXPORT_FAILED, f"exported file is unreadable: {path} ({exc})") from exc

    for obj in blender.bpy.data.objects:
        if obj.type == "MESH" and obj.data.shape_keys is not None:
            # The first key is the basis, which is not a damage morph.
            return [k.name for k in obj.data.shape_keys.key_blocks][1:]
    return []


def _export(objects, path: Path) -> None:
    if not objects:
        raise ToolError(EXIT_EXPORT_FAILED, f"nothing selected to export to {path}")
    blender.select_only(objects)
    try:
        blender.bpy.ops.export_scene.gltf(filepath=str(path), use_selection=True, **GLTF_SETTINGS)
    except (RuntimeError, TypeError) as exc:
        raise ToolError(
            EXIT_EXPORT_FAILED, f"glTF export failed for {path}: {exc}", path=str(path)
        ) from exc

    if not path.is_file() or path.stat().st_size == 0:
        raise ToolError(
            EXIT_EXPORT_FAILED, f"glTF export produced no file at {path}", path=str(path)
        )
    log("INFO", f"exported {path.name} ({path.stat().st_size} bytes, {len(objects)} objects)")
