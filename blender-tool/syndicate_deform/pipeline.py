"""The deform tool's pipeline: subdivide, author morphs, export, verify, publish.

The same shape as :mod:`syndicate_fracture.pipeline` and for the same reasons — outputs are staged
in a temp directory and moved into place only after verification passes (D09-R2), so a failed run
never leaves a half-authored mesh behind for the harness to trip over.

Two stages, not seven. Subdivision is here rather than in the orchestrator because it exists only
to serve deformation: D15-S5.7 gives ``SHEET_METAL`` a fine lattice and ``STRUCTURAL`` a coarse one
precisely so that the dent lands as a dent and not as a facet, and a tool that authored the morphs
without the density they need would produce technically-valid rubbish.
"""

from __future__ import annotations

import json
import shutil
import tempfile
from pathlib import Path
from typing import Any

from syndicate_fracture import blender, exporter
from syndicate_fracture.errors import (
    EXIT_INPUT_GEOMETRY_INVALID,
    EXIT_OUTPUT_WRITE_FAILED,
    EXIT_SHAPEKEY_FAILED,
    ToolError,
    log,
)
from syndicate_policy.classes import DEFORM, treatment

from . import TOOL_VERSION
from .cli import Args
from .manifest import MANIFEST_FILE, build
from .morphs import generate_damage_morphs, morph_names
from .selfverify import run as self_verify
from .subdivide import subdivide_to


def run(args: Args) -> dict[str, Any]:
    """Author damage morphs on every selected object. Raises ``ToolError`` on failure."""
    blender.require_bpy()
    blender_version = blender.blender_version()
    log(
        "INFO",
        f"syndicate_deform {TOOL_VERSION} on Blender {blender_version}, seed {args.seed}, "
        f"DEFORM transform for a {args.destruction_class} part",
    )

    blender.load_input(args.input)
    names = [obj.name for obj in blender.mesh_objects(args.object)]
    log("INFO", f"processing {len(names)} object(s): {names}")

    results: list[tuple[str, dict[str, Any], Path]] = []
    temp_root = Path(tempfile.mkdtemp(prefix="syndicate_deform_"))
    try:
        for name in names:
            # Reloaded per object for the reason the fracture tool reloads: the export's
            # re-import check resets the scene (D09-R15) and invalidates every reference the
            # next object would have held.
            blender.load_input(args.input)
            document, staged = _process_one(
                blender.mesh_objects(name)[0], args, blender_version, temp_root
            )
            results.append((name, document, staged))

        for name, document, staged in results:
            _publish(staged, args.out, document)
            log("INFO", f"published '{name}' to {args.out}")

        summary = _summary(results, args)
        if args.report is not None:
            _write_report(args.report, summary)
        return summary
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)


def plan(args: Args) -> dict[str, Any]:
    """The ``--dry-run`` plan: what would happen, changing nothing."""
    resolved = treatment(args.destruction_class)
    return {
        "ok": True,
        "dryRun": True,
        "toolVersion": TOOL_VERSION,
        "transform": str(DEFORM),
        "destructionClass": args.destruction_class,
        "input": str(args.input),
        "out": str(args.out),
        "object": args.object,
        "seed": args.seed,
        "parameters": args.parameters_block(),
        "subdivideEdgeM": resolved.subdivide_edge_m if args.subdivide else 0.0,
        "stages": [
            "1 subdivide to the class's target edge length"
            if args.subdivide and resolved.subdivide_edge_m > 0.0
            else "1 subdivide (skipped)",
            "2 author damage morphs",
            "3 glTF export" if not args.no_export else "3 glTF export (skipped)",
            "4 self-verification",
        ],
    }


# --- Per-object pipeline ---------------------------------------------------------------


def _process_one(obj, args: Args, blender_version: str, temp_root: Path):
    name = obj.name
    staged = temp_root / name
    staged.mkdir(parents=True, exist_ok=True)

    blender.apply_transforms(obj)
    if not obj.data.vertices or not obj.data.polygons:
        raise ToolError(EXIT_INPUT_GEOMETRY_INVALID, f"'{name}' has no geometry", object=name)

    resolved = treatment(args.destruction_class)

    # --- Stage 1: subdivision -----------------------------------------------------------
    subdivided_from = len(obj.data.polygons)
    if args.subdivide and resolved.subdivide_edge_m > 0.0:
        subdivide_to(obj, resolved.subdivide_edge_m)
        log("INFO", f"'{name}' subdivided {subdivided_from} -> {len(obj.data.polygons)} faces")

    # --- Stage 2: morphs ----------------------------------------------------------------
    _refuse_existing_morphs(obj)
    morphs = generate_damage_morphs(
        obj, levels=args.levels, amplitude=args.amplitude, seed=args.seed
    )
    if not morphs:
        raise ToolError(
            EXIT_SHAPEKEY_FAILED, f"'{name}' produced no damage morphs", object=name
        )
    log("INFO", f"'{name}' authored {len(morphs)} damage morphs")

    # Everything read off `obj` must be read HERE, before the export below. The round-trip
    # check re-imports into a fresh scene (D09-R15), which frees every object in the old one —
    # and Blender does not fail politely on a freed reference, it raises `ReferenceError:
    # StructRNA of type Object has been removed` from whatever line touches it next. The
    # fracture pipeline has always ordered itself this way; this one had to learn.
    subdivided_to = len(obj.data.polygons)

    # --- Stage 3: export ----------------------------------------------------------------
    exported_morph_names: list[str] | None = None
    if args.keep_blend:
        # Before the export, for the same reason: saving needs the live scene.
        blender.save_blend(staged / "processed.blend")
    if not args.no_export:
        mesh_glb = exporter.export_gltf(obj, [], staged)[0]
        exported_morph_names = exporter.reimport_morph_target_names(mesh_glb)

    # --- Manifest + stage 4 -------------------------------------------------------------
    document = build(
        part_type_id=name,
        source_path=args.input,
        blender_version=blender_version,
        args=args,
        morphs=morphs,
        subdivided_from=subdivided_from,
        subdivided_to=subdivided_to,
    )
    report = self_verify(
        morphs=morphs, manifest=document, exported_morph_names=exported_morph_names
    )
    if not report.passed:
        failures = [c for c in report.checks if c.failed]
        raise ToolError(
            report.worst_code(),
            f"self-verification failed for '{name}': {len(failures)} check(s)",
            object=name,
            failures=[c.to_json() for c in failures],
        )
    document["verification"] = report.to_json()
    for warning in report.warnings:
        log("WARN", f"'{name}': {warning.name} — {warning.measured}")
    return document, staged


def _refuse_existing_morphs(obj) -> None:
    """Drop this tool's own ``dmg_*`` keys so a re-run replaces rather than duplicates them.

    ``shape_key_add`` does not overwrite: handed a name already taken it appends ``.001``, and
    the mesh exports carrying eight morph targets where the manifest promises four. A source
    arrives carrying them whenever it has been through this tool before, which is the normal
    case for an idempotent pipeline (DISC-065).

    Only the four names this tool owns are removed. A key authored elsewhere under another name
    is somebody else's and is left alone. Note the asymmetry with
    :func:`syndicate_fracture.pipeline._refuse_existing_morphs`, which *refuses* rather than
    replacing: re-authoring your own transform is idempotence, and finding somebody else's on a
    part that must not have it is a content error.
    """
    from syndicate_policy.classes import MORPH_LEVELS

    mesh = obj.data
    if mesh.shape_keys is None:
        return
    owned = set(MORPH_LEVELS)
    for key_block in list(mesh.shape_keys.key_blocks):
        if key_block.name in owned:
            obj.shape_key_remove(key_block)


# --- Publication -----------------------------------------------------------------------


def _publish(staged: Path, out_root: Path, document: dict[str, Any]) -> None:
    try:
        out_root.mkdir(parents=True, exist_ok=True)
        (staged / MANIFEST_FILE).write_text(
            json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        for item in sorted(staged.iterdir()):
            destination = out_root / item.name
            if destination.exists():
                destination.unlink()
            shutil.move(str(item), str(destination))
    except OSError as exc:
        raise ToolError(
            EXIT_OUTPUT_WRITE_FAILED, f"could not write outputs to {out_root}: {exc}"
        ) from exc


def _write_report(path: Path, summary: dict[str, Any]) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as exc:
        raise ToolError(
            EXIT_OUTPUT_WRITE_FAILED, f"could not write report to {path}: {exc}"
        ) from exc


def _summary(results, args: Args) -> dict[str, Any]:
    return {
        "ok": True,
        "exitCode": 0,
        "toolVersion": TOOL_VERSION,
        "transform": str(DEFORM),
        "destructionClass": args.destruction_class,
        "out": str(args.out),
        "seed": args.seed,
        "objects": [
            {
                "partTypeId": name,
                "morphTargets": document["morphTargets"],
                "verificationPassed": document["verification"]["passed"],
                "warnings": document["verification"]["warnings"],
            }
            for name, document, _ in results
        ],
    }


__all__ = ["morph_names", "plan", "run"]
