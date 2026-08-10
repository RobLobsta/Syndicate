"""Cut a whole-vehicle model into a chassis and four wheels.

The algorithm is connected-component analysis plus spatial classification, and it works
because of a fact about how vehicles are modelled: a wheel never shares vertices with the
sheet metal around it. Tyre, rim, disc and caliper are separate closed shells sitting in a
wheel arch, so "which triangles are the wheel" is a geometry question rather than a naming
one — which matters, because the two models this project ships name their parts
``Object_170`` and ``polySurface766_F:Ford...`` respectively, and neither naming survives
being relied on.

Five stages, in this order for reasons that are not interchangeable:

1. **Pose.** Skinned meshes are baked through their armature first. This is not a
   formality — see :func:`bake_armature_poses`, and DISC-016 for what reading the node
   transform instead does to this particular pair of models.
2. **Correct.** The ``import.json`` correction (DEC-036) is applied so every measurement
   from here on is in the game's metres and axes (D00-R16). Classification thresholds are
   then real quantities — "a wheel is under 0.6 m tall" — rather than numbers tuned per
   model.
3. **Separate.** Loose parts, but only for objects that actually straddle a wheel boundary.
   Blender's ``separate(type='LOOSE')`` on a 15,000-triangle mesh is seconds, and on all
   171 of them it is minutes for no benefit.
4. **Classify.** Wheel-shaped, outboard and low → one of four corners; everything else →
   chassis.
5. **Emit.** Origins centred on each wheel's axle so it rotates about the right point, and
   one ``.glb`` per part with a ``_col`` collision node beside the visual mesh (D08-R3).
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

try:  # pragma: no cover - exercised only inside a Blender host
    import bpy  # isort: skip
    import bmesh  # isort: skip
    from mathutils import Matrix, Vector  # isort: skip

    HAVE_BPY = True
except ImportError:  # pragma: no cover - the pure-Python unit test path
    bpy = None  # type: ignore[assignment]
    bmesh = None  # type: ignore[assignment]
    Matrix = Vector = None  # type: ignore[assignment]
    HAVE_BPY = False


# ---- Classification thresholds, in game metres ---------------------------------------

#: A wheel's centre is at least this far off the centreline. Half the narrowest plausible
#: track; nothing structural on a car's centreline can be mistaken for one.
MIN_WHEEL_OFFSET_M = 0.45

#: A wheel's centre sits below this height. A 0.7 m tyre has its axle at 0.35 m, and even a
#: 1.2 m truck tyre puts it at 0.60. This was 0.80 and admitted the Eclipse's wing mirror,
#: which sits at 0.746, is outboard, and is round enough from the side to pass the shape
#: test — it dragged the front axle 0.30 m rearward and its reported diameter to 1.31 m.
MAX_WHEEL_CENTRE_Y_M = 0.65

#: Largest wheel diameter accepted. Above this it is a body panel that happens to be round.
MAX_WHEEL_DIAMETER_M = 1.20

#: Widest wheel accepted, measured across the axle.
MAX_WHEEL_WIDTH_M = 0.75

#: A wheel seen from the side is a disc: its height and its length agree to within this
#: fraction. This is the single most discriminating test — it rejects wishbones, driveshafts
#: and sill panels, all of which are low and outboard and none of which is round.
#:
#: A real tyre agrees to well under 1%; the slack is for the rim and hub faces, which are
#: round but sit inside a slightly taller bounding box. 0.45 was loose enough to admit a
#: wing mirror at 0.34, so the measured pieces set the bar rather than the intuition.
WHEEL_ROUNDNESS_TOLERANCE = 0.25

#: How far outside a wheel's own radius a piece may sit and still be counted part of it.
#: Covers the caliper and the hub face, which stick out past the tyre's silhouette.
WHEEL_CAPTURE_MARGIN = 1.12

#: Degrees of the circle a piece must occupy, about the axle, to be treated as *rotating*
#: with the wheel rather than merely sitting inside it.
#:
#: This is a physical test standing in for a semantic one. A part that turns with a wheel has
#: to be rotationally symmetric about the axle, or it would sweep through the bodywork once a
#: revolution — so tyre, rim, hub and brake disc all occupy the full circle. A brake caliper
#: does not: it is a block clamped over one sector of the disc, and it stays put while the
#: disc spins inside it. Measured over both shipped cars a caliper occupies 90-150 degrees and every
#: rotating piece occupies 360°, so the bar sits far from either.
#:
#: Named parts would answer this on the Stampede (``…CallipersCalliperA_Zone…``) and not on
#: the Eclipse (``calliperzone_d`` yes, but its rim is ``material_51`` and ``oyctp``). The
#: geometry answers it on both.
ROTATION_SYMMETRY_MIN_DEG = 300.0

#: Sectors the circle is divided into when measuring that coverage. 24 gives 15° resolution,
#: which is finer than the gap between a caliper's 150° and a rim's 360° by a wide margin.
ROTATION_SECTORS = 24

#: Vertices nearer the axle than this contribute no bearing — the hub face's centre sits on
#: the axis, where the angle is undefined and numerically wild.
ROTATION_MIN_RADIUS_M = 0.02


@dataclass
class Island:
    """One connected piece of geometry, measured in game space."""

    obj: object
    lo: object
    hi: object
    triangles: int

    @property
    def centre(self):
        return (self.lo + self.hi) / 2

    @property
    def size(self):
        return self.hi - self.lo


@dataclass
class WheelGroup:
    """The islands making up one corner of the vehicle.

    The wheel's *geometry* — where its axle is, how big the tyre is — comes from the round
    islands that seeded it and is frozen before anything else is captured. That separation
    is the whole reason this class holds two lists. A brake caliper legitimately sticks out
    past the tyre's silhouette, so measuring the wheel from everything that turns with it
    reports a tyre half again too big, and every downstream number — the slot position, the
    suspension rest length, the wheel radius Bullet is given — inherits the error.
    """

    corner: str
    seeds: list = field(default_factory=list)
    furniture: list = field(default_factory=list)
    static: list = field(default_factory=list)

    @property
    def islands(self) -> list:
        """Everything that rotates with this wheel, and therefore leaves with it."""
        return self.seeds + self.furniture

    def _bounds(self, islands):
        lo = Vector((
            min(i.lo.x for i in islands),
            min(i.lo.y for i in islands),
            min(i.lo.z for i in islands),
        ))
        hi = Vector((
            max(i.hi.x for i in islands),
            max(i.hi.y for i in islands),
            max(i.hi.z for i in islands),
        ))
        return lo, hi

    def bounds(self):
        """The extent of everything in the group, which is what the exported mesh spans."""
        return self._bounds(self.islands)

    def centre(self):
        """The axle: the centre of the round parts, not of everything bolted to them."""
        lo, hi = self._bounds(self.seeds)
        return (lo + hi) / 2

    def radius(self):
        """The tyre's radius, from the round parts alone."""
        lo, hi = self._bounds(self.seeds)
        return max(hi.y - lo.y, hi.z - lo.z) / 2

    def width(self):
        """The tyre's width across the axle, from the round parts alone."""
        lo, hi = self._bounds(self.seeds)
        return hi.x - lo.x


# ---- Stage 1: load and pose -----------------------------------------------------------


def load_model(model_dir: Path):
    """Imports the glTF, drops everything that is not the vehicle, and poses the result."""
    bpy.ops.wm.read_factory_settings(use_empty=True)
    gltf = model_dir / "scene.gltf"
    if not gltf.is_file():
        gltf = model_dir / "scene.glb"
    bpy.ops.import_scene.gltf(filepath=str(gltf))
    drop_foreign_roots()
    bake_armature_poses()
    apply_import_correction(model_dir)


def drop_foreign_roots() -> list[str]:
    """Deletes every root subtree except the one holding the most geometry.

    A downloaded scene is not only the vehicle. The Eclipse arrives with two one-metre
    icospheres — a lighting rig the author left in — which are legitimate mesh objects and
    would otherwise be welded into the chassis, dragging its bounding box out by a metre on
    each side and its collision hull with it.
    """
    counts: dict[str, int] = {}
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH" or not len(obj.data.vertices):
            continue
        counts[_root_of(obj).name] = counts.get(_root_of(obj).name, 0) + 1
    if not counts:
        return []
    keep = max(counts, key=counts.get)
    dropped = []
    for obj in list(bpy.context.scene.objects):
        if _root_of(obj).name != keep:
            dropped.append(obj.name)
            bpy.data.objects.remove(obj, do_unlink=True)
    return dropped


def bake_armature_poses() -> int:
    """Replaces each skinned mesh with its posed geometry, then drops the modifier.

    **This is the stage that makes the rest of the tool correct**, and it is not obvious
    that it is needed: these cars do not animate, so it is tempting to assume the armature
    is inert and a mesh sits where its node transform says.

    For most objects that is true. For ten of the Eclipse's, it is wrong by up to 2.65 m —
    the whole rear-left corner is authored as a mirrored duplicate whose placement lives in
    the joint matrices, not in the node transform. Read the node transform and the car has
    three wheels and a spare floating over the bonnet (DISC-016).

    :return: how many objects were baked
    """
    deps = bpy.context.evaluated_depsgraph_get()
    baked = 0
    for obj in [o for o in bpy.context.scene.objects if o.type == "MESH"]:
        if not any(m.type == "ARMATURE" for m in obj.modifiers):
            continue
        evaluated = obj.evaluated_get(deps)
        posed = bpy.data.meshes.new_from_object(evaluated)
        obj.modifiers.clear()
        obj.data = posed
        baked += 1
    # The armatures themselves are now inert; leaving them in confuses the export selection.
    for obj in [o for o in bpy.context.scene.objects if o.type == "ARMATURE"]:
        bpy.data.objects.remove(obj, do_unlink=True)
    return baked


def apply_import_correction(model_dir: Path) -> dict:
    """Bakes ``import.json`` into the scene so world space is game space (DEC-036).

    The correction is authored in the game's frame (Y up) and applied here in Blender's
    (Z up), so the translation's Y and Z swap and the yaw is about Blender's Z. Getting
    that wrong is invisible on a symmetric model and obvious on nothing else, which is why
    it is written out once here rather than inlined at each call site.
    """
    correction_file = model_dir / "import.json"
    correction = json.loads(correction_file.read_text()) if correction_file.is_file() else {}
    scale = float(correction.get("scaleToMetres", 1.0))
    yaw = math.radians(float(correction.get("yawDeg", 0.0)))
    t = correction.get("translationM", {})
    tx, ty, tz = float(t.get("x", 0.0)), float(t.get("y", 0.0)), float(t.get("z", 0.0))

    # game (x, y, z) is blender (x, -z, y): the translation's game Y becomes Blender's Z,
    # and its game Z becomes Blender's negated Y.
    transform = (
        Matrix.Translation(Vector((tx, -tz, ty)))
        @ Matrix.Rotation(yaw, 4, "Z")
        @ Matrix.Scale(scale, 4)
    )
    for obj in bpy.context.scene.objects:
        if obj.parent is None:
            obj.matrix_world = transform @ obj.matrix_world
    bpy.context.view_layer.update()
    return {
        "scaleToMetres": scale,
        "yawDeg": math.degrees(yaw),
        "translationM": {"x": tx, "y": ty, "z": tz},
    }


# ---- Stage 3: islands ------------------------------------------------------------------


def measure(obj) -> Island:
    """One object's bounds in game space, from its vertices rather than its bounding box."""
    mw = obj.matrix_world
    lo = Vector((1e9, 1e9, 1e9))
    hi = Vector((-1e9, -1e9, -1e9))
    for v in obj.data.vertices:
        g = _to_game(mw @ v.co)
        lo = Vector((min(lo.x, g.x), min(lo.y, g.y), min(lo.z, g.z)))
        hi = Vector((max(hi.x, g.x), max(hi.y, g.y), max(hi.z, g.z)))
    return Island(obj=obj, lo=lo, hi=hi, triangles=len(obj.data.polygons))


def collect_islands() -> list[Island]:
    """Every mesh object, measured. Loose separation happens later and only where needed."""
    return [
        measure(o)
        for o in bpy.context.scene.objects
        if o.type == "MESH" and len(o.data.vertices) > 0
    ]


def separate_loose(island: Island) -> list[Island]:
    """Splits one object into its connected components (the user-facing 'loose parts')."""
    obj = island.obj
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    before = set(bpy.context.scene.objects)
    bpy.ops.mesh.separate(type="LOOSE") if bpy.context.mode == "EDIT_MESH" else None
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.separate(type="LOOSE")
    bpy.ops.object.mode_set(mode="OBJECT")
    produced = [o for o in bpy.context.scene.objects if o not in before] + [obj]
    return [measure(o) for o in produced if o.type == "MESH" and len(o.data.vertices) > 0]


# ---- Stage 4: classify -----------------------------------------------------------------


def is_wheel_shaped(island: Island) -> bool:
    """Whether an island looks like part of a road wheel.

    Four tests, and the last one carries the weight. Outboard and low are necessary and
    nowhere near sufficient — a sill, a wishbone and an exhaust are all outboard and low.
    Roundness is what separates them: seen from the side a wheel is a disc, so its vertical
    and longitudinal extents agree, and nothing else on a car does that at this height.
    """
    c, s = island.centre, island.size
    if abs(c.x) < MIN_WHEEL_OFFSET_M or c.y > MAX_WHEEL_CENTRE_Y_M:
        return False
    if s.x > MAX_WHEEL_WIDTH_M or max(s.y, s.z) > MAX_WHEEL_DIAMETER_M:
        return False
    if min(s.y, s.z) < 0.05:
        return False
    return abs(s.y - s.z) <= WHEEL_ROUNDNESS_TOLERANCE * max(s.y, s.z)


def seed_wheels(islands: list[Island]) -> dict[str, WheelGroup]:
    """Finds the four corners from the wheel-shaped islands alone.

    Front and rear are split at the midpoint of the wheel-shaped islands' own longitudinal
    spread rather than at the vehicle's centre. A mid-engined car's wheels are not
    symmetric about its bodywork, and a bumper overhang would otherwise push the divide
    past an axle.
    """
    seeds = [i for i in islands if is_wheel_shaped(i)]
    if not seeds:
        return {}
    zs = sorted(i.centre.z for i in seeds)
    zmid = (zs[0] + zs[-1]) / 2
    groups: dict[str, WheelGroup] = {}
    for island in seeds:
        corner = ("f" if island.centre.z > zmid else "r") + ("l" if island.centre.x < 0 else "r")
        groups.setdefault(corner, WheelGroup(corner)).seeds.append(island)
    return groups


def capture_wheel_furniture(islands: list[Island], groups: dict[str, WheelGroup]) -> None:
    """Adds the brake disc and hub to the wheel, and sets the caliper aside.

    Two gates, and the second one is the point. Containment finds everything living inside a
    wheel's cylinder — disc, hub, caliper, wheel bolts — because none of those passes the
    roundness test on its own and all of them are part of the corner. Rotational symmetry then
    decides which of them actually *turns*: a disc does and a caliper does not, and a caliper
    exported inside the wheel spins with it, which is visible in any capture and wrong in a way
    no measurement of the wheel would report (:data:`ROTATION_SYMMETRY_MIN_DEG`).

    What is set aside lands in :attr:`WheelGroup.static` and goes back to the chassis. That is
    an approximation and a documented one: a caliper is unsprung, so it should rise and fall
    with the wheel and only decline to rotate. Leaving it on the chassis costs a few
    centimetres of travel it will not follow and buys a caliper that stays where a caliper
    stays. The exact answer is a non-rotating hub part at the axle, which needs a slot type
    the part schema does not yet have.
    """
    claimed = {id(i.obj) for g in groups.values() for i in g.islands}
    # Every cylinder is measured before any of them captures anything, so what one wheel
    # takes cannot enlarge another wheel's catchment on the next iteration.
    cylinders = {
        corner: (
            group.centre(),
            group.radius() * WHEEL_CAPTURE_MARGIN,
            group.width() / 2 * WHEEL_CAPTURE_MARGIN,
        )
        for corner, group in groups.items()
    }
    for corner, group in groups.items():
        centre, radius, half_width = cylinders[corner]
        for island in islands:
            if id(island.obj) in claimed:
                continue
            # Containment, not proximity. Every corner of the island's box has to be inside
            # the cylinder: a wishbone reaching into the arch has its centroid next to the
            # hub and its far end bolted to the chassis, and capturing it would take the
            # subframe with the wheel.
            if not _inside_cylinder(island, centre, radius, half_width):
                continue
            if angular_coverage_deg(island, centre) >= ROTATION_SYMMETRY_MIN_DEG:
                group.furniture.append(island)
            else:
                group.static.append(island)
            claimed.add(id(island.obj))


def prune_non_rotating_seeds(groups: dict[str, WheelGroup]) -> int:
    """Moves seeds that turn out not to be rotationally symmetric into ``static``.

    :func:`is_wheel_shaped` asks whether an island's silhouette is *square*, which a disc is
    and so is a caliper bolt: on the Stampede eight caliper fragments of one to forty triangles
    seeded the front-right corner alongside the 4,516-triangle rim. They are far too small to
    move the axle the group's geometry is measured from, which is what makes a second pass
    safe — the provisional axle is the rim's, and re-measuring against it is enough.

    :return: how many seeds were reclassified
    """
    moved = 0
    for group in groups.values():
        if not group.seeds:
            continue
        axle = group.centre()
        keep, reject = [], []
        for island in group.seeds:
            rotates = angular_coverage_deg(island, axle) >= ROTATION_SYMMETRY_MIN_DEG
            (keep if rotates else reject).append(island)
        # Never strip a corner bare: with nothing kept there is no axle and no wheel, and the
        # honest failure is the one the report already knows how to describe.
        if keep:
            group.seeds = keep
            group.static.extend(reject)
            moved += len(reject)
    return moved


def angular_coverage_deg(island: Island, axle) -> float:
    """How much of the circle about ``axle`` this island's geometry occupies, in degrees.

    Measured from vertices rather than from the bounding box. A rim is spokes with gaps
    between them, and its box corners land in four sectors however many spokes it has — the
    box would report a five-spoke wheel and a caliper as equally partial.

    ``axle`` is a point in game space; bearings are taken in the YZ plane, which is the plane
    a wheel turns in (the axle runs along X, D06-S5.5).
    """
    occupied = set()
    mw = island.obj.matrix_world
    for v in island.obj.data.vertices:
        g = _to_game(mw @ v.co)
        dy, dz = g.y - axle.y, g.z - axle.z
        if math.hypot(dy, dz) < ROTATION_MIN_RADIUS_M:
            continue
        bearing = math.degrees(math.atan2(dz, dy)) % 360.0
        occupied.add(int(bearing // (360 / ROTATION_SECTORS)))
    return len(occupied) * (360.0 / ROTATION_SECTORS)


def classify(islands: list[Island]) -> tuple[dict[str, WheelGroup], list[Island]]:
    """Splits every island into four wheel groups and the chassis remainder.

    Anything captured into a corner but found not to rotate — the caliper — is returned to the
    chassis rather than dropped, so no triangle of the source model goes missing.
    """
    groups = seed_wheels(islands)
    prune_non_rotating_seeds(groups)
    capture_wheel_furniture(islands, groups)
    rotating = {id(i.obj) for g in groups.values() for i in g.islands}
    chassis = [i for i in islands if id(i.obj) not in rotating]
    return groups, chassis


# ---- Helpers ---------------------------------------------------------------------------


def _inside_cylinder(island: Island, centre, radius: float, half_width: float) -> bool:
    """Whether an island's whole bounding box lies inside a wheel's cylinder."""
    if island.lo.x < centre.x - half_width or island.hi.x > centre.x + half_width:
        return False
    for y in (island.lo.y, island.hi.y):
        for z in (island.lo.z, island.hi.z):
            if math.hypot(y - centre.y, z - centre.z) > radius:
                return False
    return True


def _root_of(obj):
    while obj.parent is not None:
        obj = obj.parent
    return obj


def _to_game(v):
    """Blender world (Z up, +Y forward) to game (Y up, -Z forward) — D00-R16."""
    return Vector((v.x, v.z, -v.y))
