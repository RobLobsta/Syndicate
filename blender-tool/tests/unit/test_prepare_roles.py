"""Roles (a door, not a panel) and the rotational-symmetry pass of D15-S5.4.

Carries T-D15-7 (an annulus covers 360°, a wedge 90°), T-D15-2 (no caliper material in an
exported wheel) and T-D15-2b (a wheel keeps its lug nuts and valve stem). All three are about
the same rule and it is the subtle one in D15: symmetry is judged over a **material group in
one corner** (D15-R22), never over a single shell, because a lug nut plainly rotates and
plainly occupies 15° of the circle on its own.
"""

from __future__ import annotations

import math

from syndicate_prepare import cues, roles
from syndicate_prepare.labels import CHASSIS, GLASS, HUB, LIGHT, PANEL, WHEEL
from syndicate_prepare.shell import Shell


def shell(index, lo, hi, label=CHASSIS, material=None, triangles=800, vertices=(), **kwargs):
    centroid = kwargs.pop("centroid", tuple((lo[i] + hi[i]) * 0.5 for i in range(3)))
    made = Shell(
        index=index,
        name=f"obj_{index}",
        material=material,
        triangles=triangles,
        lo=lo,
        hi=hi,
        centroid=centroid,
        **kwargs,
    )
    made.label = label
    made.vertex_sample = tuple(vertices)
    return made


def body_frame():
    """A 4.5 x 1.8 x 1.3 m car, as every role threshold is a fraction of."""
    return cues.BodyFrame([shell(99, (-0.9, 0.0, -2.25), (0.9, 1.3, 2.25))])


def ring(centre_x, axle_y, axle_z, radius, sectors=48, width=0.22):
    """Vertices spread all the way round an axle — a tyre, a rim, a brake disc."""
    points = []
    for step in range(sectors):
        angle = 2.0 * math.pi * step / sectors
        for side in (-width / 2, width / 2):
            points.append(
                (centre_x + side, axle_y + radius * math.sin(angle),
                 axle_z + radius * math.cos(angle))
            )
    return points


def arc(centre_x, axle_y, axle_z, radius, degrees, sectors=8):
    """Vertices over part of the circle — a caliper clamped over one sector of the disc."""
    return [
        (
            centre_x,
            axle_y + radius * math.sin(math.radians(offset)),
            axle_z + radius * math.cos(math.radians(offset)),
        )
        for offset in [degrees * step / sectors for step in range(sectors + 1)]
    ]


# ---- Panel roles ---------------------------------------------------------------------------


def test_a_flank_panel_in_the_middle_of_the_car_is_a_door():
    body = body_frame()
    door = shell(1, (-0.92, 0.35, -0.55), (-0.86, 1.05, 0.55), label=PANEL)
    assert roles.role_for(door, body) == roles.DOOR


def test_a_horizontal_panel_over_the_front_of_the_car_is_a_bonnet():
    body = body_frame()
    bonnet = shell(2, (-0.75, 0.92, 0.7), (0.75, 0.99, 1.95), label=PANEL)
    assert roles.role_for(bonnet, body) == roles.BONNET


def test_a_horizontal_panel_over_the_back_of_the_car_is_a_boot():
    body = body_frame()
    boot = shell(3, (-0.7, 0.95, -2.0), (0.7, 1.01, -1.1), label=PANEL)
    assert roles.role_for(boot, body) == roles.BOOT


def test_a_transverse_panel_at_the_nose_is_a_bumper():
    body = body_frame()
    bumper = shell(4, (-0.88, 0.25, 2.12), (0.88, 0.75, 2.25), label=PANEL)
    assert roles.role_for(bumper, body) == roles.BUMPER


def test_a_flank_panel_low_on_the_body_is_a_sill():
    body = body_frame()
    sill = shell(5, (-0.9, 0.08, -0.6), (-0.84, 0.28, 0.6), label=PANEL)
    assert roles.role_for(sill, body) == roles.SILL


def test_a_flank_panel_ahead_of_the_doors_is_a_fender():
    body = body_frame()
    fender = shell(6, (-0.9, 0.5, 1.1), (-0.82, 1.0, 1.9), label=PANEL)
    assert roles.role_for(fender, body) == roles.FENDER


def test_something_too_small_to_be_a_panel_takes_no_role():
    body = body_frame()
    handle = shell(7, (-0.92, 0.72, -0.1), (-0.88, 0.78, 0.05), label=PANEL)
    assert roles.role_for(handle, body) is None


# ---- Lamp and glazing roles -------------------------------------------------------------------


def test_a_lamp_at_the_front_is_a_headlight_and_at_the_back_a_tail_light():
    body = body_frame()
    head = shell(8, (0.4, 0.6, 2.0), (0.8, 0.8, 2.2), label=LIGHT)
    tail = shell(9, (0.4, 0.7, -2.2), (0.8, 0.9, -2.0), label=LIGHT)
    assert roles.role_for(head, body) == roles.HEAD
    assert roles.role_for(tail, body) == roles.TAIL


def test_the_big_pane_over_the_front_of_the_cabin_is_the_windscreen():
    body = body_frame()
    screen = shell(10, (-0.72, 0.95, 0.25), (0.72, 1.28, 0.95), label=GLASS)
    assert roles.role_for(screen, body) == roles.WINDSCREEN


def test_a_lamp_lens_is_not_a_windscreen():
    """The size test comes first, or a headlight puts a two-metre slot on the chassis."""
    body = body_frame()
    lens = shell(11, (0.45, 0.62, 2.06), (0.78, 0.79, 2.16), label=GLASS)
    assert roles.role_for(lens, body) == roles.LENS


def test_glass_in_a_flank_is_a_side_window():
    body = body_frame()
    window = shell(12, (-0.86, 1.0, -0.45), (-0.84, 1.26, 0.5), label=GLASS)
    assert roles.role_for(window, body) == roles.SIDE_WINDOW


# ---- Angular coverage: T-D15-7 -----------------------------------------------------------------


def test_an_annulus_covers_the_whole_circle_and_a_wedge_does_not():
    from syndicate_prepare.grouping import angular_coverage_deg
    from syndicate_prepare.labels import ROTATION_SECTORS

    axle = (-0.8, 0.35, 1.45)
    full = angular_coverage_deg(ring(axle[0], axle[1], axle[2], 0.33), axle, ROTATION_SECTORS)
    wedge = angular_coverage_deg(
        arc(axle[0], axle[1], axle[2], 0.30, 90.0), axle, ROTATION_SECTORS
    )
    assert full == 360.0
    assert wedge <= 90.0 + 360.0 / ROTATION_SECTORS


# ---- Corners and what turns with them --------------------------------------------------------


def wheel_corner_shells(index, x, z, material="tyre"):
    """A tyre, a rim, four lug nuts and a caliper, at one corner."""
    axle_y, radius = 0.35, 0.33
    made = [
        shell(index, (x - 0.11, 0.02, z - 0.33), (x + 0.11, 0.68, z + 0.33), label=WHEEL,
              material=material, vertices=ring(x, axle_y, z, radius)),
        shell(index + 1, (x - 0.09, 0.10, z - 0.25), (x + 0.09, 0.60, z + 0.25), label=WHEEL,
              material="rim", vertices=ring(x, axle_y, z, 0.24)),
    ]
    # Four lug nuts, each 15° of the circle on its own, all of one material.
    for nut in range(4):
        angle = math.radians(90.0 * nut)
        made.append(
            shell(
                index + 2 + nut,
                (x - 0.02, axle_y + 0.09 * math.sin(angle) - 0.02,
                 z + 0.09 * math.cos(angle) - 0.02),
                (x + 0.02, axle_y + 0.09 * math.sin(angle) + 0.02,
                 z + 0.09 * math.cos(angle) + 0.02),
                label=CHASSIS,
                material="lugnut",
                triangles=60,
                vertices=arc(x, axle_y, z, 0.09, 12.0) if nut == 0 else [
                    (x, axle_y + 0.09 * math.sin(angle + math.radians(offset)),
                     z + 0.09 * math.cos(angle + math.radians(offset)))
                    for offset in (0.0, 6.0, 12.0)
                ],
            )
        )
    made.append(
        shell(index + 6, (x - 0.05, axle_y - 0.02, z - 0.16), (x + 0.05, axle_y + 0.22, z + 0.02),
              label=CHASSIS, material="calliper", triangles=400,
              vertices=arc(x, axle_y, z, 0.22, 120.0))
    )
    return made


def four_wheeled_car():
    shells = [shell(0, (-0.9, 0.0, -2.25), (0.9, 1.3, 2.25), triangles=40_000)]
    index = 10
    for x, z in ((-0.8, 1.45), (0.8, 1.45), (-0.8, -1.35), (0.8, -1.35)):
        shells.extend(wheel_corner_shells(index, x, z))
        index += 10
    return shells


def test_four_wheel_shaped_groups_become_four_corners():
    shells = four_wheeled_car()
    corners = roles.find_corners(shells, body_frame())
    assert sorted(corner.name for corner in corners) == ["fl", "fr", "rl", "rr"]


def test_a_corners_axle_and_diameter_come_from_its_seeds_alone():
    """AC-D15-3: hub furniture must not change a wheel's reported size."""
    shells = four_wheeled_car()
    corner = next(c for c in roles.find_corners(shells, body_frame()) if c.name == "fl")
    assert math.isclose(corner.axle[1], 0.35, abs_tol=0.02)
    assert math.isclose(corner.axle[2], 1.45, abs_tol=0.02)
    assert math.isclose(corner.radius_m * 2, 0.66, abs_tol=0.03)


def test_the_caliper_stays_behind_and_the_lug_nuts_turn():
    """T-D15-2 and T-D15-2b, which are the same rule seen from its two sides."""
    shells = four_wheeled_car()
    body = body_frame()
    corners = roles.find_corners(shells, body)
    roles.capture_into_corners(shells, corners)
    roles.resolve_rotation(corners)

    by_material = {}
    for one in shells:
        if one.corner == "fl":
            by_material.setdefault(one.material, set()).add(one.label)

    assert by_material["calliper"] == {HUB}
    assert by_material["lugnut"] == {WHEEL}
    assert by_material["tyre"] == {WHEEL}
    assert by_material["rim"] == {WHEEL}


def test_every_corner_shell_is_either_a_wheel_or_a_hub_and_nothing_is_lost():
    """AC-D15-4 at the corner: capture must not drop or duplicate a shell."""
    shells = four_wheeled_car()
    corners = roles.find_corners(shells, body_frame())
    roles.capture_into_corners(shells, corners)
    roles.resolve_rotation(corners)
    captured = [one for one in shells if one.corner is not None]
    assert len(captured) == 4 * 7
    assert {one.label for one in captured} == {WHEEL, HUB}
    seen = [one.index for corner in corners for one in corner.rotating + corner.static]
    assert len(seen) == len(set(seen)) == len(captured)
