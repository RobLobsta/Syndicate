"""Generates the canonical fixture meshes of D14-S7.1.

The fixtures are checked into ``fixtures/meshes/`` as ``.glb``, but they are *generated*
rather than hand-modelled: D14-R21 requires each to be watertight, single-material,
authored at 1 unit = 1 m, with a documented origin and no loose geometry. A generator
satisfies all of that by construction, and — more importantly — makes the analytic expected
values of D14-R22 derivable rather than measured, so ``test_cube_1m`` can validate the
manifest generator against closed-form truth instead of against itself.

Run as::

    python3 -m syndicate_fracture.fixtures fixtures/meshes
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

from . import blender
from .blender import require_bpy
from .exporter import GLTF_SETTINGS
from .geometry import Vec3

# Blender authors Z-up (D00-R16), so a fixture's documented "origin at the centre of the
# base face" means the base sits at z = 0 here and at y = 0 after export.


def cube_1m() -> tuple[list[Vec3], list[list[int]]]:
    """Solid 1 m cube, origin at the centre of the base face (D14-S7.1)."""
    h = 0.5
    vertices: list[Vec3] = [
        (-h, -h, 0.0),
        (h, -h, 0.0),
        (h, h, 0.0),
        (-h, h, 0.0),
        (-h, -h, 1.0),
        (h, -h, 1.0),
        (h, h, 1.0),
        (-h, h, 1.0),
    ]
    faces = [
        [0, 3, 2, 1],  # base, winding outward (downward)
        [4, 5, 6, 7],  # top
        [0, 1, 5, 4],
        [1, 2, 6, 5],
        [2, 3, 7, 6],
        [3, 0, 4, 7],
    ]
    return vertices, faces


def plate_2x1x0_1() -> tuple[list[Vec3], list[list[int]]]:
    """Thin armour plate, 2 x 1 x 0.1 m, centred on its own volume."""
    return _box(2.0, 1.0, 0.1, base_at_zero=False)


def cylinder_r0_5_h1(segments: int = 48) -> tuple[list[Vec3], list[list[int]]]:
    """Cylinder r = 0.5 m, h = 1 m, origin at the centre of the base face."""
    radius, height = 0.5, 1.0
    vertices: list[Vec3] = []
    for ring_z in (0.0, height):
        for i in range(segments):
            angle = 2.0 * math.pi * i / segments
            vertices.append((radius * math.cos(angle), radius * math.sin(angle), ring_z))
    bottom_center = len(vertices)
    vertices.append((0.0, 0.0, 0.0))
    top_center = len(vertices)
    vertices.append((0.0, 0.0, height))

    faces: list[list[int]] = []
    for i in range(segments):
        j = (i + 1) % segments
        faces.append([i, j, j + segments, i + segments])  # side quad
        faces.append([bottom_center, j, i])  # base fan, wound outward (downward)
        faces.append([top_center, i + segments, j + segments])
    return vertices, faces


def complex_hollow(wall: float = 0.05) -> tuple[list[Vec3], list[list[int]]]:
    """Hollow 1 m box with 0.05 m walls, origin at the centre of the base face.

    Built as an outer shell plus an inward-wound inner shell, which is what makes the
    signed-volume computation genuinely tested: a bounding-box or convex-hull volume would
    report 1 m3 instead of the ~0.27 m3 the walls actually occupy (D14-S7.1).
    """
    outer_v, outer_f = _box(1.0, 1.0, 1.0, base_at_zero=True)
    inner_v, inner_f = _box(1.0 - 2 * wall, 1.0 - 2 * wall, 1.0 - 2 * wall, base_at_zero=True)
    inner_v = [(v[0], v[1], v[2] + wall) for v in inner_v]

    offset = len(outer_v)
    # Reversed winding: the cavity's normals point into the material, so the divergence
    # theorem subtracts the cavity rather than adding it.
    flipped = [list(reversed([i + offset for i in face])) for face in inner_f]
    return outer_v + inner_v, outer_f + flipped


FIXTURES = {
    "test_cube_1m": (cube_1m, "steel", 1001),
    "test_plate_2x1x0.1": (plate_2x1x0_1, "steel_hardened", 1002),
    "test_cylinder_r0.5_h1": (cylinder_r0_5_h1, "aluminium", 1003),
    "test_complex_hollow": (complex_hollow, "steel", 1004),
}


def write_all(out_dir: Path) -> list[Path]:
    """Generate every fixture as a ``.glb`` in ``out_dir``."""
    require_bpy()
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for name, (builder, material, _seed) in sorted(FIXTURES.items()):
        blender.reset_scene()
        vertices, faces = builder()
        obj = blender.new_mesh_object(name, vertices, faces)
        material_data = blender.bpy.data.materials.new(name=material)
        obj.data.materials.append(material_data)

        path = out_dir / f"{name}.glb"
        blender.select_only([obj])
        blender.bpy.ops.export_scene.gltf(filepath=str(path), use_selection=True, **GLTF_SETTINGS)
        written.append(path)
        print(f"wrote {path}", file=sys.stderr)
    return written


def _box(sx: float, sy: float, sz: float, base_at_zero: bool) -> tuple[list[Vec3], list[list[int]]]:
    hx, hy = sx / 2.0, sy / 2.0
    lo = 0.0 if base_at_zero else -sz / 2.0
    hi = sz if base_at_zero else sz / 2.0
    vertices: list[Vec3] = [
        (-hx, -hy, lo),
        (hx, -hy, lo),
        (hx, hy, lo),
        (-hx, hy, lo),
        (-hx, -hy, hi),
        (hx, -hy, hi),
        (hx, hy, hi),
        (-hx, hy, hi),
    ]
    faces = [
        [0, 3, 2, 1],
        [4, 5, 6, 7],
        [0, 1, 5, 4],
        [1, 2, 6, 5],
        [2, 3, 7, 6],
        [3, 0, 4, 7],
    ]
    return vertices, faces


if __name__ == "__main__":
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("fixtures/meshes")
    write_all(target)
