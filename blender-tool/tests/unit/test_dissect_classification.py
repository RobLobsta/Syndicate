"""The dissection classifier, without a Blender host.

The tests that matter here are the ones about *rejection*. Finding a wheel is easy — four
round things at the corners of a car — and every wrong answer this tool has produced so far
came from something that is not a wheel passing the test: a wing mirror, a brake caliper, a
sill. So each threshold gets a case that sits just the wrong side of it, taken from the
geometry that actually fooled an earlier version rather than from a made-up shape.
"""

from __future__ import annotations

from dataclasses import dataclass

from syndicate_dissect import dissect


@dataclass(frozen=True)
class V:
    """A stand-in for ``mathutils.Vector``: the classifier only reads x, y and z."""

    x: float
    y: float
    z: float

    def __add__(self, other):
        return V(self.x + other.x, self.y + other.y, self.z + other.z)

    def __sub__(self, other):
        return V(self.x - other.x, self.y - other.y, self.z - other.z)

    def __truediv__(self, scalar):
        return V(self.x / scalar, self.y / scalar, self.z / scalar)


def island(centre: V, size: V, triangles: int = 100) -> dissect.Island:
    half = size / 2
    return dissect.Island(obj=None, lo=centre - half, hi=centre + half, triangles=triangles)


# ---- What a wheel is ------------------------------------------------------------------


def test_a_tyre_is_a_wheel():
    """The Eclipse's front tyre: 0.707 m across, 0.269 wide, axle at 0.356."""
    assert dissect.is_wheel_shaped(island(V(0.856, 0.356, 1.457), V(0.269, 0.707, 0.707)))


def test_a_brake_disc_is_a_wheel():
    """Smaller than the tyre, thinner, same axle — still round, still a wheel piece."""
    assert dissect.is_wheel_shaped(island(V(0.878, 0.356, 1.457), V(0.056, 0.408, 0.408)))


# ---- What a wheel is not ---------------------------------------------------------------


def test_a_wing_mirror_is_not_a_wheel():
    """The case that broke the first version.

    Outboard, below the old height limit, and round enough to pass a 0.45 roundness
    tolerance. It dragged the Eclipse's front axle 0.30 m rearward and reported a 1.31 m
    tyre. Two thresholds moved because of it; either alone would reject it.
    """
    assert not dissect.is_wheel_shaped(island(V(0.993, 0.746, 0.757), V(0.164, 0.337, 0.510)))


def test_a_sill_panel_is_not_a_wheel():
    """Low and outboard and nowhere near round: two metres long, ten centimetres tall."""
    assert not dissect.is_wheel_shaped(island(V(0.9, 0.30, 0.0), V(0.10, 0.12, 2.0)))


def test_a_driveshaft_is_not_a_wheel():
    """On the centreline, so it fails before roundness is even considered."""
    assert not dissect.is_wheel_shaped(island(V(0.0, 0.30, 0.0), V(1.2, 0.10, 0.10)))


def test_a_roof_panel_is_not_a_wheel():
    """Round in profile and far too high to be a road wheel."""
    assert not dissect.is_wheel_shaped(island(V(0.7, 1.20, 0.0), V(0.5, 0.60, 0.60)))


def test_a_spare_wheel_on_the_boot_floor_is_rejected_by_height():
    """A wheel that is not a road wheel. It belongs to the chassis, and it turns with nothing."""
    assert not dissect.is_wheel_shaped(island(V(0.5, 0.90, -1.9), V(0.25, 0.70, 0.70)))


# ---- Corners ---------------------------------------------------------------------------


def test_wheels_are_assigned_to_the_corner_they_sit_in():
    islands = [
        island(V(-0.85, 0.36, 1.45), V(0.27, 0.71, 0.71)),
        island(V(0.85, 0.36, 1.45), V(0.27, 0.71, 0.71)),
        island(V(-0.83, 0.36, -1.24), V(0.32, 0.72, 0.72)),
        island(V(0.83, 0.36, -1.24), V(0.32, 0.72, 0.72)),
    ]
    groups = dissect.seed_wheels(islands)
    assert sorted(groups) == ["fl", "fr", "rl", "rr"]
    assert all(len(g.seeds) == 1 for g in groups.values())


def test_front_and_rear_split_at_the_wheels_own_midpoint():
    """Not at the vehicle's centre.

    A mid-engined car's wheels are not symmetric about its bodywork, and a long front
    overhang would otherwise push the divide past the front axle and file both front wheels
    as rear ones.
    """
    islands = [
        island(V(-0.85, 0.36, 2.00), V(0.27, 0.71, 0.71)),
        island(V(0.85, 0.36, 2.00), V(0.27, 0.71, 0.71)),
        island(V(-0.83, 0.36, 0.10), V(0.32, 0.72, 0.72)),
        island(V(0.83, 0.36, 0.10), V(0.32, 0.72, 0.72)),
    ]
    groups = dissect.seed_wheels(islands)
    assert sorted(groups) == ["fl", "fr", "rl", "rr"]


def test_no_wheels_yields_no_groups():
    assert dissect.seed_wheels([island(V(0.0, 0.5, 0.0), V(2.0, 1.0, 4.0))]) == {}


# ---- Capture ---------------------------------------------------------------------------


def test_a_caliper_inside_the_wheel_cylinder_is_captured():
    centre = V(0.85, 0.36, 1.45)
    caliper = island(V(0.86, 0.42, 1.44), V(0.17, 0.28, 0.20))
    assert dissect._inside_cylinder(caliper, centre, 0.40, 0.20)


def test_a_wishbone_reaching_out_of_the_cylinder_is_not_captured():
    """Its centroid is beside the hub and its far end is bolted to the subframe.

    Containment rather than proximity is what keeps this out — and capturing it would take
    the subframe, and then the floor pan, off with the wheel.
    """
    centre = V(0.85, 0.36, 1.45)
    wishbone = island(V(0.60, 0.34, 1.45), V(0.60, 0.08, 0.10))
    assert not dissect._inside_cylinder(wishbone, centre, 0.40, 0.20)
