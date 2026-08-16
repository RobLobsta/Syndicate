"""The ten stages of D17-S5.1, driven inside a Blender host.

::

    1.  Load and correct frame        units, bore axis, origin at the mount face   (D17-S5.2)
    1b. Normalise materials           the house style                (D15-S5.9, reused unchanged)
    2.  Repair geometry               weld, dissolve, triangulate    (D15-S5.5, reused unchanged)
    3.  Separate into shells          connected components                         (D17-S5.5)
    4.  Label shells                  the weapon cue ensemble                      (D17-S5.6)
    5.  Group shells into sub-parts   one part per label instance                  (D17-S5.7)
    6.  Build the slot graph          parenting, and close the seams               (D17-S5.8)
    7.  Author articulation           what moves and about what                    (D17-S5.9)
    8.  Derive family, stats, mass    from geometry and D01-S4.4                   (D17-S5.10)
    9.  Author destruction per class  morphs and fracture               (D17-S5.11, D09 reused)
    10. Export and self-verify        part.json, mesh.glb, weapon.json             (D17-S5.14)

**Stage 1 runs twice, and that is not a bug.** The bore axis is what the correction is expressed in
terms of, and the bore axis is a measurement — so the model is measured in its raw frame, the
correction is derived from those measurements, the correction is applied, and everything is measured
again. The alternative is asking an operator to author the correction before the tool will run,
which
is the thing DEC-065 removed from the vehicle pipeline and is not worth reintroducing here.

Everything that reads Blender is in this module. Everything that *decides* anything is in the pure
modules beside it — ``bore``, ``cues``, ``grouping``, ``graph``, ``articulate``, ``stats`` — which
is
what lets every decision be unit-tested with no Blender host at all.
"""

from __future__ import annotations

import json
import math
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import ClassVar

from . import articulate, cues, graph, grouping, selfverify, stats
from . import bore as bore_mod
from .labels import CARRIAGE_RADIUS, MOUNT, WEAPON_MAX_SHELLS, WEAPON_MIN_SHELL_TRIANGLES

try:  # pragma: no cover - exercised only inside a Blender host
    import bpy  # isort: skip
    import bmesh  # isort: skip
    from mathutils import Matrix, Vector  # isort: skip

    HAVE_BPY = True
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bmesh = bpy = None  # type: ignore[assignment]
    Matrix = Vector = None  # type: ignore[assignment]
    HAVE_BPY = False


class WeaponError(Exception):
    """A failure reported through an exit code rather than a traceback (D17-R19).

    Carries the partial report when there is one. A self-verification failure is the case that
    matters: the operator needs to see *which* check failed and what the pipeline decided on the way
    there, and a bare exit code sends them back to re-running with a debugger attached.
    """

    def __init__(self, message: str, code: int, report: dict | None = None):
        super().__init__(message)
        self.code = code
        self.report = report


@dataclass
class Options:
    model: Path
    out: Path | None = None
    weapon_id: str | None = None
    family: str | None = None
    size_class: str | None = None
    target_length_m: float | None = None
    seed: int = 1
    style_table: Path = Path("assets/materials/style.json")
    normalise_style: bool = True
    strict: bool = False
    material_table: Path = Path("assets/materials/materials.json")


@dataclass
class Stage:
    name: str
    seconds: float
    detail: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        return {"stage": self.name, "seconds": round(self.seconds, 3), **self.detail}


def run(options: Options) -> dict:
    """Runs all ten stages and returns the D17-S4.7 report."""
    if not HAVE_BPY:
        raise WeaponError("syndicate-weapon needs a Blender host", 70)

    started = time.time()
    stages: list[Stage] = []
    weapon_id = options.weapon_id or _default_id(options.model)

    # ---- Stage 1: load ------------------------------------------------------------------
    with StageTimer("load", stages) as detail:
        source_meta = load_model(options.model)
        detail.update(source_meta)

    # ---- Stage 1b: style ----------------------------------------------------------------
    with StageTimer("style", stages) as detail:
        detail.update(normalise_materials(options))

    # ---- Stage 2: repair ----------------------------------------------------------------
    with StageTimer("repair", stages) as detail:
        detail.update(repair_geometry())

    # ---- Stage 3: separate --------------------------------------------------------------
    with StageTimer("separate", stages) as detail:
        shells, objects = separate_into_shells()
        dropped = drop_duplicate_shells(shells, objects)
        shells = merge_small_shells(shells, objects)
        detail.update({"shells": len(shells), "duplicatesDropped": dropped})

    if not shells:
        raise WeaponError("no geometry survived the repair stage", 80)

    # ---- Stage 4a: the bore, in the raw frame -------------------------------------------
    with StageTimer("bore", stages) as detail:
        try:
            raw_bore = bore_mod.find(shells)
        except ValueError as error:
            raise WeaponError(str(error), 82) from error
        detail.update({
            "axis": _round3(raw_bore.axis),
            "confidence": round(raw_bore.confidence, 3),
            "because": raw_bore.because,
        })

    # ---- Stage 1 (second half): normalise the frame -------------------------------------
    #
    # Before anything is labelled, not after. A downloaded model arrives at an unknown scale — both
    # shipped weapons import at 100x — and every threshold the ensemble measures is a distance. Put
    # the bore on +Z and the model at unit length first, and each of those thresholds becomes a
    # proportion of the gun, which is what it always meant (D17-R23a).
    with StageTimer("normalise", stages) as detail:
        detail.update(normalise_frame(raw_bore))
        shells = remeasure(shells, objects)
        # Re-fit rather than assuming the origin: the axis is +Z by construction now, but the bore
        # *line* runs through the barrel, which sits above the model origin — the correction put
        # that
        # origin on the mount face (D17-R25). Taking (0,0,0) as a point on the bore made every
        # coaxial test measure from the wrong line and cost the shipped machine gun its barrel.
        unit_bore = _fitted_bore(shells)
        detail["boreOrigin"] = _round3(unit_bore.origin)

    # ---- Stage 3b: discard what is not the weapon (D17-R27) ------------------------------
    with StageTimer("discard", stages) as detail:
        shells, discarded = discard_non_weapon(shells, objects, unit_bore)
        detail.update(discarded)
        if not shells:
            raise WeaponError("every shell was discarded as non-weapon geometry", 80)
        unit_bore = _fitted_bore(shells)

    # ---- Stage 4b: label ----------------------------------------------------------------
    with StageTimer("label", stages) as detail:
        cues.label_shells(shells, unit_bore)
        detail.update(_label_summary(shells))

    # ---- Stage 5: group -----------------------------------------------------------------
    with StageTimer("group", stages) as detail:
        parts = grouping.group(shells, unit_bore, weapon_id)
        try:
            grouping.check_count(parts)
        except ValueError as error:
            raise WeaponError(str(error), 83) from error
        _tag_repetition(parts, shells)
        detail.update({"subParts": [p.name for p in parts]})

    # ---- Stage 8a: family and size ------------------------------------------------------
    with StageTimer("classify", stages) as detail:
        family, family_confidence, family_because = stats.derive_family(parts, unit_bore)
        if options.family:
            family, family_confidence = options.family, 1.0
            family_because = "forced with --family"
        size_class, size_because = stats.derive_size(parts, unit_bore, family)
        if options.size_class:
            size_class, size_because = options.size_class, "forced with --size"
        detail.update({
            "family": family, "familyConfidence": round(family_confidence, 3),
            "familyBecause": family_because, "sizeClass": size_class, "sizeBecause": size_because,
        })

    # ---- Stage 1 (third part): scale to the size class's target length -------------------
    with StageTimer("scale", stages) as detail:
        target = options.target_length_m or stats.target_length(size_class)
        detail.update(scale_to_target(target))
        shells = remeasure(shells, objects)
        corrected_bore = _fitted_bore(shells)
        parts = grouping.group(shells, corrected_bore, weapon_id)
        _tag_repetition(parts, shells)
        detail.update({"shells": len(shells), "subParts": len(parts),
                       "boreOrigin": _round3(corrected_bore.origin)})

    # ---- Stage 5b: synthesise the mount's geometry if it was invented --------------------
    with StageTimer("mount", stages) as detail:
        detail.update(realise_synthesised_mount(parts, objects, shells))

    # ---- Stage 6: the slot graph and the seams ------------------------------------------
    with StageTimer("graph", stages) as detail:
        vertices = {s.index: (s.vertex_sample or (s.centroid,)) for s in shells}
        nodes, seams = graph.build(parts, vertices, target)
        bad = [s for s in seams if not s.is_closed]
        detail.update({"seams": [s.as_dict() for s in seams], "openSeams": len(bad)})

    # ---- Stage 7: articulation ----------------------------------------------------------
    with StageTimer("articulate", stages) as detail:
        detail.update({"articulated": articulate.author(
            parts, corrected_bore, family, stats.fire_interval_s(family))})

    # ---- Stage 8b: mass and stats -------------------------------------------------------
    with StageTimer("stats", stages) as detail:
        total_mass = sum(p.mass_kg for p in parts)
        try:
            stats.check_mass(total_mass, size_class)
        except ValueError as error:
            raise WeaponError(str(error), 85) from error
        root = next(p for p in parts if p.label == MOUNT)
        muzzle = stats.muzzle_local(parts, corrected_bore, root.origin)
        bore_origin_local = tuple(corrected_bore.origin[i] - root.origin[i] for i in range(3))
        block = stats.weapon_block(family, parts, corrected_bore, muzzle)
        stat_block = stats.stat_block(family, size_class)
        detail.update({"totalMassKg": round(total_mass, 3), "muzzleLocal": _round3(muzzle)})

    # ---- Stages 9 and 10: author destruction, export ------------------------------------
    produced: list = []
    if options.out is not None:
        with StageTimer("export", stages) as detail:
            produced = export_all(options, parts, nodes, objects, shells, family, size_class,
                                  block, stat_block, muzzle)
            detail.update({"parts": [p.as_dict() for p in produced]})

    manifest = {
        "schemaVersion": "1.0.0",
        "weaponId": weapon_id,
        "family": family,
        "familyConfidence": round(family_confidence, 3),
        "sizeClass": size_class,
        "sourceModel": str(options.model),
        "sourceLicence": source_meta.get("licence", "unknown"),
        "boreAxisLocal": {"x": 0.0, "y": 0.0, "z": 1.0},
        # Where the bore line sits in the mount's own space. Not the origin: D17-R25 puts the origin
        # on the mount *face*, which is deliberately below the bore, so a check or a renderer that
        # assumed the bore passes through (0,0,0) would be measuring from the wrong line.
        "boreOriginLocal": {"x": round(bore_origin_local[0], 5),
                            "y": round(bore_origin_local[1], 5),
                            "z": round(bore_origin_local[2], 5)},
        "muzzleLocal": {"x": round(muzzle[0], 5), "y": round(muzzle[1], 5), "z": round(muzzle[2],
        5)},
        "totalMassKg": round(total_mass, 3),
        "parts": [p.as_dict() for p in parts],
        "seams": [s.as_dict() for s in seams],
        "stats": stat_block,
        "weapon": block,
    }

    checks = selfverify.run_checks(manifest, parts, seams, shells, corrected_bore, options)
    manifest["checks"] = [c.as_dict() for c in checks]

    report = dict(manifest)
    report["stages"] = [s.as_dict() for s in stages]
    report["votes"] = [dict(v, shell=s.index) for s in shells for v in s.votes]
    report["seconds"] = round(time.time() - started, 3)
    report["ok"] = all(c.passed for c in checks)

    if options.out is not None:
        # Named for the weapon, not `weapon.json`: the shared library holds every modular weapon in
        # one directory (D08-R14b), so a fixed filename would have the second weapon overwrite the
        # first's manifest.
        target = options.out / f"{weapon_id}.weapon.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")

    failed = [c for c in checks if not c.passed]
    if failed:
        raise WeaponError(
            "self-verification failed: " + "; ".join(f"{c.name} {c.detail}" for c in failed),
            86,
            report)
    return report


# ---- Stage 1: load -------------------------------------------------------------------------


def load_model(model: Path) -> dict:
    """Imports the glTF and drops everything that is not mesh geometry.

    The licence is read out of the file's ``asset.extras`` and carried into the manifest, because a
    weapon whose licence is lost in processing is a legal problem rather than a content one
    (D17-E15). Both shipped models are CC-BY-4.0 and both require attribution.
    """
    bpy.ops.wm.read_factory_settings(use_empty=True)
    path = str(model)
    if model.suffix.lower() in (".glb", ".gltf"):
        bpy.ops.import_scene.gltf(filepath=path)
    else:
        raise WeaponError(f"{model} is not a .glb or .gltf", 65)

    meshes = [obj for obj in bpy.data.objects if obj.type == "MESH"]
    if not meshes:
        raise WeaponError(f"{model} contains no mesh geometry", 65)

    # Bake every object's world transform into its mesh, then clear the hierarchy. The rest of the
    # pipeline measures in world space, and a parent transform left in place is a measurement that
    # silently disagrees with the geometry (DISC-016's lesson, applied preventively).
    bpy.ops.object.select_all(action="DESELECT")
    for obj in meshes:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    for obj in meshes:
        obj.parent = None
    for obj in list(bpy.data.objects):
        if obj.type != "MESH":
            bpy.data.objects.remove(obj, do_unlink=True)

    prescale = prescale_to_unit()

    return {
        "objects": len(meshes),
        "triangles": _triangle_count(),
        "licence": _read_licence(model),
        "prescale": prescale,
    }


def prescale_to_unit() -> dict:
    """Scales the model so its largest extent is 1.0, at load, before anything else runs.

    **Every absolute threshold downstream depends on this.** The repair stage welds at 0.1 mm
    (D15-S5.5) and dissolves slivers at a fixed edge length; a downloaded model whose units are
    unknown makes both of those meaningless. Both shipped weapons import at 100x, where a 0.1 mm
    weld distance is a 1-micron weld in model terms — so nothing welded, the cannon separated into
    **203 shells instead of 22**, and the bore fit was outvoted by rivets.

    This is a different correction from the bore-aligned one in :func:`normalise_frame`, and it has
    to be first because that one needs measurements that this one makes trustworthy. It is folded
    into the final scale rather than reported as a separate correction, so ``scaleApplied`` in the
    manifest is still the one number that takes the source to the game.
    """
    mesh_objects = [o for o in bpy.data.objects if o.type == "MESH"]
    lo = [float("inf")] * 3
    hi = [float("-inf")] * 3
    for obj in mesh_objects:
        for corner in obj.bound_box:
            world = obj.matrix_world @ Vector(corner)
            for i in range(3):
                lo[i] = min(lo[i], world[i])
                hi[i] = max(hi[i], world[i])
    if lo[0] == float("inf"):
        return {"applied": False}
    extent = max(hi[i] - lo[i] for i in range(3))
    if extent <= 1e-12:
        return {"applied": False}
    scale = 1.0 / extent
    centre = Vector(((lo[0] + hi[0]) / 2.0, (lo[1] + hi[1]) / 2.0, (lo[2] + hi[2]) / 2.0))
    for obj in mesh_objects:
        obj.matrix_world = Matrix.Scale(scale, 4) @ Matrix.Translation(-centre) @ obj.matrix_world
    bpy.context.view_layer.update()
    _apply_transforms(mesh_objects)
    return {"applied": True, "sourceExtent": round(extent, 4), "scale": round(scale, 8)}


def _read_licence(model: Path) -> str:
    """The licence line out of the glTF's ``asset.extras`` (D17-E15)."""
    try:
        raw = model.read_bytes()
        # Both the JSON chunk of a .glb and a whole .gltf carry the string; find it without a full
        # parse, which for a 10 MB binary is the difference between milliseconds and seconds.
        marker = b'"license"'
        index = raw.find(marker)
        if index < 0:
            return "unknown"
        segment = raw[index:index + 400].decode("utf-8", errors="replace")
        value = segment.split(":", 1)[1].strip()
        return value.split('"')[1] if '"' in value else "unknown"
    except (OSError, IndexError):
        return "unknown"


def normalise_materials(options: Options) -> dict:
    """Stage 1b: the same house-style table a vehicle goes through (D17-R28, D15-S5.9).

    Literally the same module and the same table, which is the whole of the answer to "the tool
    should normalise style": a weapon and a car look like they belong to one game only if they went
    through one table, rather than two that agree today.
    """
    if not options.normalise_style:
        return {"applied": False, "reason": "disabled with --no-style"}
    try:
        from syndicate_prepare import style

        table = style.StyleTable.load(options.style_table)
        report = style.apply_to_scene(table, options.seed)
        report["applied"] = True
        return report
    except Exception as error:
        return {"applied": False, "reason": str(error)}


# ---- Stage 2: repair -----------------------------------------------------------------------


def repair_geometry() -> dict:
    """Stage 2: D15-S5.5's repair, plus the two that matter more on a gun (D17-R30, R31)."""
    from syndicate_prepare import prepare

    result = prepare.clean_topology()
    result["boresCapped"] = cap_open_bores()
    return result


def cap_open_bores() -> int:
    """Closes open tube ends so a barrel is a solid the fracture path can cut (D17-R31).

    A barrel modelled as an open tube has no inside, and DISC-039's lesson generalises: the D09
    partition needs a solid, and an open surface fails it three stages from the cause.
    """
    capped = 0
    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        mesh = bmesh.new()
        mesh.from_mesh(obj.data)
        boundary = [edge for edge in mesh.edges if edge.is_boundary]
        if boundary:
            try:
                bmesh.ops.holes_fill(mesh, edges=boundary, sides=0)
                capped += 1
            except (RuntimeError, ValueError):
                pass
        mesh.to_mesh(obj.data)
        mesh.free()
        obj.data.update()
    return capped


# ---- Stage 3: separate ---------------------------------------------------------------------


def separate_into_shells():
    """Connected-component separation, then measurement (D17-R32, D15-S5.2's mechanism)."""
    from syndicate_prepare import prepare

    shells, objects = prepare.separate_into_shells()
    if len(shells) > WEAPON_MAX_SHELLS:
        raise WeaponError(
            f"{len(shells)} shells exceeds WEAPON_MAX_SHELLS ({WEAPON_MAX_SHELLS})", 81)
    return shells, objects


def drop_duplicate_shells(shells, objects) -> int:
    """Removes coincident duplicated geometry (D17-R31).

    Sketchfab exports frequently carry the same mesh twice at the same coordinates. Welding does not
    remove it — a duplicate is a separate connected component — and left in place it doubles the
    part's mass and z-fights on every frame.
    """
    seen: dict[tuple, int] = {}
    dropped = 0
    for shell in sorted(shells, key=lambda s: s.index):
        key = (
            shell.triangles,
            tuple(round(c, 4) for c in shell.lo),
            tuple(round(c, 4) for c in shell.hi),
        )
        if key in seen:
            obj = objects.pop(shell.index, None)
            if obj is not None:
                bpy.data.objects.remove(obj, do_unlink=True)
            shell.merged_into = seen[key]
            dropped += 1
            continue
        seen[key] = shell.index
    if dropped:
        shells[:] = [s for s in shells if s.merged_into is None]
    return dropped


def merge_small_shells(shells, objects):
    """Shells below the triangle floor join their nearest neighbour (D17-R33).

    Ties broken on shell index so two runs agree (G3). The neighbour is chosen by centroid distance,
    which for a gun's screws and pins is the piece they are screwed into.
    """
    keep = [s for s in shells if s.triangles >= WEAPON_MIN_SHELL_TRIANGLES]
    small = [s for s in shells if s.triangles < WEAPON_MIN_SHELL_TRIANGLES]
    if not keep or not small:
        return shells
    for shell in sorted(small, key=lambda s: s.index):
        target = min(keep, key=lambda k: (round(k.distance_to(shell), 6), k.index))
        shell.merged_into = target.index
        source = objects.get(shell.index)
        into = objects.get(target.index)
        if source is not None and into is not None:
            _join(into, source)
            objects.pop(shell.index, None)
    return keep


def _join(into, source) -> None:
    bpy.ops.object.select_all(action="DESELECT")
    source.select_set(True)
    into.select_set(True)
    bpy.context.view_layer.objects.active = into
    bpy.ops.object.join()


# ---- Stage 1, second half: correct the frame -----------------------------------------------


def normalise_frame(raw_bore) -> dict:
    """Rotate the bore onto +Z, scale to unit bore length, and put the model on the origin.

    This is D17-R23's scale-rotate-translate, performed at unit scale rather than at the final one,
    and it runs **before labelling** rather than after. The reason is D17-R23a: the ensemble's
    thresholds are distances, a downloaded model's scale is unknown, and normalising first turns
    every one of those thresholds into a proportion of the weapon. Run the other way round — which
    is how this was first written — the shipped machine gun labelled three of its nine shells and
    called its receiver a shield.
    """
    extent = _scene_bore_extent(raw_bore)
    scale = 1.0 if extent <= 1e-9 else 1.0 / extent

    # Game-space bore axis to Blender space, then the minimal rotation onto Blender's -Y, which is
    # game +Z (D00-R16). Minimal rather than a full basis alignment: for a gun whose bore is roughly
    # horizontal — which is every gun — the minimal rotation is a yaw, and a yaw preserves up
    # exactly. A full basis would also fix a roll these models do not have, and would risk inventing
    # one they do.
    axis_blender = Vector((raw_bore.axis[0], -raw_bore.axis[2], raw_bore.axis[1])).normalized()
    rotation = axis_blender.rotation_difference(Vector((0.0, -1.0, 0.0))).to_matrix().to_4x4()

    mesh_objects = [o for o in bpy.data.objects if o.type == "MESH"]
    for obj in mesh_objects:
        obj.matrix_world = rotation @ Matrix.Scale(scale, 4) @ obj.matrix_world
    bpy.context.view_layer.update()
    _apply_transforms(mesh_objects)

    offset = _mount_offset()
    for obj in mesh_objects:
        obj.matrix_world = Matrix.Translation(offset) @ obj.matrix_world
    bpy.context.view_layer.update()
    _apply_transforms(mesh_objects)

    return {
        "rawBoreExtent": round(extent, 5),
        "normalisingScale": round(scale, 6),
        "translation": {"x": round(offset[0], 5), "y": round(offset[1], 5), "z": round(offset[2],
        5)},
    }


def scale_to_target(target_length_m: float) -> dict:
    """Uniform scale from unit length to the size class's target (D17-R26).

    The model is exactly 1.0 long along the bore when this runs, so the scale factor *is* the target
    length. The deadband of D17-R26 is expressed against that: a target within 10% of a metre-scale
    model would be a scale of 1.02, which costs precision for nothing.
    """
    mesh_objects = [o for o in bpy.data.objects if o.type == "MESH"]
    for obj in mesh_objects:
        obj.matrix_world = Matrix.Scale(target_length_m, 4) @ obj.matrix_world
    bpy.context.view_layer.update()
    _apply_transforms(mesh_objects)
    # Scaling about the origin keeps the mount face on it, so no second translation is needed.
    return {"targetLengthM": round(target_length_m, 4), "scaleApplied": round(target_length_m, 6)}


def _scene_bore_extent(bore) -> float:
    """The model's extent along the bore, read from the live scene rather than from measurements."""
    along = []
    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        for corner in obj.bound_box:
            world = obj.matrix_world @ Vector(corner)
            game = (world.x, world.z, -world.y)
            along.append(bore.coordinate_of(game))
    return (max(along) - min(along)) if along else 0.0


def _apply_transforms(mesh_objects) -> None:
    if not mesh_objects:
        return
    bpy.ops.object.select_all(action="DESELECT")
    for obj in mesh_objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = mesh_objects[0]
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def _mount_offset():
    """The Blender-space translation that puts the mount face at the origin (D17-R25).

    The bottom of the whole weapon on the vertical axis, and its centre on the other two. A weapon
    sits on its mounting rather than hanging from it, and an origin anywhere else is what makes a
    fitted weapon float or sink into the bodywork — the single largest source of the sloppy seams
    this document exists to prevent.
    """
    lo = [float("inf")] * 3
    hi = [float("-inf")] * 3
    for obj in [o for o in bpy.data.objects if o.type == "MESH"]:
        for corner in obj.bound_box:
            world = obj.matrix_world @ Vector(corner)
            for i in range(3):
                lo[i] = min(lo[i], world[i])
                hi[i] = max(hi[i], world[i])
    if lo[0] == float("inf"):
        return Vector((0.0, 0.0, 0.0))
    # Blender: X right, Z up, and -Y is game +Z. Centre X and Y, floor Z.
    return Vector((-(lo[0] + hi[0]) / 2.0, -(lo[1] + hi[1]) / 2.0, -lo[2]))


def discard_non_weapon(shells, objects, bore):
    """Drops geometry that is not part of the gun (D17-R27, D17-E3, D17-E4).

    A display base, a diorama's ground plane, and a siege carriage's road wheels and axle are all
    things a weapon model comes *with* and none of them is the weapon. The rule is a cylinder about
    the bore: a gun's working parts are arranged around its bore and are therefore near it, and what
    sits :data:`CARRIAGE_RADIUS` further out is what the gun is carried on.

    **Every discarded shell is named in the report**, with its triangle count. Discarding silently
    is
    what turns "the pipeline produced a weapon" into "the pipeline produced a weapon and threw away
    a
    third of the model", and on the shipped cannon that third is four road wheels and an axle.
    """
    kept, dropped = [], []
    for shell in sorted(shells, key=lambda s: s.index):
        if bore.radius_of(shell.centroid) > CARRIAGE_RADIUS:
            dropped.append(shell)
            obj = objects.pop(shell.index, None)
            if obj is not None:
                bpy.data.objects.remove(obj, do_unlink=True)
        else:
            kept.append(shell)
    if not kept:
        # Everything is outside the cylinder, which means the bore fit is wrong rather than that the
        # model is all carriage. Keeping the model is the safer failure: the report will show a
        # nonsensical classification, which is easier to read than an empty one.
        return shells, {"discarded": 0, "reason": "every shell fell outside the carriage radius; "
                                                  "kept them all rather than trust the bore fit"}
    return kept, {
        "discarded": len(dropped),
        "discardedTriangles": sum(s.triangles for s in dropped),
        "keptTriangles": sum(s.triangles for s in kept),
        "carriageRadius": CARRIAGE_RADIUS,
        "examples": [
            {"shell": s.index, "triangles": s.triangles,
             "radius": round(bore.radius_of(s.centroid), 4)}
            for s in sorted(dropped, key=lambda s: -s.triangles)[:8]
        ],
    }


def remeasure(shells, objects):
    """Re-measures every surviving shell in the corrected frame, keeping its label.

    The labels were decided in the raw frame and every cue that produced them is bore-relative, so
    they survive a rigid transform and a uniform scale unchanged. Re-running the ensemble here would
    produce the same answer at the cost of a second pass — and would risk producing a *different*
    one, which would make the report's votes disagree with the parts it shipped.
    """
    from syndicate_prepare import prepare

    out = []
    for shell in sorted(shells, key=lambda s: s.index):
        obj = objects.get(shell.index)
        if obj is None:
            continue
        fresh = prepare.measure(obj, shell.index)
        fresh.label = shell.label
        fresh.confidence = shell.confidence
        fresh.votes = shell.votes
        out.append(fresh)
    return out


def _fitted_bore(shells):
    """The bore in the corrected frame: axis exactly +Z, origin fitted to the barrel.

    The axis is snapped rather than re-fitted because the correction *made* it +Z, and a re-fit
    would
    return something a fraction of a degree off and make every downstream coordinate slightly
    frame-dependent. The origin is genuinely re-fitted, because where the bore line sits laterally
    is
    a property of the gun that the correction did not set.
    """
    fitted = bore_mod.find(shells)
    return bore_mod.Bore(axis=(0.0, 0.0, 1.0), origin=fitted.origin, confidence=fitted.confidence,
                         because="axis is +Z by construction; origin fitted to the barrel")


# ---- Stage 5b: the synthesised mount -------------------------------------------------------


def realise_synthesised_mount(parts, objects, shells) -> dict:
    """Builds real geometry for a mount that was invented rather than found (D17-R41).

    Real geometry, not a `part.json` entry: a mount that existed only in a document would be a
    weapon that renders floating in the air, has no collision where it meets the vehicle, and cannot
    fracture when it is shot off.
    """
    synthesised = [p for p in parts if p.label == MOUNT and p.synthesised]
    if not synthesised:
        return {"synthesised": False}

    part = synthesised[0]
    lo = [min(s.lo[i] for s in shells) for i in range(3)]
    [max(s.hi[i] for s in shells) for i in range(3)]

    # Under whatever it will actually carry — the receiver, or the largest part if there is none.
    # Sizing it off the model's global bounds instead put its top face at the weapon's lowest point,
    # which on the shipped machine gun is the barrel: the boss then stood 75 mm clear of the thing
    # it
    # was supposed to hold, and the seam rule correctly reported a join it could not find.
    # The receiver by preference, because that is the mount's child in the taxonomy (D17-R42) and a
    # boss built under something else leaves that join to be found at a widened reach. Falls back to
    # the largest sub-part for a model that has no receiver at all.
    from .labels import RECEIVER

    receivers = [p for p in parts if p.label == RECEIVER]
    carried = receivers[0] if receivers else max(
        (p for p in parts if p.label != MOUNT), key=lambda p: p.area_m2, default=None)
    if carried is None:
        return {"synthesised": False, "reason": "nothing to mount"}

    width = max(0.03, (carried.hi[0] - carried.lo[0]) * 0.8)
    depth = max(0.03, (carried.hi[2] - carried.lo[2]) * 0.35)
    # From the model floor up into the carried part, so it both reaches the ground plane the weapon
    # is bolted at and overlaps what it holds. The overlap is what makes the join a contact rather
    # than a coincidence of two faces at the same height.
    top = carried.lo[1] + max(0.004, (carried.hi[1] - carried.lo[1]) * 0.15)
    height = max(0.02, top - lo[1])
    centre_x = (carried.lo[0] + carried.hi[0]) * 0.5
    centre_z = (carried.lo[2] + carried.hi[2]) * 0.5

    mesh = bpy.data.meshes.new(f"{part.name}_mesh")
    obj = bpy.data.objects.new(part.name, mesh)
    bpy.context.collection.objects.link(obj)
    builder = bmesh.new()
    bmesh.ops.create_cube(builder, size=1.0)
    bmesh.ops.scale(builder, vec=Vector((width, depth, height)), verts=builder.verts)
    # Blender space: game (x, y, z) is Blender (x, -z, y).
    bmesh.ops.translate(
        builder,
        vec=Vector((centre_x, -centre_z, lo[1] + height * 0.5)),
        verts=builder.verts)
    builder.to_mesh(mesh)
    builder.free()

    # The style pass ran at stage 1b, before this geometry existed, so a synthesised mount has no
    # material at all and renders **pure white** — a bright box under a styled gun, visible in the
    # first capture of the shipped cannon and in none of the checks.
    #
    # A *new, untextured* material rather than the donor's own: the carried part's material is an
    # image texture, and this cube has no UV map, so sharing it exported a mesh.glb the runtime
    # could not read at all — which turned a cosmetic defect into a missing part. Its colour is
    # taken
    # from the donor's base-colour factor where there is one, so the boss still belongs to the gun.
    material = bpy.data.materials.new(f"{part.name}_mat")
    material.use_nodes = True
    principled = next((n for n in material.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if principled is not None:
        principled.inputs["Base Color"].default_value = _donor_colour(objects, carried)
        principled.inputs["Metallic"].default_value = 0.85
        principled.inputs["Roughness"].default_value = 0.45
    mesh.materials.append(material)

    index = max(objects) + 1 if objects else 0
    objects[index] = obj
    part.shells = [index]
    part.synthesised = True

    from syndicate_prepare import prepare

    measured = prepare.measure(obj, index)
    measured.label = MOUNT
    measured.confidence = 1.0
    measured.votes = []
    shells.append(measured)
    part.lo, part.hi, part.centroid = measured.lo, measured.hi, measured.centroid
    part.triangles = measured.triangles
    part.area_m2 = measured.area_m2
    part.volume_m3 = measured.volume_m3
    part.mass_kg = grouping.mass_of(part)
    return {"synthesised": True, "carries": carried.name, "widthM": round(width, 4),
            "depthM": round(depth, 4), "heightM": round(height, 4),
            "massKg": round(part.mass_kg, 3)}


# ---- Stages 9 and 10: author and export ----------------------------------------------------


def _donor_colour(objects, carried):
    """The base colour of the part a synthesised mount carries, or a neutral plate grey.

    DISC-048 applies and is easy to re-break: a Base Color socket *behind a texture* is a
    multiplication factor rather than a colour. Reading it is still the best available signal for
    what the gun was styled to — a factor of 1 gives the neutral fallback, which is the right answer
    for a gun whose colour lives entirely in its texture.
    """
    # DISC-047: the PBR environment renders a flat base colour roughly **four times** brighter than
    # the colour itself, so a plausible-looking 0.22 plate grey comes out near white — which is what
    # the second capture of the shipped cannon showed. 0.06 is the value that lands on plate grey.
    fallback = (0.06, 0.062, 0.068, 1.0)
    donor = objects.get(carried.shells[0]) if carried.shells else None
    if donor is None or not donor.data.materials:
        return fallback
    material = donor.data.materials[0]
    if material is None or not material.use_nodes:
        return fallback
    principled = next((n for n in material.node_tree.nodes if n.type == "BSDF_PRINCIPLED"), None)
    if principled is None:
        return fallback
    colour = tuple(principled.inputs["Base Color"].default_value)
    if all(channel > 0.95 for channel in colour[:3]):
        return fallback
    # Darkened for the same reason the fallback is: whatever the donor's factor is, it is read by
    # the
    # renderer through the same fourfold brightening.
    return (colour[0] * 0.3, colour[1] * 0.3, colour[2] * 0.3, 1.0)


def export_all(options, parts, nodes, objects, shells, family, size_class, block, stat_block,
               muzzle) -> list:
    """Writes every sub-part's mesh, hull and ``part.json`` (D17-S5.11, D17-S5.14)."""
    from syndicate_prepare import exporter

    out_root = options.out
    out_root.mkdir(parents=True, exist_ok=True)
    node_by_part = {id(n.part): n for n in nodes}
    children: dict[int, list] = {}
    for node in nodes:
        if node.parent is not None:
            children.setdefault(id(node.parent), []).append(node)

    produced = []
    for part in parts:
        part_objects = [objects[i] for i in part.shells if i in objects]
        if not part_objects:
            continue
        result = exporter.export_part(_ExportView(part), part_objects, out_root, options.seed)
        produced.append(result)
        document = build_part_document(
            part, node_by_part.get(id(part)), children.get(id(part), []), result,
            family, size_class, block, stat_block, muzzle)
        target = out_root / part.part_type_id / "part.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n")
    return produced


class _ExportView:
    """Adapts a :class:`grouping.SubPart` to what ``syndicate_prepare.exporter`` expects.

    The vehicle exporter reads ``part_type_id``, ``origin`` and ``label``, and its ``label`` is a
    D15 label used only to look up a destruction treatment. Mapping D17's labels onto D15's classes
    here — rather than teaching that exporter a second taxonomy — keeps one exporter with one
    contract, which is what makes "reused unchanged" true rather than aspirational.
    """

    #: D17 label -> a D15 label whose treatment is the one D17-S4.2 asks for.
    TREATMENT_PROXY: ClassVar[dict[str, str]] = {
        "STRUCTURAL": "chassis",
        "RIGID": "wheel",
        "SHEET_METAL": "panel",
        "GLASS": "glass",
        "NONE": "decal",
    }

    def __init__(self, part):
        self._part = part
        self.part_type_id = part.part_type_id
        self.origin = part.origin
        self.label = self.TREATMENT_PROXY[part.destruction_class]


def build_part_document(part, node, children, produced, family, size_class, block, stat_block,
                        muzzle) -> dict:
    """One sub-part's ``part.json`` (D08-S4.2).

    The ``weapon`` block goes on the **mount** and nowhere else: the mount is the part that occupies
    the vehicle's hardpoint, so it is the part that is "fitted", and A216 makes a weapon block on
    anything else an error. Its sub-parts are ``WEAPON``-category parts that carry no block, which
    is exactly D08-R5's "a part in category weapon with no block never fires" — correct, because
    they do not fire, the gun does.
    """
    is_root = part.label == MOUNT
    # A205 (D05-R6): a decorative part may declare no stats and no armour. A muzzle brake, a sight
    # and a grip plate are all `DECORATIVE` by D17-S4.2 — losing them is cosmetic — so they carry
    # mass and health and nothing else. Emitting an armour value on them made every one of them a
    # blocking asset error, and the weapon simply did not appear in the game.
    decorative = part.category == "DECORATIVE"
    document = {
        "schemaVersion": "1.0.0",
        "partTypeId": part.part_type_id,
        "displayName": part.name.replace("_", " ").title(),
        "category": part.category,
        "massKg": round(part.mass_kg, 3),
        "maxHp": round(max(20.0, part.mass_kg * 8.0), 2),
        "armorValue": 0.0 if decorative else round(min(40.0, part.mass_kg * 0.35), 2),
        "materialId": part.material_id,
        "slotTypeRequired": part.slot_type_required if not is_root else "TURRET_MOUNT",
        "sizeClass": size_class,
        "destructionClass": part.destruction_class,
        "powerCost": round(stats.power_cost(family, size_class) / max(1, len(children) + 1), 3),
        "breakImpulseN": round(max(400.0, part.mass_kg * 90.0), 1),
        "hangsBeforeFalling": part.destruction_class == "STRUCTURAL",
        "tags": ["weapon", part.label, "prepared"],
        "assets": {
            "visualMesh": "mesh.glb",
            "collisionSource": f"mesh.glb#node={part.part_type_id}_col",
        },
    }
    if produced.morphs:
        document["assets"]["morphTargets"] = list(produced.morphs)
    if getattr(part, "articulation", None):
        document["articulation"] = part.articulation
    if is_root:
        document["weapon"] = block
        document["stats"] = stat_block
    slots = []
    for child_node in sorted(children, key=lambda n: n.slot_id):
        child = child_node.part
        offset = tuple(child.origin[i] - part.origin[i] for i in range(3))
        slots.append({
            "slotId": child_node.slot_id,
            "slotType": "SUBSLOT",
            "localPosition": {"x": round(offset[0], 5), "y": round(offset[1], 5),
                              "z": round(offset[2], 5)},
            "localRotationDeg": {"order": "XYZ", "x": 0.0, "y": 0.0, "z": 0.0},
            # Derived from the child's own mass, so a sub-part can never fail A306 against its own
            # parent (D17-E9): the only mass gate that can bite is the vehicle's, which is the one
            # that should.
            "maxMassKg": round(max(1.0, child.mass_kg * 1.25), 3),
            "sizeClass": size_class,
            "covers": [],
            "isDetachable": True,
        })
    if slots:
        document["slots"] = slots
    return document


# ---- Reporting helpers ---------------------------------------------------------------------


def _tag_repetition(parts, shells) -> None:
    """Records how many congruent instances a sub-part was built from, for INDEX articulation."""
    by_index = {s.index: s for s in shells}
    for part in parts:
        sizes = {}
        for index in part.shells:
            shell = by_index.get(index)
            if shell is None:
                continue
            key = (shell.triangles, tuple(round(c, 2) for c in shell.size))
            sizes[key] = sizes.get(key, 0) + 1
        part.repetition = max(sizes.values()) if sizes else 0


def _label_summary(shells) -> dict:
    counts: dict[str, int] = {}
    triangles: dict[str, int] = {}
    for shell in shells:
        counts[shell.label] = counts.get(shell.label, 0) + 1
        triangles[shell.label] = triangles.get(shell.label, 0) + shell.triangles
    total = sum(triangles.values()) or 1
    return {
        "counts": dict(sorted(counts.items())),
        "triangleShare": {k: round(v / total, 4) for k, v in sorted(triangles.items())},
    }


def _triangle_count() -> int:
    total = 0
    for obj in bpy.data.objects:
        if obj.type != "MESH":
            continue
        obj.data.calc_loop_triangles()
        total += len(obj.data.loop_triangles)
    return total


def _default_id(model: Path) -> str:
    stem = "".join(c if c.isalnum() else "_" for c in model.stem.lower())
    stem = "_".join(filter(None, stem.split("_")))
    return f"weapon_{stem}_01"


def _round3(vector) -> dict:
    return {"x": round(vector[0], 5), "y": round(vector[1], 5), "z": round(vector[2], 5)}


class StageTimer:
    """Times a stage and appends it to the report's ``stages`` array."""

    def __init__(self, name: str, stages: list):
        self.name = name
        self.stages = stages
        self.detail: dict = {}

    def __enter__(self) -> dict:
        self.started = time.time()
        return self.detail

    def __exit__(self, *exc):
        self.stages.append(Stage(self.name, time.time() - self.started, self.detail))
        return False


_ = math  # kept for the geometry helpers above when they are extended
