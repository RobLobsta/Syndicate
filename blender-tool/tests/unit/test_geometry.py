"""Mesh measurement and convex hulls (D09-S6.2, D09-S5.5).

Every case here has a closed-form answer, which is the point: D09-S6.2 says the tool and the
harness implement the same formulas independently so that agreement is evidence. That
argument only works if each implementation is independently checked against the analytic
truth rather than against the other.
"""

import math

import pytest

from syndicate_fracture.geometry import (
    Vec3,
    aabb_of,
    convex_hull,
    inertia_diagonal,
    inflate_hull,
    max_outside_distance,
    mesh_centroid,
    mesh_volume,
    quantise,
    simplify_hull,
    surface_area,
    vertex_normals,
)


def cube(
    size: float = 1.0, base_at_zero: bool = True
) -> tuple[list[Vec3], list[tuple[int, int, int]]]:
    """A closed axis-aligned box with outward-facing triangles."""
    h = size / 2.0
    lo = 0.0 if base_at_zero else -h
    hi = size if base_at_zero else h
    v: list[Vec3] = [
        (-h, -h, lo),
        (h, -h, lo),
        (h, h, lo),
        (-h, h, lo),
        (-h, -h, hi),
        (h, -h, hi),
        (h, h, hi),
        (-h, h, hi),
    ]
    quads = [
        (0, 3, 2, 1),
        (4, 5, 6, 7),
        (0, 1, 5, 4),
        (1, 2, 6, 5),
        (2, 3, 7, 6),
        (3, 0, 4, 7),
    ]
    tris = []
    for a, b, c, d in quads:
        tris.append((a, b, c))
        tris.append((a, c, d))
    return v, tris


def sphere_points(rings: int = 20, segments: int = 20) -> list[Vec3]:
    points = []
    for i in range(rings):
        theta = math.pi * i / (rings - 1)
        for j in range(segments):
            phi = 2.0 * math.pi * j / segments
            points.append(
                (math.sin(theta) * math.cos(phi), math.sin(theta) * math.sin(phi), math.cos(theta))
            )
    return points


class TestMeasurement:
    def test_unit_cube_volume_is_exactly_one(self) -> None:
        v, t = cube(1.0)
        assert mesh_volume(v, t) == pytest.approx(1.0, abs=1e-12)

    def test_volume_scales_cubically(self) -> None:
        v, t = cube(2.0)
        assert mesh_volume(v, t) == pytest.approx(8.0, abs=1e-12)

    def test_centroid_of_a_base_origin_cube_is_half_its_height(self) -> None:
        # The analytic value D14-S7.1 records for test_cube_1m, in Blender's Z-up space.
        v, t = cube(1.0)
        assert mesh_centroid(v, t) == pytest.approx((0.0, 0.0, 0.5), abs=1e-9)

    def test_inertia_matches_the_closed_form_for_a_cube(self) -> None:
        # D14-R22: m(h^2 + d^2)/12 about each axis, the only fixture with analytic truth.
        v, t = cube(1.0)
        mass = 7850.0
        expected = mass * (1.0 + 1.0) / 12.0
        diagonal = inertia_diagonal(v, t, mass)
        for axis in diagonal:
            assert axis == pytest.approx(expected, rel=1e-6)

    def test_surface_area_of_a_unit_cube_is_six(self) -> None:
        v, t = cube(1.0)
        assert surface_area(v, t) == pytest.approx(6.0, abs=1e-12)

    def test_volume_is_positive_regardless_of_winding(self) -> None:
        # An inward-wound source is a content bug, not a negative mass.
        v, t = cube(1.0)
        flipped = [(a, c, b) for a, b, c in t]
        assert mesh_volume(v, flipped) == pytest.approx(1.0, abs=1e-12)

    def test_aabb_bounds_every_vertex(self) -> None:
        v, _ = cube(2.0, base_at_zero=False)
        box = aabb_of(v)
        assert box.min == pytest.approx((-1.0, -1.0, -1.0))
        assert box.max == pytest.approx((1.0, 1.0, 1.0))
        assert box.volume == pytest.approx(8.0)
        assert box.max_extent == pytest.approx(2.0)

    def test_vertex_normals_point_outward_on_a_cube(self) -> None:
        v, t = cube(1.0, base_at_zero=False)
        normals = vertex_normals(v, t)
        for position, normal in zip(v, normals, strict=True):
            # Each cube corner's area-weighted normal points away from the centre.
            assert sum(p * n for p, n in zip(position, normal, strict=True)) > 0.0


class TestConvexHull:
    def test_hull_of_a_cube_is_the_cube(self) -> None:
        v, _ = cube(1.0)
        points, tris = convex_hull(v)
        assert len(points) == 8
        assert mesh_volume(points, tris) == pytest.approx(1.0, abs=1e-9)

    def test_hull_contains_every_input_point(self) -> None:
        # The property that made the cylinder fixture fail when face winding was wrong: a
        # hull that reports its own inputs as outside itself is silently inverted.
        points, tris = convex_hull(sphere_points())
        assert max_outside_distance(points, tris, sphere_points()) < 1e-9

    def test_hull_discards_interior_points(self) -> None:
        v, _ = cube(1.0, base_at_zero=False)
        points, _ = convex_hull([*v, (0.0, 0.0, 0.0), (0.1, 0.1, 0.1)])
        assert len(points) == 8

    def test_hull_of_a_tetrahedron_has_the_closed_form_volume(self) -> None:
        points, tris = convex_hull([(0, 0, 0), (1, 0, 0), (0, 1, 0), (0, 0, 1)])
        assert len(points) == 4
        assert mesh_volume(points, tris) == pytest.approx(1 / 6, abs=1e-12)

    def test_degenerate_input_yields_an_empty_hull(self) -> None:
        # Coplanar or too-few points must produce nothing, so the caller reports
        # HULL_FAILED rather than shipping a flat collision shape.
        assert convex_hull([(0, 0, 0), (1, 0, 0), (0, 1, 0)]) == ([], [])
        assert convex_hull([(0, 0, 0), (1, 0, 0), (0, 1, 0), (1, 1, 0)]) == ([], [])

    def test_simplification_reaches_the_budget_and_keeps_most_volume(self) -> None:
        points, tris = convex_hull(sphere_points())
        assert len(points) > 32
        full_volume = mesh_volume(points, tris)

        simple_points, simple_tris = simplify_hull(points, tris, 32)
        assert len(simple_points) <= 32
        # Maximising remaining volume is what makes this hold; minimising the "increase",
        # as D09-S5.5 words it, selects the most destructive removal every step (DEV-003).
        assert mesh_volume(simple_points, simple_tris) > 0.75 * full_volume

    def test_inflation_restores_enclosure_after_simplification(self) -> None:
        source = sphere_points()
        points, tris = convex_hull(source)
        simple_points, simple_tris = simplify_hull(points, tris, 32)
        shortfall = max_outside_distance(simple_points, simple_tris, source)
        assert shortfall > 0.0, "simplifying a curved hull must leave it inside the source"

        grown_points, grown_tris = inflate_hull(simple_points, simple_tris, shortfall * 1.05)
        assert max_outside_distance(grown_points, grown_tris, source) <= 0.0


class TestQuantisation:
    def test_nearby_points_share_a_lattice_cell(self) -> None:
        # Why shard ordering is stable: a difference far below any tolerance must not
        # reorder the shards (D09-R10, G11).
        assert quantise((1.000_000_1, 0.0, 0.0)) == quantise((1.000_000_2, 0.0, 0.0))

    def test_distinct_points_do_not_collide(self) -> None:
        assert quantise((0.0, 0.0, 0.0)) != quantise((0.001, 0.0, 0.0))
