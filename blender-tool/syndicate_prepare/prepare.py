"""The stage order of D15-S5.1, driven inside a Blender host.

::

    1. Load, pose, and correct        (D09-S5.1 conventions; DISC-016 for why posing is first)
    1b. Normalise the materials        (D15-S9, syndicate_prepare.style)
    2. Repair geometry                 (D15-S5.5, syndicate_prepare.cleanup)
    3. Separate into connected shells  (D15-S5.2)
    4. Label shells                    (D15-S4.2 ensemble, D15-S4.3 overrides, roles, corners)
    5. Group shells into parts         (D15-S5.3)
    6. Rig articulated parts           (D15-S5.6, syndicate_prepare.hinges)
    7. Author destruction per class    (D15-S5.7, syndicate_prepare.destruction)
    8. Re-origin, re-parent, export    (D08-S4.5, syndicate_prepare.exporter)
    9. Self-verify and report          (D15-S4.4)

All nine run in one invocation. Given ``--out``, the end of it is a directory of parts and an
assembly the game loads: a model goes in and a vehicle comes out.

Everything that reads Blender is in this module and in :mod:`syndicate_prepare.exporter`.
Everything that *decides* anything is in the pure-Python modules beside them — ``cleanup``,
``cues``, ``roles``, ``grouping``, ``hinges``, ``destruction`` and ``manifest`` — which is what
lets every decision in the pipeline be unit-tested with no Blender host at all.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass
from pathlib import Path

from . import (
    cleanup,
    cues,
    destruction,
    exporter,
    grouping,
    hinges,
    manifest,
    profile,
    repair,
    roles,
    style,
)
from .labels import CHASSIS, LIGHT, MAX_SHELLS, ROTOR, UNCLASSIFIED, WEAPON
from .overrides import Overrides
from .roles import MAIN
from .shell import Shell

try:  # pragma: no cover - exercised only inside a Blender host
    # bpy first: `bmesh` is a submodule of the Blender runtime and is not importable until
    # `bpy` has initialised it, so the other order fails with ModuleNotFoundError on a host
    # that has both.
    import bpy  # isort: skip
    import bmesh  # isort: skip
    from mathutils import Vector  # isort: skip

    HAVE_BPY = True
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bmesh = None  # type: ignore[assignment]
    bpy = None  # type: ignore[assignment]
    Vector = None  # type: ignore[assignment]
    HAVE_BPY = False


class PrepareError(Exception):
    """A failure the tool reports through its exit code rather than a traceback."""


# ---- Stage 1: load, pose, correct ---------------------------------------------------------


def load_and_correct(model_dir: Path) -> dict:
    """Loads the model, drops foreign roots, bakes any armature, and corrects the frame.

    All four are :func:`syndicate_dissect.dissect.load_model`, called once. The two tools must
    see the same geometry in the same frame, or a wheel the dissector measured at one axle
    position and the preparer measured at another would be two different wheels with the same
    name.

    **Once** is the operative word. Calling the four stages again after ``load_model`` has run
    them is not idempotent: the pose bake flattens the hierarchy, so a second
    ``drop_foreign_roots`` sees 171 objects that are each their own root, keeps whichever
    happens to have the most children, and deletes the car. Measured, that took the Eclipse
    from 283,192 triangles to 15,381 and reported it as 0.73 m long.
    """
    from syndicate_dissect import dissect

    dissect.load_model(model_dir)
    correction = _read_import_json(model_dir)
    meshes = [obj for obj in bpy.data.objects if obj.type == "MESH"] if HAVE_BPY else []
    return {"objects": len(meshes), "correction": correction}


def normalise_materials(options) -> dict:
    """Stage 1b: move every source material into the house style (D15-S9, D15-R40).

    A missing or malformed style table is reported and the run continues unstyled, rather than
    failing: the vehicle that comes out is still a correct vehicle, it just does not match the
    roster, and refusing to prepare a car because a palette file was moved would be the wrong
    trade. ``--no-style`` is the same outcome asked for deliberately.
    """
    if not options.normalise_style:
        return {"applied": False, "reason": "disabled with --no-style"}
    try:
        table = style.StyleTable.load(options.style_table)
    except style.StyleError as error:
        return {"applied": False, "reason": str(error)}
    try:
        report = style.apply_to_scene(table, options.seed)
    except style.StyleError as error:  # pragma: no cover - needs a Blender host to reach
        return {"applied": False, "reason": str(error)}
    report["applied"] = True
    return report


def _read_import_json(model_dir: Path) -> dict:
    """The correction ``load_model`` applied, for the report (DEC-036, D15-R27)."""
    import json

    path = Path(model_dir) / "import.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError:
        return {}


# ---- Stage 3: separation (D15-S5.2) --------------------------------------------------------


def measure_objects() -> list[Shell]:
    """Every mesh object measured as it stands, before separation (D15-R14).

    The correction of stage 2 is planned against these. It has to be: every geometric cue is
    a measurement, so a model whose scale or orientation is wrong produces measurements in
    the wrong units, and fixing the frame afterwards would mean every threshold in
    :mod:`syndicate_prepare.labels` had already been compared against nonsense.

    Cheap on purpose — bounds, centroid and triangle count only. The expensive measurements
    (surface area, volume, the vertex sample) are taken once, after the scene is correct.
    """
    if not HAVE_BPY:
        raise PrepareError("measurement needs a Blender host")
    objects = sorted(
        (obj for obj in bpy.data.objects if obj.type == "MESH"), key=lambda obj: obj.name
    )
    return [measure(obj, index, detailed=False) for index, obj in enumerate(objects)]


def apply_correction(correction) -> None:
    """Bake a :class:`syndicate_prepare.cleanup.Correction` into the scene.

    Applied to root objects only and in the same order
    ``syndicate_dissect.dissect.apply_import_correction`` applies ``import.json``, because it
    *is* the same correction — this is the residual that file did not carry, and the two must
    compose rather than merely resemble each other.
    """
    from mathutils import Matrix, Vector

    if correction.is_identity:
        return
    tx, ty, tz = correction.translation
    yaw = math.radians(correction.yaw_deg)
    # game (x, y, z) is blender (x, -z, y): the translation's game Y becomes Blender's Z and
    # its game Z becomes Blender's negated Y, and a yaw about the game's +y is about
    # Blender's +z.
    transform = (
        Matrix.Translation(Vector((tx, -tz, ty)))
        @ Matrix.Rotation(yaw, 4, "Z")
        @ Matrix.Scale(correction.scale, 4)
    )
    for obj in bpy.context.scene.objects:
        if obj.parent is None:
            obj.matrix_world = transform @ obj.matrix_world
    bpy.context.view_layer.update()


def clean_topology() -> dict:
    """Weld doubled vertices and delete degenerate faces, over every object (D15-S5.5).

    The one repair in this pipeline that changes geometry rather than placing it, and it is
    bounded to :data:`cleanup.WELD_DISTANCE_M` — 0.1 mm, an order of magnitude below the
    smallest feature any D15 threshold measures — so no weld can move a shell far enough to
    change what it is labelled.

    It is not cosmetic. Doubled vertices are why a downloaded car separates into thousands of
    shells that ought to be one: two triangles sharing an edge in appearance but not in
    topology are two connected components, and every stage after separation inherits that.
    """
    before_vertices = sum(len(obj.data.vertices) for obj in _mesh_objects())
    before_faces = sum(len(obj.data.polygons) for obj in _mesh_objects())

    for obj in _mesh_objects():
        mesh = bmesh.new()
        mesh.from_mesh(obj.data)
        bmesh.ops.remove_doubles(mesh, verts=list(mesh.verts), dist=cleanup.WELD_DISTANCE_M)
        # Dissolve before delete: a sliver is collapsed into its neighbours and leaves no
        # hole, where deleting it would. What is left after this is genuinely zero-area.
        bmesh.ops.dissolve_degenerate(
            mesh, dist=cleanup.DEGENERATE_EDGE_M, edges=list(mesh.edges)
        )
        degenerate = [
            face for face in mesh.faces if face.calc_area() <= cleanup.MIN_FACE_AREA_M2
        ]
        if degenerate:
            bmesh.ops.delete(mesh, geom=degenerate, context="FACES")
        loose = [vertex for vertex in mesh.verts if not vertex.link_faces]
        if loose:
            bmesh.ops.delete(mesh, geom=loose, context="VERTS")
        mesh.to_mesh(obj.data)
        mesh.free()
        obj.data.update()

    after_vertices = sum(len(obj.data.vertices) for obj in _mesh_objects())
    after_faces = sum(len(obj.data.polygons) for obj in _mesh_objects())
    return {
        "check": "degenerate topology",
        "applied": before_vertices != after_vertices or before_faces != after_faces,
        "before": f"{before_vertices} vertices, {before_faces} faces",
        "after": f"{after_vertices} vertices, {after_faces} faces",
        "detail": (
            f"welded {before_vertices - after_vertices} doubled vertices and deleted "
            f"{before_faces - after_faces} degenerate faces at "
            f"{cleanup.WELD_DISTANCE_M * 1000:g} mm"
        ),
    }


def _mesh_objects() -> list:
    return [obj for obj in bpy.data.objects if obj.type == "MESH"]


def separate_into_shells() -> tuple[list[Shell], dict[int, object]]:
    """``separate(type='LOOSE')`` over **every** object, then measure each result (D15-R15).

    Over every object, not only the ones suspected of straddling a boundary. An object in a
    downloaded file is a *material group*, not a part: on both shipped cars one object is the
    entire painted body, and on one of them a single object is both headlights and both tail
    lights at once (DISC-018).

    D15-R16 records the cost as bounded and known — about 6,800 shells from 283k triangles in
    16 s — so this stage is not an optimisation target and must not be skipped as one.
    """
    if not HAVE_BPY:
        raise PrepareError("separation needs a Blender host")

    bpy.ops.object.select_all(action="DESELECT")
    targets = [obj for obj in bpy.data.objects if obj.type == "MESH"]
    for obj in targets:
        obj.select_set(True)
    if targets:
        bpy.context.view_layer.objects.active = targets[0]
        bpy.ops.object.mode_set(mode="EDIT")
        bpy.ops.mesh.select_all(action="SELECT")
        bpy.ops.mesh.separate(type="LOOSE")
        bpy.ops.object.mode_set(mode="OBJECT")

    meshes = [obj for obj in bpy.data.objects if obj.type == "MESH"]
    if len(meshes) > MAX_SHELLS:
        # D15-E7: abort with something machine-readable rather than run for an unbounded time.
        raise PrepareError(
            f"{len(meshes)} shells exceeds MAX_SHELLS ({MAX_SHELLS}); this model is not a vehicle "
            "this pipeline can prepare unattended"
        )

    # Sorted by name so the shell indices — which every tie-break in the ensemble falls back
    # on — are a property of the file rather than of Blender's internal ordering (D15-R30).
    ordered = sorted(meshes, key=lambda obj: obj.name)
    shells = []
    objects: dict[int, object] = {}
    for index, obj in enumerate(ordered):
        shell = measure(obj, index)
        if shell.triangles == 0:
            continue
        shells.append(shell)
        objects[index] = obj
    return shells, objects


def measure(obj, index: int, detailed: bool = True) -> Shell:
    """Reads one object into a :class:`Shell`, in world space.

    ``detailed`` adds the surface area, the volume and the vertex sample — everything the
    export stage needs and the correction stage does not. Two passes over a 280,000-triangle
    car cost seconds, and skipping the expensive half of the first one is most of that back.
    """
    mesh = obj.data
    mesh.calc_loop_triangles()
    triangles = len(mesh.loop_triangles)
    if triangles == 0:
        return Shell(index, obj.name, material_name(obj), 0, (0, 0, 0), (0, 0, 0), (0, 0, 0))

    matrix = obj.matrix_world
    lo = [float("inf")] * 3
    hi = [float("-inf")] * 3
    accumulated = [0.0, 0.0, 0.0]
    for vertex in mesh.vertices:
        # Converted to the game frame here, at the one place geometry is read. Every threshold
        # in `labels` is a real quantity in the game's metres and axes (D00-R16, D15-R14), so
        # measuring in Blender's Z-up frame would compare a car's height against a rule about
        # its length — which is exactly what it did on the first run: 2.18 x 4.67 x 1.24 for a
        # car that is 2.18 x 1.24 x 4.67.
        world = _to_game(matrix @ vertex.co)
        for axis in range(3):
            value = world[axis]
            lo[axis] = min(lo[axis], value)
            hi[axis] = max(hi[axis], value)
            accumulated[axis] += value
    count = max(1, len(mesh.vertices))
    centroid = tuple(value / count for value in accumulated)

    shell = Shell(
        index=index,
        name=obj.name,
        material=material_name(obj),
        triangles=triangles,
        lo=tuple(lo),
        hi=tuple(hi),
        centroid=centroid,
    )
    read_material_physics(obj, shell)
    if detailed:
        shell.area_m2, shell.volume_m3 = measure_area_and_volume(obj)
        shell.vertex_sample = sample_vertices(obj)
    return shell


def measure_area_and_volume(obj) -> tuple[float, float]:
    """Surface area in m² and enclosed volume in m³, both in game units.

    The volume is the signed tetrahedron sum and is meaningful **only for a closed shell**.
    On real vehicle art most shells are open surfaces, where it comes out near zero or
    frankly negative — which is why :mod:`syndicate_prepare.manifest` weighs a part by its
    area and its class's wall thickness rather than by this. It is measured anyway because
    the difference between the two is how the export stage knows a shell needs solidifying.
    """
    mesh = obj.data
    mesh.calc_loop_triangles()
    matrix = obj.matrix_world
    positions = [_to_game(matrix @ vertex.co) for vertex in mesh.vertices]

    area = 0.0
    volume = 0.0
    for triangle in mesh.loop_triangles:
        a, b, c = (positions[i] for i in triangle.vertices)
        ab = tuple(b[i] - a[i] for i in range(3))
        ac = tuple(c[i] - a[i] for i in range(3))
        cross = (
            ab[1] * ac[2] - ab[2] * ac[1],
            ab[2] * ac[0] - ab[0] * ac[2],
            ab[0] * ac[1] - ab[1] * ac[0],
        )
        area += 0.5 * math.sqrt(sum(value * value for value in cross))
        volume += (
            a[0] * (b[1] * c[2] - b[2] * c[1])
            + a[1] * (b[2] * c[0] - b[0] * c[2])
            + a[2] * (b[0] * c[1] - b[1] * c[0])
        ) / 6.0
    return area, abs(volume)


def sample_vertices(obj) -> tuple:
    """Up to :data:`shell.VERTEX_SAMPLE_LIMIT` vertices in game space, evenly strided.

    Strided rather than random: the sample has to be a function of the geometry alone (G3),
    and a stride is, where anything drawing on a random source would make the wheel/hub split
    of D15-R21 depend on a seed.
    """
    from .shell import VERTEX_SAMPLE_LIMIT

    matrix = obj.matrix_world
    vertices = obj.data.vertices
    count = len(vertices)
    if count <= VERTEX_SAMPLE_LIMIT:
        return tuple(_to_game(matrix @ vertex.co) for vertex in vertices)
    stride = count / VERTEX_SAMPLE_LIMIT
    return tuple(
        _to_game(matrix @ vertices[min(count - 1, int(index * stride))].co)
        for index in range(VERTEX_SAMPLE_LIMIT)
    )


def material_name(obj) -> str | None:
    """The first material slot's name, or ``None``.

    The first rather than all of them: separation by loose parts leaves each shell with the
    slots of the object it came from, but a connected shell in practice uses one, and the key
    an operator writes in ``parts.json`` has to be a single string (D15-R9).
    """
    for slot in obj.material_slots:
        if slot.material is not None:
            return slot.material.name
    return None


def read_material_physics(obj, shell: Shell) -> None:
    """Fills in the C2 fields from the Blender material the glTF importer built (D15-S4.2).

    The importer maps ``alphaMode`` onto ``blend_method``, base-colour alpha and transmission
    onto the Principled BSDF's inputs, and ``doubleSided`` onto backface culling. Reading those
    is reading the file's own declared render intent, which is why D15-R6 ranks this above the
    material's *name*.
    """
    material = None
    for slot in obj.material_slots:
        if slot.material is not None:
            material = slot.material
            break
    if material is None:
        return

    modes = {"OPAQUE": "OPAQUE", "BLEND": "BLEND", "CLIP": "HASHED", "HASHED": "HASHED"}
    shell.alpha_mode = modes.get(getattr(material, "blend_method", "OPAQUE"), "OPAQUE")
    shell.double_sided = not getattr(material, "use_backface_culling", False)

    principled = None
    if material.use_nodes and material.node_tree is not None:
        for node in material.node_tree.nodes:
            if node.type == "BSDF_PRINCIPLED":
                principled = node
                break
    if principled is None:
        return

    shell.base_alpha = _input_value(principled, "Alpha", 1.0)
    shell.roughness = _input_value(principled, "Roughness", 0.5)
    # Blender 4.x renamed the transmission and emission sockets; both names are tried so the
    # tool does not silently read zero on one version and the truth on another.
    shell.transmission = _input_value(principled, "Transmission Weight", None)
    if shell.transmission is None:
        shell.transmission = _input_value(principled, "Transmission", 0.0)
    emission = _input_value(principled, "Emission Strength", 0.0)
    colour = _input_socket(principled, "Emission Color")
    if colour is None:
        colour = _input_socket(principled, "Emission")
    if colour is not None and hasattr(colour, "default_value"):
        try:
            brightness = max(colour.default_value[0], colour.default_value[1],
                colour.default_value[2])
        except (TypeError, IndexError):
            brightness = 0.0
        shell.emissive = float(emission) * float(brightness)
    else:
        shell.emissive = float(emission)


def _input_socket(node, name: str):
    return node.inputs.get(name)


def _input_value(node, name: str, fallback):
    socket = node.inputs.get(name)
    if socket is None:
        return fallback
    try:
        return float(socket.default_value)
    except (TypeError, ValueError):
        return fallback


def vertices_of(obj) -> list[tuple[float, float, float]]:
    """Every vertex of an object in game space, for the rotational-symmetry test (D15-R24)."""
    matrix = obj.matrix_world
    return [_to_game(matrix @ vertex.co) for vertex in obj.data.vertices]


def _to_game(v) -> tuple[float, float, float]:
    """Blender world (Z up, +Y forward) to game (Y up, -Z forward) — D00-R16.

    The same conversion :mod:`syndicate_dissect` uses, deliberately duplicated as three lines
    rather than imported: it returns a plain tuple here because :class:`Shell` is a pure-Python
    record that must not depend on ``mathutils``, and that is what lets the whole cue ensemble
    be unit-tested with no Blender host.
    """
    return (v.x, v.z, -v.y)


# ---- The pipeline ---------------------------------------------------------------------------


@dataclass
class Options:
    """One preparation run's configuration.

    Gathered into a record rather than passed as nine arguments because the CLI, the Gradle
    task and the tests all construct the same thing, and a positional list that long is a
    place for two of them to disagree silently.
    """

    model_dir: Path
    vehicle: str
    strict: bool = False
    seed: int = 1
    mass_kg: float | None = None
    display_name: str | None = None
    out: Path | None = None
    vehicles_out: Path | None = None
    material_table: Path = Path("assets/materials/materials.json")
    balance_table: Path = Path("assets/balance/classes.json")
    style_table: Path = Path("assets/materials/style.json")
    normalise_style: bool = True
    write_import: bool = True


def run(options: Options) -> dict:
    """The nine stages of D15-S5.1, in their order, returning the D15-S4.4 report.

    ``options.out`` is what separates a classification run from a preparation run. Without it
    stages 7 and 8 do not execute and the report describes what *would* be exported, which is
    the form worth running when a threshold changed. With it, the vehicle is written.
    """
    started = time.perf_counter()
    stages: dict[str, object] = {}

    overrides = Overrides.load(options.model_dir)

    # --- Stage 1: load, pose, correct ----------------------------------------------------
    stages["load"] = load_and_correct(options.model_dir)

    # --- Stage 1b: normalise the materials into the house style (D15-S9) -----------------
    #
    # Before the geometry is corrected, and that ordering is the point rather than an accident.
    # Restyling reads and writes materials only, so it is unaffected by scale or axes -- but
    # every stage after stage 2 progressively destroys the thing it needs. Separation shatters a
    # material group into thousands of shells (DISC-018), grouping joins shells that came from
    # different materials into one part, and the export writes one mesh per part. By the time a
    # part exists there is no material left to normalise, only a mesh that already carries the
    # wrong colours.
    stages["style"] = normalise_materials(options)

    # --- Stage 2: repair geometry (D15-S5.5) ---------------------------------------------
    plan = cleanup.plan(measure_objects())
    apply_correction(plan.correction)
    welded = clean_topology()
    composed = _existing_correction(options.model_dir).then(plan.correction)
    if options.write_import and not plan.correction.is_identity:
        _write_import_json(options.model_dir, composed)
    stages["cleanup"] = {
        **plan.as_dict(),
        "weld": welded,
        "importJson": composed.as_report(),
        "written": bool(options.write_import and not plan.correction.is_identity),
    }

    # --- Stage 3: separate into connected shells (D15-S5.2) ------------------------------
    shells, objects = separate_into_shells()
    stages["separate"] = {
        "shells": len(shells),
        "triangles": sum(shell.triangles for shell in shells),
    }

    overrides.verify_against({shell.material for shell in shells if shell.material})

    twins = cues.find_mirror_twins(shells)
    repairs = repair.inspect(shells, twins)
    stages["repair"] = repairs.as_dict()

    # --- Stage 4: label shells (D15-S4.2, D15-S4.3) --------------------------------------
    body = cues.BodyFrame(shells)
    for shell in shells:
        cues.label_shell(shell, body, twins.get(shell.index), overrides)
    role_counts = roles.assign_roles(shells, body)

    corners = roles.find_corners(shells, body)
    captured = roles.capture_into_corners(shells, corners)
    roles.resolve_rotation(corners)
    dissolved = roles.dissolve_empty_corners(corners, shells)
    stages["label"] = {
        "mirrorPairs": len(twins) // 2,
        "overrides": overrides.unused_report(),
        "roles": role_counts,
        "corners": [corner.as_dict() for corner in corners],
        "dissolvedCorners": dissolved,
        "capturedIntoCorners": captured,
    }

    # --- Stage 5: group shells into parts (D15-S5.3) -------------------------------------
    merged = grouping.merge_small_shells(shells)
    parts = grouping.group_into_parts(shells, twins)
    stages["group"] = {"parts": len(parts), "mergedSmallShells": merged}

    # --- Stages 6 to 8 -------------------------------------------------------------------
    build = assemble(options, shells, parts, corners, body, objects, stages)

    from .report import build_report

    return build_report(
        vehicle=options.vehicle,
        model_dir=options.model_dir,
        shells=shells,
        parts=parts,
        stages=stages,
        overrides=overrides,
        strict=options.strict,
        elapsed_s=time.perf_counter() - started,
        build=build,
    )


def assemble(options: Options, shells, parts, corners, body, objects, stages) -> dict:
    """Stages 6 to 8: rig, author, name, weigh and — when asked — write (D15-S5.1).

    The whole of this is arithmetic over the grouped parts plus, at the end, one pass over
    Blender. Splitting it out of :func:`run` keeps the stage order above readable as the nine
    lines D15-S5.1 lists.
    """
    densities = manifest.load_densities(options.material_table)
    class_targets = manifest.load_class_targets(options.balance_table)
    researched = profile.Profile.load(options.model_dir)
    stages["profile"] = researched.as_report()

    # A part too light to be a part goes into the chassis, exactly as a shell too small to be
    # a shell goes into its neighbour (D15-R17). A wheel that is not at a corner goes the same
    # way, for a different reason: it was never a wheel. Both keep every triangle accounted for.
    candidates, cornerless = manifest.absorb_cornerless_wheels(
        [group for group in parts if group.label not in (CHASSIS, UNCLASSIFIED)], corners
    )
    kept, absorbed = manifest.absorb_small_parts(candidates, densities)
    absorbed = absorbed + cornerless
    absorbed_shells = [shell for group in absorbed for shell in group.shells]
    stages.setdefault("group", {})["absorbedIntoChassis"] = {
        "parts": len(absorbed),
        "triangles": sum(group.triangles for group in absorbed),
        "floorKg": manifest.MIN_PART_MASS_KG,
        "cornerlessWheels": len(cornerless),
    }

    chassis_group = grouping.Part(label=CHASSIS, side="c", index=0)
    chassis_group.shells = sorted(
        [shell for shell in shells if shell.label in (CHASSIS, UNCLASSIFIED)] + absorbed_shells,
        key=lambda shell: shell.index,
    )
    prepared = manifest.prepare_parts(options.vehicle, kept, corners, body)
    chassis = manifest.PreparedPart(
        part_type_id=f"chassis_{options.vehicle}_01",
        slot_id="root",
        label=CHASSIS,
        role=None,
        side="c",
        group=chassis_group,
        origin=(0.0, 0.0, 0.0),
        material_id="steel",
        instances=["root"],
    )
    prepared.insert(0, chassis)

    # --- Stage 6: rig articulated parts (D15-S5.6) ---------------------------------------
    declared = {hinge.part: hinge for hinge in Overrides.load(options.model_dir).hinges}
    rigged = []
    for part in prepared:
        part.hinge = hinges.infer(part.group, body, declared.get(part.part_type_id))
        if part.hinge is not None:
            rigged.append({"part": part.part_type_id, **part.hinge.as_dict(),
                           "because": part.hinge.because})
    stages["rig"] = {"hinged": len(rigged), "hinges": rigged}

    # --- Mass, class and power -----------------------------------------------------------
    # A researched kerb mass outranks the footprint estimate and is outranked by --mass, which
    # is an operator saying "not this time" about a file they can see (D15-S11).
    target, target_note = manifest.target_mass_kg(
        body,
        options.mass_kg if options.mass_kg is not None else researched.kerb_mass_kg,
        corners,
        shells,
    )
    if options.mass_kg is None and researched.kerb_mass_kg is not None:
        target_note = f"the researched kerb mass of the {researched.reference or 'reference car'}"
    mass_report = manifest.assign_masses(prepared, chassis, densities, target)
    mass_report["source"] = target_note
    total = manifest.total_mass_kg(prepared)
    vehicle_class = researched.vehicle_class or manifest.vehicle_class_for(total)
    budget = class_targets.get(vehicle_class, 0.0)

    stats = manifest.chassis_stats(chassis.mass_kg)
    stats.update(researched.chassis_stats)
    references = {
        part.part_type_id: manifest.reference_power_cost(
            part,
            manifest.HP_PER_KG[part.destruction_class] * part.mass_kg,
            manifest.ARMOR_PER_KG.get(manifest.PART_CATEGORY[part.label], 0.0) * part.mass_kg,
            stats["engineForceN"]["add"] if part.is_chassis else 0.0,
        )
        for part in prepared
    }
    manifest.distribute_power(prepared, references, budget)
    stages["mass"] = mass_report
    stages["balance"] = {
        "vehicleClass": vehicle_class,
        "powerBudgetTarget": budget,
        "totalMassKg": round(total, 2),
    }

    # --- Stage 7: destruction authoring (D15-S5.7) ----------------------------------------
    stages["destruction"] = destruction.plan(parts)

    documents = {}
    exported: list[dict] = []
    if options.out is not None:
        exported = _export_all(options, prepared, chassis, objects)
        produced = {entry["partTypeId"]: entry for entry in exported}
        overridden = 0
        for part in prepared:
            override = produced.get(part.part_type_id, {}).get("massOverrideKg")
            if override:
                part.mass_kg = round(float(override), 3)
                overridden += 1
        if overridden:
            # The fracture manifest is authoritative for a glass part's mass the moment one
            # exists (A202), and it is produced *after* the balance was struck — so the chassis
            # takes the difference again. Without this the assembly weighed 1501.18 kg against a
            # researched 1500, which A310 allows and the profile calibration does not: the whole
            # point of a kerb mass is that it is the number the car is sold on.
            mass_report.update(
                manifest.assign_masses(prepared, chassis, densities, target, fixed=produced)
            )
            mass_report["source"] = target_note
    else:
        produced = {}

    # --- Stage 8: the documents ------------------------------------------------------------
    # The wheel slots need two facts about the whole vehicle: how stiff its springs are and how
    # many wheels it stands on, because together they fix how far it sags (`wheel_slot_lift`).
    stiffness = manifest.DEFAULT_SUSPENSION_STIFFNESS * (
        researched.wheel_stats.get("suspensionStiffness", {}).get("mul", 1.0)
    ) + researched.wheel_stats.get("suspensionStiffness", {}).get("add", 0.0)
    wheel_count = sum(
        max(1, len(part.instances)) for part in prepared if part.label == manifest.WHEEL
    )
    placements = {
        part.part_type_id: manifest.slot_positions(part, stiffness, wheel_count)
        for part in prepared
        if not part.is_chassis
    }
    slots = [
        manifest.build_slot(part, slot, position)
        for part in prepared
        if not part.is_chassis
        for slot, position in placements[part.part_type_id]
    ]
    # The mounting points for content this model does not contain (D15-R42). They are added to
    # the chassis's slot list and filled by nobody: a weapon or a module is shared content in
    # `assets/parts/`, and this is where one goes when a player fits it.
    hardpoints = manifest.hardpoint_slots(body, total)
    slots.extend(hardpoints)
    stages["hardpoints"] = {
        "slots": [slot["slotId"] for slot in hardpoints],
        "maxMassKg": hardpoints[0]["maxMassKg"] if hardpoints else 0.0,
    }

    # The main rotor's thrust first, because a tail rotor is sized as a fraction of it rather
    # than by its own disc loading (manifest.rotor_thrust_n). Zero when there is no main
    # rotor, which is every wheeled vehicle and costs nothing.
    main_thrust_n = max(
        (
            manifest.rotor_thrust_n(part, 0.0)
            for part in prepared
            if part.label == ROTOR and part.role == MAIN
        ),
        default=0.0,
    )
    rotor_thrusts = {
        part.part_type_id: manifest.rotor_thrust_n(part, main_thrust_n)
        for part in prepared
        if part.label == ROTOR
    }

    for part in prepared:
        document = manifest.build_part_document(
            part,
            chassis_slots=sorted(slots, key=lambda slot: slot["slotId"]) if part.is_chassis else [],
            stats=manifest.stats_for(part, stats, researched),
            handling=manifest.handling_for(part, researched),
            produced=produced.get(part.part_type_id),
            weapon=manifest.weapon_block(part, body) if part.label == WEAPON else None,
            light=manifest.light_block(part, body) if part.label == LIGHT else None,
            rotor=manifest.rotor_block(part, body) if part.label == ROTOR else None,
        )
        if part.label == ROTOR:
            # The stat rather than the block, so degradation reaches it (DEC-090): a rotor
            # shot to half health lifts less, and nothing in the flight model knows that.
            # camelCase key and an {"add": ...} term, which is what every other stat in a
            # part.json is and what AssetLoader parses. Written as a bare number under a
            # SCREAMING_CASE key first, which loaded silently as no stat at all and left the
            # aircraft on the ground with a rotor that turned and lifted nothing.
            document.setdefault("stats", {})["rotorThrustN"] = {
                "add": rotor_thrusts[part.part_type_id]
            }
        if options.out is not None:
            documents[Path(options.out) / part.part_type_id / "part.json"] = document

    assembly = manifest.build_assembly_document(
        options.vehicle,
        options.display_name
        or researched.display_name
        or options.vehicle.replace("_", " ").title(),
        prepared,
        chassis,
        vehicle_class,
        placements,
    )
    if options.vehicles_out is not None:
        documents[
            Path(options.vehicles_out) / assembly["vehicleTypeId"] / "assembly.json"
        ] = assembly

    # `manifest.json` beside the parts it describes (D08-R14b). Written whenever the parts are:
    # a description of a part set that is not on disk would be a file about nothing.
    parts_manifest = manifest.build_parts_manifest(
        vehicle_type_id=assembly["vehicleTypeId"],
        display=assembly["displayName"],
        vehicle_class=vehicle_class,
        prepared=prepared,
        chassis=chassis,
        assembly=assembly,
        source={
            "modelDir": str(options.model_dir),
            "seed": options.seed,
            "styleId": (stages.get("style") or {}).get("styleId"),
            "sourceKind": (stages.get("style") or {}).get("sourceKind"),
            "massSource": mass_report.get("source"),
            "referenceVehicle": researched.reference,
        },
        hardpoints=hardpoints,
        produced=produced,
    )
    if options.out is not None:
        documents[Path(options.out) / manifest.PARTS_MANIFEST_FILE] = parts_manifest

    written = exporter.write_documents(documents) if documents else []
    return {
        "parts": [
            {
                "partTypeId": part.part_type_id,
                "label": part.label,
                "role": part.role,
                "slots": part.instances,
                "massKg": part.mass_kg,
                "powerCost": part.power_cost,
                "materialId": part.material_id,
                "destructionClass": part.destruction_class,
                "areaM2": round(sum(s.area_m2 for s in part.group.shells), 4),
                "enclosedM3": round(sum(s.volume_m3 for s in part.group.shells), 6),
                "triangles": part.group.triangles,
                "originM": [round(value, 4) for value in part.origin],
                "hinged": part.hinge is not None,
            }
            for part in prepared
        ],
        "assembly": assembly,
        "exported": exported,
        "written": written,
    }


def _export_all(options: Options, prepared, chassis, objects) -> list[dict]:
    """Stage 8's Blender half: one mesh per part, then the fractures, then nothing else.

    The fracture runs after every mesh is on disk because the D09 tool reloads the scene
    (D09-R15), which would invalidate every object reference the parts still being exported
    are holding.
    """
    out = Path(options.out)
    results = []
    for part in prepared:
        members = [objects[shell.index] for shell in part.group.shells if shell.index in objects]
        results.append(exporter.export_part(part, members, out, options.seed))
    for part, produced in zip(prepared, results, strict=True):
        if destruction.treatment_for(part.label).fracture_shards and part is not chassis:
            exporter.author_fracture(part, out, options.material_table, options.seed, produced)
    return [
        {**produced.as_dict(), "massOverrideKg": produced.mass_override_kg}
        for produced in results
    ]


def _existing_correction(model_dir: Path):
    """Whatever ``import.json`` already applies, as a :class:`cleanup.Correction`."""
    document = _read_import_json(model_dir)
    translation = document.get("translationM", {}) or {}
    return cleanup.Correction(
        scale=float(document.get("scaleToMetres", 1.0)),
        yaw_deg=float(document.get("yawDeg", 0.0)),
        translation=(
            float(translation.get("x", 0.0)),
            float(translation.get("y", 0.0)),
            float(translation.get("z", 0.0)),
        ),
    )


def _write_import_json(model_dir: Path, correction) -> None:
    """Record the composed correction beside the model (DEC-036).

    Composed, not residual: the model was already loaded through the old file, so writing the
    residual would drop whatever that file was doing and the next run would arrive at a
    different vehicle. Writing the composition makes the pipeline idempotent — run it twice
    and the second run's residual is the identity.
    """
    import json

    path = Path(model_dir) / "import.json"
    document = correction.as_import_json(
        "Correction from the source art's units and axes to the game's (D00-R16), derived by "
        "syndicate-prepare (D15-S5.5) and verified by `syndicate-verify --model`."
    )
    path.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")


def unclassified_fraction(shells: list[Shell]) -> float:
    """Triangle share the ensemble could not name (D15-R2, D15-R13)."""
    total = sum(shell.triangles for shell in shells)
    if total == 0:
        return 0.0
    unnamed = sum(shell.triangles for shell in shells if shell.label == UNCLASSIFIED)
    return unnamed / total
