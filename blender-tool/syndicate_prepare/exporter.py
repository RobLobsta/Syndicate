"""Stages 7 and 8, inside Blender: author the destruction, then re-origin and export.

:mod:`syndicate_prepare.destruction` decides *what* treatment each part gets and
:mod:`syndicate_prepare.manifest` computes *what the documents say*. This module is the half
that touches the scene: it joins each part's shells into one object, gives it the origin its
slot is measured from, subdivides and dents what deforms, builds the collision hull, and writes
``mesh.glb``.

**What is produced is what is written.** Every stage here reports whether it actually
succeeded, and the manifest is built from that report rather than from the plan. A part whose
morphs failed a D09 guard ships without a ``morphTargets`` array instead of shipping a
``part.json`` that promises four shape keys the file does not contain — which would pass this
tool and fail in the asset gate, one layer further from the cause.

The glass fracture (D15-S5.7) runs **last**, after every mesh is on disk, and it runs through
the D09 tool rather than beside it. Two reasons: that tool already self-verifies, and it
reloads the scene per object (D09-R15's round-trip check), which would destroy the scene this
module is still working in if it ran any earlier.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .destruction import treatment_for
from .manifest import WALL_THICKNESS_M

try:  # pragma: no cover - exercised only inside a Blender host
    import bpy  # isort: skip
    import bmesh  # isort: skip
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bpy = bmesh = None  # type: ignore[assignment]

#: Faces a part may be subdivided up to. A chassis arrives with a hundred thousand and needs
#: no subdivision at all; a door skin arrives with two hundred and needs a lot. The cap is
#: what stops a part that arrives somewhere in between from being subdivided into a part no
#: renderer wants, and it is per part rather than per vehicle for the same reason.
MAX_SUBDIVIDED_FACES = 40_000

#: Passes of edge subdivision. Each halves the longest edges, so three passes take a 0.6 m
#: edge to 0.075 m — past the sheet-metal target — and no pass runs if nothing is too long.
MAX_SUBDIVISION_PASSES = 3

#: Amplitudes handed to the D09 morph generator, in metres of inward displacement at full
#: damage, tried in order until one passes that tool's own guards.
#:
#: 4 cm reads as a dent on a door at gameplay distance and stays inside the collision hull,
#: which never deforms (D06-NG5). It is too much for a chassis: measured on the Eclipse's, a
#: 4 cm dent collapses a face somewhere in 181,000 triangles and D09's zero-area guard
#: rejects the whole morph — correctly, and leaving the largest part on the car with no
#: deformation at all. Retrying smaller costs one pass and is the difference between a car
#: that dents and a car that does not.
#:
#: The ladder does not go below 2 cm, because D09's *other* guard requires each level to
#: displace at least 5 mm and `dmg_25` displaces a quarter of the amplitude: going lower
#: trades one guard failure for the opposite one.
MORPH_AMPLITUDES_M = (0.04, 0.03, 0.02)


@dataclass
class Produced:
    """What stage 7 and stage 8 actually managed, per part."""

    part_type_id: str
    triangles: int = 0
    subdivided_from: int = 0
    morphs: list[str] = field(default_factory=list)
    shards: int = 0
    mass_override_kg: float | None = None
    notes: list[str] = field(default_factory=list)

    @property
    def has_morphs(self) -> bool:
        return len(self.morphs) == 4

    @property
    def has_shards(self) -> bool:
        return self.shards > 0

    def as_dict(self) -> dict:
        return {
            "partTypeId": self.part_type_id,
            "triangles": self.triangles,
            "subdividedFrom": self.subdivided_from,
            "morphTargets": self.morphs,
            "shards": self.shards,
            "notes": self.notes,
        }


def to_blender(point) -> tuple[float, float, float]:
    """Game space to Blender space: game ``(x, y, z)`` is Blender ``(x, -z, y)`` (D00-R16)."""
    return (point[0], -point[2], point[1])


def export_part(part, objects, out_root: Path, seed: int) -> Produced:
    """Join, re-origin, author and write one part. Returns what was produced.

    The order is not interchangeable. Joining first means the morph generator sees the whole
    part and dents it as one surface rather than denting each shell about its own centre.
    Re-origining before authoring means the shape keys are stored in the space the part will
    be drawn in. Building the hull after authoring means the hull encloses the *undamaged*
    mesh, which is the one the physics uses, because collision geometry never deforms.
    """
    from syndicate_dissect import emit

    produced = Produced(part_type_id=part.part_type_id)
    joined = emit.join_objects(objects, part.part_type_id)
    if joined is None:
        produced.notes.append("no geometry")
        return produced

    emit.recentre_on(joined, to_blender(part.origin))

    treatment = treatment_for(part.label)
    if treatment.fracture_shards:
        wall = WALL_THICKNESS_M[treatment.destruction_class]
        if not solidify(joined, wall):
            produced.notes.append("could not be solidified; it will not fracture")
    if treatment.subdivide_edge_m > 0.0:
        produced.subdivided_from = len(joined.data.polygons)
        subdivide_to(joined, treatment.subdivide_edge_m)
    if treatment.morphs:
        produced.morphs = generate_morphs(joined, seed, produced)

    collision = emit.build_collision_hull(joined, f"{part.part_type_id}_col")
    joined.data.calc_loop_triangles()
    produced.triangles = len(joined.data.loop_triangles)
    emit.export_part(joined, collision, out_root / part.part_type_id)
    return produced


def subdivide_to(obj, target_edge_m: float) -> None:
    """Subdivide edges longer than ``target_edge_m``, bounded by :data:`MAX_SUBDIVIDED_FACES`.

    A panel crumples locally and keeps its area (D15-S5.7), which needs vertex density where
    the dent is or the dent is a facet. A door skin exported from a game model is often four
    quads, and four quads cannot dent.
    """
    for _pass in range(MAX_SUBDIVISION_PASSES):
        mesh = bmesh.new()
        mesh.from_mesh(obj.data)
        long_edges = [edge for edge in mesh.edges if edge.calc_length() > target_edge_m]
        if not long_edges or len(mesh.faces) >= MAX_SUBDIVIDED_FACES:
            mesh.free()
            return
        bmesh.ops.subdivide_edges(mesh, edges=long_edges, cuts=1, use_grid_fill=True)
        mesh.to_mesh(obj.data)
        mesh.free()
        obj.data.update()


def generate_morphs(obj, seed: int, produced: Produced) -> list[str]:
    """The D09 damage shape keys, or none and a note saying why (D09-S5.3, D15-S5.7).

    A failure here is content, not a crash. The generator's guards exist to stop a morph that
    is invisible or that inverts a face from shipping, and a part too coarse to dent
    meaningfully trips them legitimately — a wing mirror is 60 triangles. The part then ships
    rigid, which is what it would have been if the taxonomy had called it one.
    """
    from syndicate_fracture.cli import Args
    from syndicate_fracture.errors import ToolError
    from syndicate_fracture.morphs import generate_damage_morphs, morph_names

    last: Exception | None = None
    for amplitude in MORPH_AMPLITUDES_M:
        args = Args(
            input=Path("."), out=Path("."), seed=seed,
            damage_morphs=4, morph_amplitude=amplitude,
        )
        try:
            names = morph_names(generate_damage_morphs(obj, args))
        except ToolError as error:
            last = error
            _clear_shape_keys(obj)
            continue
        if amplitude != MORPH_AMPLITUDES_M[0]:
            produced.notes.append(f"damage morphs generated at {amplitude:g} m amplitude")
        return names
    produced.notes.append(f"no damage morphs: {last}")
    return []


def _clear_shape_keys(obj) -> None:
    """Drop a failed attempt's keys, so the next amplitude starts from the undamaged mesh."""
    while obj.data.shape_keys is not None and obj.data.shape_keys.key_blocks:
        obj.shape_key_remove(obj.data.shape_keys.key_blocks[0])


def fracture_glass(part, out_root: Path, material_table: Path, seed: int, produced: Produced):
    """Cell-fracture a ``glass`` part through the D09 tool (D15-S5.7).

    Runs after every mesh is written, because the fracture pipeline reloads the scene. The
    part's own ``mesh.glb`` is both the input and part of the output: the tool republishes it
    alongside ``shards.glb`` and ``fracture_manifest.json``, keeping the ``_col`` node it
    finds beside the visual mesh.

    A failure is reported and the part ships unfractured — it will then detach whole, which
    D07-E5 already handles and the asset gate already warns about (A213). A pane that cannot
    be fractured is not a reason to fail a whole vehicle.
    """
    from syndicate_fracture.cli import Args
    from syndicate_fracture.errors import ToolError
    from syndicate_fracture.pipeline import run as fracture_run

    directory = out_root / part.part_type_id
    treatment = treatment_for(part.label)
    args = Args(
        input=directory / "mesh.glb",
        out=directory,
        object=part.part_type_id,
        seed=seed,
        shards=treatment.fracture_shards,
        damage_morphs=0,
        material_table=material_table,
        material_override=part.material_id,
    )
    try:
        summary = fracture_run(args)
    except ToolError as error:
        produced.notes.append(f"not fractured: {error}")
        return produced
    except Exception as error:  # pragma: no cover - a Blender operator can raise anything
        produced.notes.append(f"not fractured: {error}")
        return produced

    produced.shards = treatment.fracture_shards
    # A202 cross-checks part.json's massKg against the fracture manifest within
    # MASS_DELTA_FRAC. The manifest's is volume x density over the solid the tool actually
    # fractured, so it is the authoritative number the moment one exists.
    manifest_mass = _manifest_mass(directory)
    if manifest_mass is not None:
        produced.mass_override_kg = manifest_mass
    del summary
    return produced


def _manifest_mass(directory: Path) -> float | None:
    import json

    path = directory / "fracture_manifest.json"
    if not path.is_file():
        return None
    try:
        return float(json.loads(path.read_text(encoding="utf-8"))["partMassKg"])
    except (OSError, ValueError, KeyError, TypeError):
        return None


def write_documents(documents: dict[Path, dict]) -> list[str]:
    """Write every ``part.json`` and ``assembly.json``, sorted, indented, newline-terminated.

    Sorted keys and a fixed indent because these files are committed content and a diff
    between two runs of the pipeline must show what changed about the *vehicle*, not what
    changed about the dictionary ordering (D15-R30's determinism, in the place it is easiest
    to lose).
    """
    import json

    written = []
    for path, document in sorted(documents.items(), key=lambda item: str(item[0])):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        written.append(str(path))
    return written


def solidify(obj, thickness_m: float) -> bool:
    """Give an open shell a wall, so it has a volume to fracture and to weigh.

    A windscreen, a door skin and a bonnet are all authored as surfaces with no thickness,
    and every one of the three operations that follow needs a solid: a Voronoi cell
    intersected with a surface is empty, ``volume x density`` over a surface is zero, and a
    convex hull over a surface is a sliver. The thickness is the destruction class's own wall
    thickness, which is what makes the solid's ``volume x density`` agree with the mass the
    manifest computed from ``area x thickness x density`` rather than merely resemble it.
    """
    modifier = obj.modifiers.new(name="solidify", type="SOLIDIFY")
    modifier.thickness = thickness_m
    modifier.offset = 0.0
    bpy.context.view_layer.objects.active = obj
    try:
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    except RuntimeError:  # pragma: no cover - a degenerate mesh can refuse
        obj.modifiers.remove(modifier)
        return False
    return True
