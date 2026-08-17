"""The top-level pipeline of D09-S5.1.

Outputs are written to a temp directory and moved into place only after verification passes
(D09-R2, atomicity). A failed run therefore never leaves a half-written asset behind — which
matters because the next thing to read that directory is the harness, and a partial asset
would fail there with a confusing error about the wrong stage.
"""

from __future__ import annotations

import json
import shutil
import tempfile
from pathlib import Path
from typing import Any

from syndicate_policy.classes import FRACTURE

from . import blender, exporter, materials, selfverify
from . import manifest as manifest_module
from .cli import TOOL_VERSION, Args
from .errors import (
    EXIT_INPUT_GEOMETRY_INVALID,
    EXIT_MATERIAL_UNRESOLVED,
    EXIT_OUTPUT_WRITE_FAILED,
    EXIT_TRANSFORM_NOT_PERMITTED,
    ToolError,
    VerificationReport,
    log,
)
from .fracture import Shard, voronoi_fracture
from .geometry import Vec3, aabb_of, boundary_edge_count, is_finite, mesh_volume, surface_area
from .hulls import Hull, build_hull
from .mass import assign_masses
from .shell import part_volume_m3 as shell_part_volume
from .shell import shell_fracture
from .verifyonly import verify_existing


def run(args: Args) -> dict[str, Any]:
    """Process every selected object. Raises ``ToolError`` with a specific exit code."""
    if args.verify_only:
        # Checked before Blender is touched, because the promise of this flag is that it
        # produces no new data and the cheapest way to keep that promise is not to open a
        # scene at all (D09-S4.2).
        return verify_existing(args)

    blender.require_bpy()
    blender_version = blender.blender_version()
    log(
        "INFO",
        f"syndicate_fracture {TOOL_VERSION} on Blender {blender_version}, seed {args.seed}, "
        f"FRACTURE transform for a {args.destruction_class} part",
    )

    table = materials.load(args.material_table)
    blender.load_input(args.input)
    names = [obj.name for obj in blender.mesh_objects(args.object)]
    log("INFO", f"processing {len(names)} object(s): {names}")

    results: list[tuple[str, dict[str, Any], Path]] = []
    temp_root = Path(tempfile.mkdtemp(prefix="syndicate_fracture_"))
    try:
        for name in names:
            # The input is reloaded per object rather than processed in one pass. Stage 6's
            # round-trip check (D09-R15) re-imports into a fresh scene, which invalidates
            # every Blender reference the previous object held — including the next
            # object's. Reloading is cheap and makes each object's run independent, which
            # also means one object's leftover shard collection cannot leak into the next.
            blender.load_input(args.input)
            manifest, staged = _process_one(
                blender.mesh_objects(name)[0], args, table, blender_version, temp_root
            )
            results.append((name, manifest, staged))

        # Atomic publish: nothing becomes visible until every object verified.
        for name, manifest, staged in results:
            _publish(staged, args.out, manifest)
            log("INFO", f"published '{name}' to {args.out}")

        summary = _summary(results, args)
        if args.report is not None:
            _write_report(args.report, summary)
        return summary
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)


def plan(args: Args) -> dict[str, Any]:
    """The ``--dry-run`` plan: what would happen, changing nothing (D09-S4.2)."""
    return {
        "ok": True,
        "dryRun": True,
        "toolVersion": TOOL_VERSION,
        "input": str(args.input),
        "out": str(args.out),
        "object": args.object,
        "seed": args.seed,
        "transform": str(FRACTURE),
        "destructionClass": args.destruction_class,
        "parameters": args.parameters_block(),
        "materialTable": str(args.material_table),
        "materialOverride": args.material_override,
        "expectedMassKg": args.expected_mass,
        "massToleranceFrac": args.mass_tolerance,
        "willExport": not args.no_export,
        "stages": [
            "1 validate source geometry",
            "2 voronoi fracture",
            "3 mass assignment",
            "4 collision hulls",
            "5 glTF export" if not args.no_export else "5 glTF export (skipped)",
            "6 self-verification",
        ],
    }


# --- Per-object pipeline ---------------------------------------------------------------


def _process_one(
    obj,
    args: Args,
    table: materials.MaterialTable,
    blender_version: str,
    temp_root: Path,
) -> tuple[dict[str, Any], Path]:
    name = obj.name
    staged = temp_root / name
    staged.mkdir(parents=True, exist_ok=True)

    # --- Stage 1: validate the source ---------------------------------------------------
    blender.apply_transforms(obj)
    _refuse_existing_morphs(obj, args)
    source_vertices, source_triangles = blender.read_mesh(obj)
    _validate_source(name, source_vertices, source_triangles, args.shell_thickness)
    material_id = _resolve_material(obj, args, table)

    # --- Stage 2: fracture --------------------------------------------------------------
    # A positive `--shell-thickness` says the source is a surface, and a surface is fractured
    # by cutting it and *then* thickening the pieces (D09-S5.2.1). The solid path cannot do
    # it: given a thickened pane it recurses past its depth bound on the sheet's own
    # nearly-parallel faces and conserves no volume (DISC-039).
    if args.shell_thickness > 0.0:
        shards = shell_fracture(obj, args)
    else:
        shards = voronoi_fracture(obj, args)
    log("INFO", f"'{name}' fractured into {len(shards)} shards")

    # --- Stage 3: mass ------------------------------------------------------------------
    masses = assign_masses(
        source_vertices,
        source_triangles,
        shards,
        material_id,
        table,
        args,
        # A shell's material volume is its area times its thickness. Its *enclosed* volume is
        # approximately zero, which is the right answer to a different question.
        part_volume_override=(
            shell_part_volume(shards) if args.shell_thickness > 0.0 else None
        ),
    )
    log(
        "INFO",
        f"'{name}' part mass {masses.part_mass_kg:.3f} kg "
        f"at {masses.density_kg_per_m3} kg/m3",
    )

    # --- Stage 4: hulls -----------------------------------------------------------------
    hulls = _build_hulls(name, obj, shards, source_vertices, args)

    # --- Stage 5: export ----------------------------------------------------------------
    exported_shard_names: list[str] | None = None
    exported_morph_names: list[str] | None = None
    shards_glb: Path | None = None
    if not args.no_export:
        mesh_glb, shards_glb = exporter.export_gltf(obj, shards, staged)
        # Re-import checks come last because they reset the scene (D09-R15).
        exported_shard_names = exporter.reimport_node_names(shards_glb)
        exported_morph_names = exporter.reimport_morph_target_names(mesh_glb)
    if args.keep_blend:
        blender.save_blend(staged / "processed.blend")

    # --- Manifest + stage 6 -------------------------------------------------------------
    bounds = aabb_of(source_vertices)
    part_hull = next(h for h in hulls if h.name == "part")
    document = manifest_module.build(
        part_type_id=name,
        source_path=args.input,
        blender_version=blender_version,
        args=args,
        shards=shards,
        masses=masses,
        part_aabb=(bounds.min, bounds.max),
        part_hull_vertex_count=part_hull.vertex_count,
        part_hull_pieces=1,
    )

    report = selfverify.run(
        shards=shards,
        hulls=hulls,
        manifest=document,
        source_vertices=source_vertices,
        source_triangles=source_triangles,
        args=args,
        exported_shard_names=exported_shard_names,
        exported_morph_names=exported_morph_names,
        shards_glb=shards_glb,
    )
    if not report.passed:
        raise _verification_failure(name, report)
    manifest_module.attach_verification(document, report)

    for warning in report.warnings:
        log("WARN", f"'{name}': {warning.name} — {warning.measured}")
    return document, staged


def _refuse_existing_morphs(obj, args: Args) -> None:
    """Refuse a mesh that already carries damage morphs its class must not have (D15-S5.7).

    No destruction class receives both transforms, so a mesh arriving here with ``dmg_*`` shape
    keys on it is evidence that something authored deformation onto a part that fractures. The
    tool used to *delete* those keys and re-author its own, which is how the two transforms
    stayed tangled together for as long as they did (DISC-068).

    Refusing rather than stripping is the point. Stripping would silently repair the symptom on
    the way past and leave whatever produced it in place; exit 77 sends the caller to the label
    or to the tool they should have run instead.
    """
    from syndicate_policy.classes import MORPH_LEVELS, treatment

    if treatment(args.destruction_class).deform:
        return
    mesh = getattr(obj, "data", None)
    keys = getattr(mesh, "shape_keys", None) if mesh is not None else None
    if keys is None:
        return
    found = sorted(k.name for k in keys.key_blocks if k.name in set(MORPH_LEVELS))
    if found:
        raise ToolError(
            EXIT_TRANSFORM_NOT_PERMITTED,
            f"'{obj.name}' already carries damage morphs {found}, but a "
            f"{args.destruction_class} part does not deform (D15-S5.7). Something authored the "
            f"DEFORM transform onto a part that fractures",
            object=obj.name,
            destructionClass=args.destruction_class,
            morphTargets=found,
        )


def _validate_source(
    name: str, vertices: list[Vec3], triangles: list, shell_thickness: float = 0.0
) -> None:
    """Stage 1 (D09-S5.1, exit 66)."""
    if not vertices or not triangles:
        raise ToolError(EXIT_INPUT_GEOMETRY_INVALID, f"'{name}' has no geometry", object=name)
    if not all(is_finite(v) for v in vertices):
        raise ToolError(
            EXIT_INPUT_GEOMETRY_INVALID, f"'{name}' has NaN or Inf coordinates", object=name
        )
    if shell_thickness > 0.0:
        # A surface legitimately encloses nothing; what it must have is area.
        if surface_area(vertices, triangles) <= 0.0:
            raise ToolError(
                EXIT_INPUT_GEOMETRY_INVALID,
                f"'{name}' has zero surface area",
                object=name,
            )
        return
    # Watertightness before volume, because on an open mesh the volume is not evidence of
    # anything. `mesh_volume` integrates the divergence theorem, which needs a closed surface to
    # be a theorem, and then takes an absolute value — so an open mesh arrives at mass assignment
    # carrying a plausible positive number that is not its volume. Both shipped cars' wheels
    # integrate to a negative number and were being fractured as though they had a volume of
    # 0.058 m3; the shards then summed 26% over and the only symptom was exit 72, three stages
    # later, blaming mass (DISC-065).
    holes = boundary_edge_count(vertices, triangles)
    if holes > 0:
        raise ToolError(
            EXIT_INPUT_GEOMETRY_INVALID,
            f"'{name}' is not watertight: {holes} edges belong to only one face, so it encloses "
            f"no volume and cannot be fractured as a solid. If it is a panel, a pane or any other "
            f"surface, fracture it as a shell with --shell-thickness",
            object=name,
            boundaryEdges=holes,
        )
    volume = mesh_volume(vertices, triangles)
    if volume <= 0.0:
        raise ToolError(
            EXIT_INPUT_GEOMETRY_INVALID,
            f"'{name}' has zero volume: it is probably not watertight",
            object=name,
            volumeM3=volume,
        )


def _resolve_material(obj, args: Args, table: materials.MaterialTable) -> str:
    """The material id, from the override or the object's slot (D09-S6.2, exit 67)."""
    material_id = args.material_override or blender.material_id_of(obj)
    if material_id is None:
        raise ToolError(
            EXIT_MATERIAL_UNRESOLVED,
            f"'{obj.name}' has no material slot and no --material-override was given",
            object=obj.name,
            known=table.ids(),
        )
    table.resolve(material_id)  # raises 67 when unknown; never defaults (D09-R19)
    return material_id


def _build_hulls(
    name: str, obj, shards: list[Shard], source_vertices: list[Vec3], args: Args
) -> list[Hull]:
    """The intact part's hull plus one per shard (D09-S5.5)."""
    collision_source = blender.find_object(f"{name}_col")
    if collision_source is not None:
        hull_input, _ = blender.read_mesh(collision_source)
    else:
        hull_input = source_vertices

    hulls = [build_hull("part", hull_input, args.part_hull_max_verts)]
    for shard in shards:
        hull = build_hull(shard.name, shard.vertices, args.hull_max_verts)
        shard.hull_vertex_count = hull.vertex_count
        hulls.append(hull)
    del obj
    return hulls


def _verification_failure(name: str, report: VerificationReport) -> ToolError:
    failures = [c for c in report.checks if c.failed]
    return ToolError(
        report.worst_code(),
        f"self-verification failed for '{name}': {len(failures)} check(s)",
        object=name,
        failures=[c.to_json() for c in failures],
    )


# --- Publication -----------------------------------------------------------------------


def _publish(staged: Path, out_root: Path, document: dict[str, Any]) -> None:
    """Move the staged outputs into ``--out`` (D09-R2, exit 75)."""
    try:
        out_root.mkdir(parents=True, exist_ok=True)
        (staged / manifest_module.MANIFEST_FILE).write_text(
            json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        for item in sorted(staged.iterdir()):
            destination = out_root / item.name
            if destination.exists():
                destination.unlink()
            shutil.move(str(item), str(destination))
    except OSError as exc:
        raise ToolError(
            EXIT_OUTPUT_WRITE_FAILED,
            f"could not write outputs to {out_root}: {exc}",
            out=str(out_root),
        ) from exc


def _write_report(path: Path, summary: dict[str, Any]) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as exc:
        raise ToolError(
            EXIT_OUTPUT_WRITE_FAILED, f"could not write report to {path}: {exc}"
        ) from exc


def _summary(results: list[tuple[str, dict[str, Any], Path]], args: Args) -> dict[str, Any]:
    """The success document written to stdout (D09-R2)."""
    return {
        "ok": True,
        "exitCode": 0,
        "toolVersion": TOOL_VERSION,
        "out": str(args.out),
        "seed": args.seed,
        "objects": [
            {
                "partTypeId": name,
                "shardCount": document["shardCount"],
                "partMassKg": document["partMassKg"],
                "morphTargets": document["morphTargets"],
                "topologyHash": document["topologyHash"],
                "verificationPassed": document["verification"]["passed"],
                "warnings": document["verification"]["warnings"],
            }
            for name, document, _ in results
        ],
    }
