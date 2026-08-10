"""The stage order of D15-S5.1, driven inside a Blender host.

::

    1. Load, pose, and correct        (D09-S5.1 conventions; DISC-016 for why posing is first)
    2. Repair geometry                 (D15-S5.5)
    3. Separate into connected shells  (D15-S5.2)
    4. Label shells                    (D15-S4.2 ensemble, D15-S4.3 overrides)
    5. Group shells into parts         (D15-S5.3)
    6. Report                          (D15-S4.4)

Stages 6 through 8 of D15-S5.1 — rigging, per-class destruction authoring, and export — are
not implemented here. They are named in the report as ``pending`` rather than omitted, because
a pipeline that quietly stopped early is indistinguishable from one that had nothing to do.

Everything that reads Blender is in this module. Everything that decides anything is in
:mod:`syndicate_prepare.cues`, :mod:`syndicate_prepare.grouping` and
:mod:`syndicate_prepare.repair`, which is what lets the decisions be unit-tested with no host.
"""

from __future__ import annotations

import time
from pathlib import Path

from . import cues, grouping, repair
from .labels import MAX_SHELLS, UNCLASSIFIED
from .overrides import Overrides
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


def separate_into_shells() -> list[Shell]:
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
    shells = [measure(obj, index) for index, obj in enumerate(ordered)]
    return [shell for shell in shells if shell.triangles > 0]


def measure(obj, index: int) -> Shell:
    """Reads one object into a :class:`Shell`, in world space."""
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
    return shell


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


def run(model_dir: Path, vehicle: str, strict: bool = False) -> dict:
    """Runs stages 1 to 6 and returns the D15-S4.4 report.

    :param model_dir: ``art-source/vehicles/<name>``
    :param vehicle: the short vehicle name, for the report and the part ids
    :param strict: whether an under-labelled model is an error (D15-R13)
    """
    started = time.perf_counter()
    stages: dict[str, object] = {}

    overrides = Overrides.load(model_dir)
    stages["load"] = load_and_correct(model_dir)

    shells = separate_into_shells()
    stages["separate"] = {
        "shells": len(shells),
        "triangles": sum(shell.triangles for shell in shells),
    }

    materials = {shell.material for shell in shells if shell.material}
    overrides.verify_against(materials)

    twins = cues.find_mirror_twins(shells)
    repairs = repair.inspect(shells, twins)
    stages["repair"] = repairs.as_dict()

    body = cues.BodyFrame(shells)
    for shell in shells:
        cues.label_shell(shell, body, twins.get(shell.index), overrides)

    merged = grouping.merge_small_shells(shells)
    parts = grouping.group_into_parts(shells, twins)
    stages["label"] = {
        "mergedSmallShells": merged,
        "mirrorPairs": len(twins) // 2,
        "overrides": overrides.unused_report(),
    }
    stages["group"] = {"parts": len(parts)}

    from .report import build_report

    report = build_report(
        vehicle=vehicle,
        model_dir=model_dir,
        shells=shells,
        parts=parts,
        stages=stages,
        overrides=overrides,
        strict=strict,
        elapsed_s=time.perf_counter() - started,
    )
    return report


def unclassified_fraction(shells: list[Shell]) -> float:
    """Triangle share the ensemble could not name (D15-R2, D15-R13)."""
    total = sum(shell.triangles for shell in shells)
    if total == 0:
        return 0.0
    unnamed = sum(shell.triangles for shell in shells if shell.label == UNCLASSIFIED)
    return unnamed / total
