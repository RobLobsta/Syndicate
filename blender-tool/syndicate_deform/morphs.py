"""Damage shape key generation — the DEFORM transform (D09-S5.3, D15-S5.7).

Produces N morph targets on the *intact* mesh representing progressive damage. Shards never
carry morphs (D09-R12): a shard is already the fully-broken representation, so morphing
debris would cost memory for nothing visible.

Displacement is inward only (D09-R13). An outward dent would make a damaged part occupy more
space than its collision hull, and since collision geometry never deforms (D06-NG5) that
shows up as a visible interpenetration the physics cannot explain.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from syndicate_fracture import blender
from syndicate_fracture.blender import require_bpy
from syndicate_fracture.errors import EXIT_SHAPEKEY_FAILED, ToolError
from syndicate_fracture.geometry import (
    Tri,
    Vec3,
    aabb_of,
    add,
    distance,
    is_finite,
    length,
    normalize,
    scale,
    sub,
    surface_area,
    triangle_area,
    vertex_normals,
)
from syndicate_fracture.rng import Pcg32, mix, stable_hash
from syndicate_policy.classes import MORPH_LEVELS

# D14-S6.4 tolerances the morphs must satisfy by construction, so ASSET-008/009 pass in the
# harness rather than being discovered broken there.
MORPH_MIN_DELTA_M = 0.005
MIN_FACE_AREA_M2 = 1e-8
CRUMPLE_FREQ = 18.0

#: Re-exported from the shared policy so the four names have one definition in the suite.
LEVEL_NAMES = MORPH_LEVELS


@dataclass
class MorphStats:
    """One morph's summary, recorded in the manifest as ``morphStats`` (D09-R7)."""

    name: str
    mean_displacement_m: float
    max_displacement_m: float

    def to_json(self) -> dict[str, float | str]:
        return {
            "name": self.name,
            "meanDisplacementM": round(self.mean_displacement_m, 6),
            "maxDisplacementM": round(self.max_displacement_m, 6),
        }


@dataclass
class _Dent:
    center: Vec3
    radius: float
    weight: float
    direction: Vec3


def _remove_existing_damage_keys(obj, mesh) -> None:
    """Drop any ``dmg_*`` shape keys already on the mesh, so this run replaces them.

    ``shape_key_add`` does not overwrite: handed a name that is already taken it appends
    ``.001``, and the mesh exports carrying eight morph targets where the manifest promises
    four. Self-verification catches that (TV-006) but blames the exporter, which is three
    stages from the cause.

    A source arrives already carrying these keys whenever the part has been through
    ``syndicate_prepare`` — every door on both shipped cars dents, so every door has
    ``dmg_25``..``dmg_100`` before the fracture ever sees it (DISC-065). The tool owns those
    names, so re-authoring them is the intent and removing them first is what makes the run
    idempotent.

    Only the levels this tool generates are removed. A key authored elsewhere under another
    name is somebody else's and is left alone.
    """
    owned = set(LEVEL_NAMES)
    for key_block in list(mesh.shape_keys.key_blocks):
        if key_block.name in owned:
            obj.shape_key_remove(key_block)


def generate_damage_morphs(
    obj, *, levels: int = 4, amplitude: float = 0.06, seed: int = 1337
) -> list[MorphStats]:
    """Add the damage shape keys to ``obj`` and return their statistics (D09-S5.3).

    Takes the three numbers it needs rather than a whole ``Args``. It used to take the fracture
    tool's, which is how the two transforms came to share a command line and then a pipeline
    (DISC-068); nothing about denting a panel needs to know a shard count.
    """
    require_bpy()
    if levels == 0:
        return []

    mesh = obj.data
    if mesh.shape_keys is None:
        obj.shape_key_add(name="Basis", from_mix=False)
    else:
        _remove_existing_damage_keys(obj, mesh)

    basis: list[Vec3] = [(v.co.x, v.co.y, v.co.z) for v in mesh.vertices]
    triangles = _triangles_of(mesh)
    normals = vertex_normals(basis, triangles)
    bounds = aabb_of(basis)
    bounding_radius = 0.5 * length(bounds.extent)
    sharp = _sharp_vertices(mesh)

    rng = Pcg32(seed=mix(seed, stable_hash(obj.name), stable_hash("morph")))
    dents = _place_dents(basis, triangles, normals, bounding_radius, rng)

    level_names = LEVEL_NAMES[:levels]
    stats: list[MorphStats] = []

    for level_index, name in enumerate(level_names):
        severity = (level_index + 1) / len(level_names)
        key = obj.shape_key_add(name=name, from_mix=False)

        max_disp = 0.0
        sum_disp = 0.0
        for vi, base in enumerate(basis):
            displacement = (0.0, 0.0, 0.0)

            # (a) Smooth dents, reused across levels at increasing depth so the four morphs
            #     read as one progression rather than four unrelated shapes.
            for dent in dents:
                dist = distance(base, dent.center)
                if dist < dent.radius:
                    falloff = _smoothstep(1.0 - dist / dent.radius)
                    depth = amplitude * severity * dent.weight * falloff
                    displacement = add(displacement, scale(dent.direction, depth))

            # (b) High-frequency crumple, seeded per vertex index so it is identical
            #     regardless of iteration order or Blender version (G11).
            crumple = _value_noise(base, mix(seed, vi))
            displacement = add(
                displacement,
                scale(normals[vi], -abs(crumple) * amplitude * 0.25 * severity),
            )

            # (c) Edge preservation: silhouette vertices move less, so the part stays
            #     recognisable at every damage level (P1).
            if vi in sharp:
                displacement = scale(displacement, 0.4)

            new_position = add(base, displacement)
            if not is_finite(new_position):
                raise ToolError(
                    EXIT_SHAPEKEY_FAILED,
                    f"morph '{name}' produced a non-finite vertex position",
                    morph=name,
                    vertex=vi,
                )
            key.data[vi].co = new_position
            magnitude = length(displacement)
            max_disp = max(max_disp, magnitude)
            sum_disp += magnitude

        mean_disp = sum_disp / max(len(basis), 1)
        _guard_morph(name, level_index, mean_disp, max_disp, stats)
        _guard_face_areas(name, basis, triangles, key)
        stats.append(
            MorphStats(name=name, mean_displacement_m=mean_disp, max_displacement_m=max_disp)
        )

    # Leave the mesh at its undamaged shape: a non-zero weight here would be baked into the
    # exported base mesh, shipping a part that is already dented at full health.
    for key_block in mesh.shape_keys.key_blocks:
        key_block.value = 0.0
    return stats


def morph_names(stats: list[MorphStats]) -> list[str]:
    return [s.name for s in stats]


# --- Internals -------------------------------------------------------------------------


def _guard_morph(
    name: str, level_index: int, mean_disp: float, max_disp: float, previous: list[MorphStats]
) -> None:
    """The guards that make ASSET-008/009 pass by construction rather than by luck."""
    if max_disp < MORPH_MIN_DELTA_M:
        raise ToolError(
            EXIT_SHAPEKEY_FAILED,
            f"morph '{name}' is degenerate: max displacement {max_disp:.6f} m",
            morph=name,
            maxDisplacementM=max_disp,
            minimum=MORPH_MIN_DELTA_M,
        )
    if level_index > 0 and mean_disp <= previous[-1].mean_displacement_m:
        raise ToolError(
            EXIT_SHAPEKEY_FAILED,
            f"morph severity is not monotonic at '{name}'",
            morph=name,
            meanDisplacementM=mean_disp,
            previous=previous[-1].mean_displacement_m,
        )


def _guard_face_areas(name: str, basis: list[Vec3], triangles: list[Tri], key) -> None:
    """No face may collapse at full weight — a zero-area face renders as a black sliver."""
    displaced = [(p.co.x, p.co.y, p.co.z) for p in key.data]
    for i, j, k in triangles:
        if triangle_area(displaced[i], displaced[j], displaced[k]) < MIN_FACE_AREA_M2:
            raise ToolError(
                EXIT_SHAPEKEY_FAILED,
                f"morph '{name}' produces a zero-area face at weight 1.0",
                morph=name,
                face=[i, j, k],
            )


def _place_dents(
    basis: list[Vec3],
    triangles: list[Tri],
    normals: list[Vec3],
    bounding_radius: float,
    rng: Pcg32,
) -> list[_Dent]:
    """Dent centres on the surface, placed once and reused at every severity."""
    area = surface_area(basis, triangles)
    count = max(4, min(16, round(4 + 8 * area)))
    dents: list[_Dent] = []
    for _ in range(count):
        vertex = rng.next_int(len(basis))
        center = basis[vertex]
        dents.append(
            _Dent(
                center=center,
                radius=max(rng.uniform(0.15, 0.45) * bounding_radius, 1e-4),
                weight=rng.uniform(0.5, 1.0),
                direction=scale(normalize(normals[vertex]), -1.0),  # inward (D09-R13)
            )
        )
    return dents


def _sharp_vertices(mesh) -> set[int]:
    """Vertices on a boundary or a sharp edge, which move at 40% (D09-S5.3 step c)."""
    sharp: set[int] = set()
    for edge in mesh.edges:
        if edge.use_edge_sharp:
            sharp.update(edge.vertices)
    # Boundary edges: used by exactly one face.
    usage: dict[tuple[int, int], int] = {}
    for polygon in mesh.polygons:
        keys = list(polygon.vertices)
        for a, b in zip(keys, keys[1:] + keys[:1], strict=False):
            key = (min(a, b), max(a, b))
            usage[key] = usage.get(key, 0) + 1
    for (a, b), count in usage.items():
        if count == 1:
            sharp.add(a)
            sharp.add(b)
    return sharp


def _triangles_of(mesh) -> list[Tri]:
    mesh.calc_loop_triangles()
    return [tuple(t.vertices) for t in mesh.loop_triangles]  # type: ignore[misc]


def _smoothstep(t: float) -> float:
    t = min(max(t, 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def _value_noise(point: Vec3, seed: int) -> float:
    """Deterministic value noise in ``[-1, 1]``.

    Seeded by ``(seed, position)`` rather than by a lattice lookup, so the result is
    identical on every platform and Blender version — a stdlib or library noise function
    would be free to change its algorithm between releases and silently alter every morph.
    """
    scaled = scale(point, CRUMPLE_FREQ)
    acc = mix(
        seed,
        math.floor(scaled[0] * 1024.0) & 0xFFFFFFFF,
        math.floor(scaled[1] * 1024.0) & 0xFFFFFFFF,
        math.floor(scaled[2] * 1024.0) & 0xFFFFFFFF,
    )
    return ((acc >> 11) & 0xFFFFF) / float(0xFFFFF) * 2.0 - 1.0


__all__ = ["MorphStats", "blender", "generate_damage_morphs", "morph_names", "sub"]
