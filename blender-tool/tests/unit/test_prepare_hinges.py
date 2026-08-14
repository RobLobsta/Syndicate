"""Stage 6: articulation rigging (D15-S5.6), and T-D15-10.

The rule these tests exist for is the one that is invisible until somebody opens a door in the
game: a hinge's *sign*. A left door and a right door turn about the same axis in opposite
directions, and a bonnet and a boot lid hinge at opposite ends of the same panel. Get any of
the four wrong and the panel opens through the car — which D15-E9 says is worse than a panel
that does not open at all.
"""

from __future__ import annotations

from syndicate_prepare import hinges, roles
from syndicate_prepare.grouping import Part
from syndicate_prepare.labels import PANEL
from syndicate_prepare.shell import Shell


class Body:
    """The vehicle's own bounds — what a swung panel must finish outside of (D15-E9)."""

    lo = (-0.9, 0.0, -2.25)
    hi = (0.9, 1.3, 2.25)


def part(lo, hi, role, side="c"):
    shell = Shell(
        index=1,
        name="panel",
        material=None,
        triangles=900,
        lo=lo,
        hi=hi,
        centroid=tuple((lo[i] + hi[i]) * 0.5 for i in range(3)),
    )
    shell.label = PANEL
    shell.role = role
    group = Part(label=PANEL, side=side, index=0, role=role)
    group.shells = [shell]
    return group


def left_door():
    return part((-0.92, 0.35, -0.55), (-0.86, 1.05, 0.55), roles.DOOR, side="l")


def right_door():
    return part((0.86, 0.35, -0.55), (0.92, 1.05, 0.55), roles.DOOR, side="r")


def bonnet():
    return part((-0.75, 0.92, 0.7), (0.75, 0.99, 1.95), roles.BONNET)


def boot():
    return part((-0.7, 0.95, -2.0), (0.7, 1.01, -1.1), roles.BOOT)


# ---- Which parts get a hinge at all ----------------------------------------------------


def test_a_door_a_bonnet_and_a_boot_are_hinged():
    for group in (left_door(), right_door(), bonnet(), boot()):
        assert hinges.infer(group, Body()) is not None


def test_a_bumper_is_not_hinged():
    """D15-R29 case 3: no inference, so the part is rigid and detaches without opening."""
    bumper = part((-0.88, 0.25, 2.12), (0.88, 0.75, 2.25), roles.BUMPER)
    assert hinges.infer(bumper, Body()) is None


def test_a_part_with_no_role_is_not_hinged():
    assert hinges.infer(part((-0.5, 0.2, 0.0), (0.5, 0.4, 0.5), None), Body()) is None


# ---- The signs, which are the whole point ------------------------------------------------


def test_a_door_hinges_about_a_vertical_axis_at_its_forward_edge():
    hinge = hinges.infer(left_door(), Body())
    assert hinge.axis == (0.0, 1.0, 0.0)
    assert hinge.pivot[2] == 0.55  # the panel's own +z edge


def test_the_two_doors_open_in_opposite_directions():
    left = hinges.infer(left_door(), Body())
    right = hinges.infer(right_door(), Body())
    assert left.open_deg == -right.open_deg


def test_each_door_opens_away_from_the_car():
    """The failure this catches is a door swinging through the cabin, which looks like a bug."""
    for group in (left_door(), right_door()):
        hinge = hinges.infer(group, Body())
        free_corner = (group.centre[0], group.centre[1], group.lo[2])
        swung = hinge.rotate(free_corner)
        assert abs(swung[0]) > abs(free_corner[0])


def test_a_bonnet_and_a_boot_both_open_upwards_about_opposite_edges():
    bonnet_hinge = hinges.infer(bonnet(), Body())
    boot_hinge = hinges.infer(boot(), Body())
    assert bonnet_hinge.axis == boot_hinge.axis == (1.0, 0.0, 0.0)
    # Rear-most edge for the bonnet, forward-most for the boot (D15-R29 case 2).
    assert bonnet_hinge.pivot[2] == 0.7
    assert boot_hinge.pivot[2] == -1.1
    for group, hinge in ((bonnet(), bonnet_hinge), (boot(), boot_hinge)):
        far = max(
            (group.lo[2], group.hi[2]), key=lambda z: abs(z - hinge.pivot[2])
        )
        swung = hinge.rotate((group.centre[0], group.centre[1], far))
        assert swung[1] > group.centre[1]


# ---- T-D15-10 / D15-E9 --------------------------------------------------------------------


def test_a_hinge_that_would_swing_the_panel_into_the_body_is_rejected():
    """A door whose free edge is *ahead* of its pivot swings inwards, and must not ship."""
    inverted = hinges.Hinge(
        axis=(0.0, 1.0, 0.0),
        pivot=(-0.89, 0.7, 0.55),
        open_deg=-62.0,  # the wrong sign for a left door
        because="deliberately inverted",
    )
    assert hinges.clears_body(left_door(), inverted, Body()) is False


def test_every_inferred_hinge_clears_the_body():
    """T-D15-10: open every inferred hinge to its authored angle; nothing intersects."""
    for group in (left_door(), right_door(), bonnet(), boot()):
        hinge = hinges.infer(group, Body())
        assert hinges.clears_body(group, hinge, Body())


# ---- D15-R29 case 1: a declared hinge always wins --------------------------------------------


def test_a_declared_hinge_beats_the_inferred_one():
    from syndicate_prepare.overrides import Hinge as Declared

    declared = Declared(part="panel_x_door_l_01", axis="z", pivot=(1.0, 2.0, 3.0), open_deg=25.0)
    hinge = hinges.infer(left_door(), Body(), declared)
    assert hinge.axis == (0.0, 0.0, 1.0)
    assert hinge.pivot == (1.0, 2.0, 3.0)
    assert hinge.open_deg == 25.0
    assert "declared" in hinge.because
