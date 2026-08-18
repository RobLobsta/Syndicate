"""The orchestrator: one structure model in, a destructible assembly out (D16-S7).

Nine stages, of which three are this package's and six are reused:

===  ==============================  ====================================================
 1   load and correct                 ``syndicate_dissect`` (DEC-036)
 2   normalise style                  ``syndicate_prepare.style`` (DEC-076, DEC-079)
 3   measure                          here — area, enclosure, bounds, per source object
 4   **cut**                          :mod:`~syndicate_structure.bands` — the one new idea
 5   **plan**                         :mod:`~syndicate_structure.graph` — the support chain
 6   weigh                            :mod:`~syndicate_structure.mass` (DEC-067's rule)
 7   export                           ``syndicate_dissect.emit`` — origins, hulls, ``mesh.glb``
 8   author destruction               ``syndicate_deform`` / ``syndicate_fracture``
 9   **document**                     :mod:`~syndicate_structure.documents`
===  ==============================  ====================================================

The reuse is the point. D16-R81 says that if a structure needs new machinery the design has
drifted; the same argument applies one layer up, at authoring time, and the answer to "how does a
building get damage morphs" has to be "the way a door does" or the two will diverge.
"""

from __future__ import annotations

import contextlib
import json
import math
from dataclasses import dataclass, field
from pathlib import Path

from . import bands as bands_module
from . import documents, graph, mass, split
from .graph import PartPlan

try:  # pragma: no cover - exercised only inside a Blender host
    import bmesh  # isort: skip
    import bpy  # isort: skip
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bpy = bmesh = None  # type: ignore[assignment]

#: D08-R2's per-part visual budget. A part over it is decimated down rather than rejected: the
#: turret arrives at 163,616 triangles over 134 objects, which is an art decision made before
#: this project saw the file, and refusing it would leave the structure subsystem with no content.
MAX_PART_TRIANGLES = 8000

#: Never decimate below this, whatever the budget says. A part reduced to a hundred triangles is
#: a silhouette, and a structure is mostly silhouette.
MIN_PART_TRIANGLES = 240

#: Amplitudes for the damage morphs, in metres, tried in order until one passes D09's guards.
#: An order of magnitude above the vehicle pipeline's 4 cm, because the dent has to read at the
#: distance a building is looked at from.
MORPH_AMPLITUDES_M = (0.25, 0.15, 0.08)

#: Source material names that mean glass, matched case-insensitively as substrings. Everything
#: else is whatever ``parts.json`` says, or concrete.
GLASS_TOKENS = ("glass", "window", "glazing")

#: The default material for a structure part with nothing better known about it.
DEFAULT_MATERIAL = "concrete"


class StructureError(RuntimeError):
    """A failure with the exit code that describes it (D09-S4.3)."""

    def __init__(self, message: str, code: int, report: dict | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.report = report


@dataclass
class Options:
    model: Path
    out: Path | None = None
    structure_id: str | None = None
    display_name: str | None = None
    seed: int = 1
    band_target_m: float = bands_module.BAND_TARGET_M
    material_table: Path = Path("assets/materials/materials.json")
    style_table: Path = Path("assets/materials/style.json")
    normalise_style: bool = True
    strict: bool = False


@dataclass
class Measured:
    """Stage 3's output: the pieces, and the Blender objects they came from."""

    pieces: list[bands_module.Piece] = field(default_factory=list)
    objects: dict[str, object] = field(default_factory=dict)


# ---- Stage 3: measure ------------------------------------------------------------------


def _to_game(v):
    """Blender (x, y, z) is game (x, z, -y): the one axis convention in the suite (D00-R16)."""
    from mathutils import Vector

    return Vector((v.x, v.z, -v.y))


def _first_material(obj) -> str:
    """The name of an object's first material slot, or empty when it has none."""
    materials = obj.data.materials
    return materials[0].name if materials and materials[0] else ""


def measure_scene() -> Measured:
    """Every mesh object, with the two quantities its mass comes from.

    Area and enclosed volume are taken from a ``bmesh`` of the *evaluated* object, so a modifier
    nobody applied cannot make a part weigh what the file says instead of what it looks like.
    """
    out = Measured()
    depsgraph = bpy.context.evaluated_depsgraph_get()
    for obj in sorted(bpy.context.scene.objects, key=lambda o: o.name):
        if obj.type != "MESH" or not obj.data.vertices:
            continue
        evaluated = obj.evaluated_get(depsgraph)
        mesh = bmesh.new()
        mesh.from_mesh(evaluated.to_mesh())
        mesh.transform(obj.matrix_world)
        area = sum(face.calc_area() for face in mesh.faces)
        try:
            volume = abs(mesh.calc_volume(signed=True))
        except ValueError:
            volume = 0.0
        mesh.free()
        evaluated.to_mesh_clear()

        lo = [1e9, 1e9, 1e9]
        hi = [-1e9, -1e9, -1e9]
        for vertex in obj.data.vertices:
            g = _to_game(obj.matrix_world @ vertex.co)
            lo = [min(lo[0], g.x), min(lo[1], g.y), min(lo[2], g.z)]
            hi = [max(hi[0], g.x), max(hi[1], g.y), max(hi[2], g.z)]
        obj.data.calc_loop_triangles()
        out.pieces.append(
            bands_module.Piece(
                name=obj.name,
                lo=(lo[0], lo[1], lo[2]),
                hi=(hi[0], hi[1], hi[2]),
                triangles=len(obj.data.loop_triangles),
                area_m2=area,
                volume_m3=volume,
                material=_first_material(obj),
            )
        )
        out.objects[obj.name] = obj
    return out


# ---- Stage 6: materials and mass -------------------------------------------------------


def dominant_material(part: PartPlan, overrides: dict[str, str]) -> str:
    """What a part is made of: its override, or the material covering most of its surface.

    Surface rather than object count, because a building's glazing is one object per facade and
    its walls are one object for the whole block — counting objects would call a concrete tower
    a greenhouse.
    """
    override = overrides.get(part.role)
    if override:
        return override
    by_material: dict[str, float] = {}
    for piece in part.component.pieces:
        by_material[piece.material] = by_material.get(piece.material, 0.0) + piece.area_m2
    if not by_material:
        return DEFAULT_MATERIAL
    winner = max(sorted(by_material.items()), key=lambda item: item[1])[0]
    return map_material(winner)


def map_material(source_name: str) -> str:
    """A source material name to a ``materials.json`` id.

    Only glass is detected by name, and only because getting it wrong is the one mistake that
    shows: a pane authored as concrete dents instead of shattering. Everything else falls to
    concrete and is corrected by ``parts.json`` where it matters, which is DEC-068's rule the
    other way round — a cue is preferred where one exists, and no cue distinguishes a painted
    concrete wall from a painted brick one in an untextured mesh.
    """
    lowered = (source_name or "").lower()
    if any(token in lowered for token in GLASS_TOKENS):
        return "glass"
    return DEFAULT_MATERIAL


def destruction_class_for(material_id: str) -> str:
    """How a part of this material fails (D15-S5.7).

    Glass shatters; everything a structure is otherwise made of buckles. ``STRUCTURAL`` rather
    than ``SHEET_METAL`` for the same reason a chassis is: these are load-bearing members that
    give way globally, not skins that crumple locally, and the two classes differ by an order of
    magnitude in how finely they are subdivided before being dented.
    """
    return "GLASS" if material_id == "glass" else "STRUCTURAL"


def weigh(plans: list[PartPlan], densities: dict[str, float], overrides: dict[str, str]) -> None:
    """Stage 6, in place: material, class and mass for every part."""
    for part in plans:
        part.material_id = dominant_material(part, overrides)
        part.destruction_class = destruction_class_for(part.material_id)
        part.mass_kg = mass.part_mass_kg(
            sum(p.area_m2 for p in part.component.pieces),
            sum(p.volume_m3 for p in part.component.pieces),
            part.material_id,
            densities.get(part.material_id, 2400.0),
        )


# ---- Stage 7: export -------------------------------------------------------------------


def decimate_to_budget(obj, budget: int = MAX_PART_TRIANGLES) -> int:
    """Collapse-decimate a part down to the D08-R2 budget, and report what it ended at."""
    obj.data.calc_loop_triangles()
    before = len(obj.data.loop_triangles)
    if before <= budget:
        return before
    target = max(MIN_PART_TRIANGLES, budget)
    modifier = obj.modifiers.new("budget", "DECIMATE")
    modifier.decimate_type = "COLLAPSE"
    modifier.ratio = target / before
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=modifier.name)
    obj.data.calc_loop_triangles()
    return len(obj.data.loop_triangles)


def export_part(
    part: PartPlan, objects: dict[str, object], out_root: Path
) -> tuple[list[str], int]:
    """Join, re-origin, decimate, dent, hull and write one part's ``mesh.glb``.

    The order matters and is the vehicle exporter's: re-origin before authoring, so the shape
    keys are stored in the space the part is drawn in; hull after, so the hull encloses the
    *undamaged* mesh, because collision geometry never deforms (D06-NG5).
    """
    from mathutils import Vector

    from syndicate_dissect import emit

    sources = [objects[piece.name] for piece in sorted(part.component.pieces, key=lambda p: p.name)]
    joined = emit.join_objects(sources, part.part_type_id)
    if joined is None:
        part.notes.append("no geometry")
        return [], 0

    # `part.origin` is in game space; `recentre_on` wants Blender's.
    ox, oy, oz = part.origin
    emit.recentre_on(joined, Vector((ox, -oz, oy)))

    triangles = decimate_to_budget(joined)
    if triangles != sum(p.triangles for p in part.component.pieces):
        part.notes.append(f"decimated to {triangles} triangles for D08-R2's budget")

    morphs: list[str] = []
    if part.destruction_class != "GLASS":
        morphs = author_morphs(joined, part)

    collision = emit.build_collision_hull(joined, f"{part.part_type_id}_col")
    emit.export_part(joined, collision, out_root / part.part_type_id)
    return morphs, triangles


def author_morphs(obj, part: PartPlan) -> list[str]:
    """The DEFORM transform (D09-S5.3), or none and a note saying why.

    A failure is content rather than a crash, exactly as it is for a vehicle part: D09's guards
    reject a morph that is invisible or that inverts a face, and a part too coarse to dent trips
    them legitimately. The part then ships rigid and the note says so.
    """
    from syndicate_deform.morphs import generate_damage_morphs, morph_names
    from syndicate_fracture.errors import ToolError

    last: Exception | None = None
    for amplitude in MORPH_AMPLITUDES_M:
        try:
            names = morph_names(generate_damage_morphs(obj, levels=4, amplitude=amplitude, seed=1))
        except ToolError as error:
            last = error
            while obj.data.shape_keys is not None and obj.data.shape_keys.key_blocks:
                obj.shape_key_remove(obj.data.shape_keys.key_blocks[0])
            continue
        if amplitude != MORPH_AMPLITUDES_M[0]:
            part.notes.append(f"damage morphs at {amplitude:g} m amplitude")
        return names
    part.notes.append(f"no damage morphs: {last}")
    return []


def author_fracture(part: PartPlan, out_root: Path, material_table: Path, seed: int) -> bool:
    """The FRACTURE transform, for the classes D15-S5.7 gives it (glass).

    Runs after every mesh is on disk because the fracture pipeline reloads the scene — the same
    constraint, and the same ordering, as the vehicle exporter's.
    """
    from syndicate_fracture.cli import Args
    from syndicate_fracture.errors import ToolError
    from syndicate_fracture.pipeline import run as fracture_run
    from syndicate_prepare.manifest import WALL_THICKNESS_M

    directory = out_root / part.part_type_id
    try:
        fracture_run(
            Args(
                input=directory / "mesh.glb",
                out=directory,
                object=part.part_type_id,
                seed=seed,
                shards=24,
                destruction_class=part.destruction_class,
                material_table=material_table,
                material_override=part.material_id,
                shell_thickness=WALL_THICKNESS_M[part.destruction_class],
            )
        )
    except (ToolError, Exception) as error:
        part.notes.append(f"not fractured: {error}")
        return False
    manifest = directory / "fracture_manifest.json"
    if manifest.is_file():
        # A202 cross-checks part.json's massKg against the fracture manifest, and the manifest's is
        # volume x density over the solid the tool actually fractured — the authoritative number the
        # moment one exists. A manifest that cannot be read leaves the measured mass standing.
        with contextlib.suppress(OSError, ValueError, KeyError, TypeError):
            part.mass_kg = float(json.loads(manifest.read_text(encoding="utf-8"))["partMassKg"])
    return True


# ---- The run ---------------------------------------------------------------------------


def load_overrides(model_dir: Path) -> dict:
    """``parts.json`` beside the model: what the geometry cannot say (D15-S4.3's mechanism)."""
    path = model_dir / "parts.json"
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def densities(table: Path) -> dict[str, float]:
    document = json.loads(table.read_text(encoding="utf-8"))
    rows = document["materials"] if isinstance(document, dict) else document
    return {row["materialId"]: float(row["densityKgPerM3"]) for row in rows}


def footprint_radius_m(pieces: list[bands_module.Piece]) -> float:
    """What placement spaces this structure by (D16-R20): its horizontal half-diagonal."""
    lo_x = min(p.lo[0] for p in pieces)
    hi_x = max(p.hi[0] for p in pieces)
    lo_z = min(p.lo[2] for p in pieces)
    hi_z = max(p.hi[2] for p in pieces)
    return math.hypot(hi_x - lo_x, hi_z - lo_z) / 2.0


def run(options: Options) -> dict:
    """Every stage, in order. Returns the report; raises :class:`StructureError` on a failure."""
    from syndicate_dissect import dissect
    from syndicate_policy.exit_codes import EXIT_EXPORT_FAILED, EXIT_INPUT_GEOMETRY_INVALID

    model_dir = options.model if options.model.is_dir() else options.model.parent
    overrides = load_overrides(model_dir)
    name = options.structure_id or model_dir.name
    structure_id = name if name.startswith("str_") else f"str_{name}_01"
    stem = structure_id.removeprefix("str_").removesuffix("_01")
    display = options.display_name or overrides.get("displayName") or stem.replace("_", " ").title()
    findings: list[str] = []

    dissect.load_model(model_dir)
    style_report = apply_style(options)
    if style_report.get("error"):
        findings.append(f"style: {style_report['error']}")

    # Measured twice, deliberately. The first pass exists only to find the scene's height, so
    # the band planes are known; the geometry is then bisected at them (D16-S7.1's cut is a cut,
    # not a sort), and the second pass measures what that produced.
    survey = measure_scene()
    if not survey.pieces:
        raise StructureError("no geometry in the model", EXIT_INPUT_GEOMETRY_INVALID)
    edges = bands_module.edges_for(survey.pieces, options.band_target_m)
    gained = split.split_at_bands(edges)
    measured = measure_scene() if gained else survey
    if gained:
        findings.append(f"bisected at {len(edges) - 2} band plane(s); {gained:+d} objects")

    cut = bands_module.cut(measured.pieces, edges)
    plans = graph.plan(cut, stem)
    weigh(plans, densities(options.material_table), overrides.get("materials", {}))
    apply_weapons(plans, overrides)

    height_m = max(p.hi[1] for p in survey.pieces) - min(p.lo[1] for p in survey.pieces)
    radius_m = footprint_radius_m(survey.pieces)

    if options.out is None:
        return report(structure_id, plans, findings, style_report, radius_m, height_m)

    out_root = options.out / structure_id / "parts"
    out_root.mkdir(parents=True, exist_ok=True)
    morphs: dict[str, list[str]] = {}
    for part in plans:
        produced, triangles = export_part(part, measured.objects, out_root)
        morphs[part.part_type_id] = produced
        part.triangles = triangles
    fractured = {
        part.part_type_id: author_fracture(part, out_root, options.material_table, options.seed)
        for part in plans
        if part.destruction_class == "GLASS"
    }

    by_parent: dict[str, list[PartPlan]] = {}
    for part in plans:
        if part.parent_id:
            by_parent.setdefault(part.parent_id, []).append(part)
    written = {}
    for part in plans:
        written[out_root / part.part_type_id / "part.json"] = documents.part_document(
            part,
            by_parent.get(part.part_type_id, []),
            display,
            morphs.get(part.part_type_id, []),
            fractured.get(part.part_type_id, False),
        )
    written[options.out / structure_id / "structure.json"] = documents.structure_document(
        structure_id, display, plans, radius_m, height_m
    )
    for path, document in sorted(written.items(), key=lambda item: str(item[0])):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    missing = [
        p.part_type_id for p in plans if not (out_root / p.part_type_id / "mesh.glb").is_file()
    ]
    if missing:
        raise StructureError(
            f"no mesh written for {', '.join(sorted(missing))}",
            EXIT_EXPORT_FAILED,
            report(structure_id, plans, findings, style_report, radius_m, height_m),
        )
    return report(structure_id, plans, findings, style_report, radius_m, height_m)


def apply_style(options: Options) -> dict:
    """Stage 2: the same house style a car and a gun go through (DEC-076, DEC-079)."""
    if not options.normalise_style:
        return {"applied": False, "reason": "disabled with --no-style"}
    try:
        from syndicate_prepare import style

        table = style.StyleTable.load(options.style_table)
        return {"applied": True, **style.apply_to_scene(table, options.seed)}
    except Exception as error:  # pragma: no cover - a missing table is a content problem
        return {"applied": False, "error": str(error)}


def apply_weapons(plans: list[PartPlan], overrides: dict) -> None:
    """A built-in weapon on the parts ``parts.json`` names (DEC-077, D15-S5.10).

    The muzzle is **derived** — the forward-most point of the part along +Z, on its own axis —
    because that is a fact about the geometry. The family is not: nothing in an untextured box
    distinguishes a rocket pod from a cannon breech, which is D17-R50's own conclusion, so it is
    authored beside the model like a weapon's family always has been.
    """
    by_role = {part.role: part for part in plans}
    for entry in overrides.get("weapons", []):
        part = by_role.get(entry.get("part", ""))
        if part is None:
            continue
        lo_x = min(p.lo[0] for p in part.component.pieces)
        hi_x = max(p.hi[0] for p in part.component.pieces)
        hi_z = max(p.hi[2] for p in part.component.pieces)
        centre_y = sum(p.centre_y for p in part.component.pieces) / len(part.component.pieces)
        part.weapon = {
            "family": entry.get("family", "ROCKET"),
            "ammoCapacity": int(entry.get("ammoCapacity", -1)),
            "blastRadiusM": float(entry.get("blastRadiusM", 0.0)),
            "rangeM": float(entry.get("rangeM", 0.0)),
            "damageType": entry.get("damageType"),
            "muzzleLocal": {
                "x": round((lo_x + hi_x) / 2.0 - part.origin[0], 4),
                "y": round(centre_y - part.origin[1], 4),
                "z": round(hi_z - part.origin[2], 4),
            },
            "stats": entry.get("stats", {}),
        }


def report(
    structure_id: str,
    plans: list[PartPlan],
    findings: list[str],
    style_report: dict,
    radius_m: float,
    height_m: float,
) -> dict:
    document = documents.report_document(structure_id, plans, findings)
    document["style"] = style_report
    document["footprint"] = {"radiusM": round(radius_m, 3), "heightM": round(height_m, 3)}
    return document
