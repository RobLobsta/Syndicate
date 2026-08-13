"""A synthetic vehicle, measured the way the Blender half of the pipeline measures one.

Shared by the preparation tests so there is exactly one description of "a pickup" to keep
correct. It is deliberately made of the pieces a real downloaded model has and no others: a
painted body, an unlabelled bit of trim, two door skins, a bonnet, a windscreen, two headlamps,
an interior, a badge, and four corners of tyre-plus-caliper.
"""

from __future__ import annotations

import math
from pathlib import Path

from syndicate_prepare import cues, grouping, roles
from syndicate_prepare.labels import (
    CHASSIS,
    DECAL,
    GLASS,
    INTERIOR,
    LIGHT,
    PANEL,
    UNCLASSIFIED,
    WHEEL,
)
from syndicate_prepare.shell import Shell

#: The repository's own content tables. The tool reads the same files the game does (D09-R18),
#: so the tests do too rather than carrying a second copy of the densities.
ASSETS = Path(__file__).resolve().parents[3] / "assets"


def shell(index, lo, hi, label, material=None, triangles=900, area=1.0, role=None, vertices=()):
    made = Shell(
        index=index,
        name=f"obj_{index}",
        material=material,
        triangles=triangles,
        lo=lo,
        hi=hi,
        centroid=tuple((lo[i] + hi[i]) * 0.5 for i in range(3)),
    )
    made.label = label
    made.role = role
    made.area_m2 = area
    made.vertex_sample = tuple(vertices)
    return made


def ring(x, axle_y, axle_z, radius, sectors=36):
    """Vertices all the way round an axle — what a tyre's sample looks like."""
    return [
        (
            x,
            axle_y + radius * math.sin(2.0 * math.pi * step / sectors),
            axle_z + radius * math.cos(2.0 * math.pi * step / sectors),
        )
        for step in range(sectors)
    ]


def pickup_shells():
    """A 5.9 x 2.05 x 1.9 m pickup: body, four wheels, two doors, a bonnet, glass and lamps."""
    shells = [
        shell(0, (-1.0, 0.2, -2.95), (1.0, 1.6, 2.95), CHASSIS, "paint", 120_000, area=26.0),
        shell(1, (-0.9, 1.55, -1.3), (0.9, 1.62, 0.4), UNCLASSIFIED, "trim_x", 400, area=3.0),
        shell(2, (-1.02, 0.55, -0.35), (-0.96, 1.45, 0.75), PANEL, "paint", 2400, area=1.6),
        shell(3, (0.96, 0.55, -0.35), (1.02, 1.45, 0.75), PANEL, "paint", 2400, area=1.6),
        shell(4, (-0.85, 1.42, 1.25), (0.85, 1.5, 2.55), PANEL, "paint", 3000, area=2.4),
        shell(5, (-0.8, 1.45, 0.45), (0.8, 1.86, 1.15), GLASS, "glass", 800, area=1.3),
        shell(6, (0.5, 0.95, 2.8), (0.95, 1.25, 2.94), LIGHT, "lamp", 600, area=0.25),
        shell(7, (-0.95, 0.95, 2.8), (-0.5, 1.25, 2.94), LIGHT, "lamp", 600, area=0.25),
        shell(8, (-0.8, 0.6, -1.2), (0.8, 1.4, 0.35), INTERIOR, "cloth", 5000, area=7.0),
        shell(9, (-0.3, 1.0, -2.94), (0.3, 1.2, -2.9), DECAL, "badge", 120, area=0.1),
    ]
    index = 20
    for x, z in ((-0.86, 1.85), (0.86, 1.85), (-0.86, -1.75), (0.86, -1.75)):
        shells.append(
            shell(index, (x - 0.14, 0.02, z - 0.4), (x + 0.14, 0.82, z + 0.4), WHEEL, "tyre",
                  2000, area=1.7, vertices=ring(x, 0.42, z, 0.40))
        )
        # A caliper: 120° of arc about the axle, once. It seeds nothing and turns with nothing.
        shells.append(
            shell(index + 1, (x - 0.06, 0.30, z - 0.14), (x + 0.06, 0.55, z + 0.02), CHASSIS,
                  "calliper", 300, area=0.18,
                  vertices=[(x, 0.42 + 0.13 * math.sin(math.radians(a)),
                             z + 0.13 * math.cos(math.radians(a))) for a in range(0, 130, 10)])
        )
        index += 10
    return shells


def classified():
    """The pickup, through stages 4 and 5: labelled, cornered, symmetry-resolved, grouped."""
    shells = pickup_shells()
    body = cues.BodyFrame(shells)
    roles.assign_roles(shells, body)
    corners = roles.find_corners(shells, body)
    roles.capture_into_corners(shells, corners)
    roles.resolve_rotation(corners)
    grouping.merge_small_shells(shells)
    parts = grouping.group_into_parts(shells, cues.find_mirror_twins(shells))
    return shells, parts, corners, body
