"""Mesh measurement and convex hulls (D09-S6.2, D09-S5.5).

Every case here has a closed-form answer, which is the point: D09-S6.2 says the tool and the
harness implement the same formulas independently so that agreement is evidence. That
argument only works if each implementation is independently checked against the analytic
truth rather than against the other.
"""

import math

import pytest

from syndicate_fracture.geometry import (
    Polytope,
    Vec3,
    _repair_visible_cap,
    _signed_distance,
    aabb_of,
    boundary_edge_count,
    clip_polytope,
    convex_hull,
    cross,
    face_planes,
    inertia_diagonal,
    inflate_hull,
    intersect_halfspaces,
    is_convex,
    is_watertight,
    max_outside_distance,
    mesh_centroid,
    mesh_volume,
    normalize,
    quantise,
    simplify_hull,
    sub,
    surface_area,
    vertex_normals,
)
from syndicate_fracture.hulls import HULL_ENCLOSE_M
from syndicate_fracture.sites import bisector_planes

from .glass_shard_fixture import GLASS_SHARD_VERTICES


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

    def test_a_closed_cube_has_no_boundary_edges(self) -> None:
        v, t = cube(1.0)
        assert boundary_edge_count(v, t) == 0
        assert is_watertight(v, t)

    def test_a_cube_missing_a_face_is_not_watertight(self) -> None:
        # Two triangles removed is one square hole, whose four edges are now unshared.
        v, t = cube(1.0)
        assert boundary_edge_count(v, t[:-2]) == 4
        assert not is_watertight(v, t[:-2])

    def test_split_vertices_do_not_read_as_holes(self) -> None:
        """The case that matters on real content: glTF stores one vertex per (position, normal).

        A closed cube imported from glTF therefore arrives with every corner duplicated and no
        two faces sharing an index. Counting on indices calls that 24 boundary edges and
        rejects the tool's own fixtures; counting on positions calls it what it is (DISC-065).
        """
        v, t = cube(1.0)
        split_vertices: list[Vec3] = []
        split_triangles = []
        for tri in t:
            base = len(split_vertices)
            split_vertices.extend(v[i] for i in tri)
            split_triangles.append((base, base + 1, base + 2))
        # Nothing is shared by index...
        assert len({i for tri in split_triangles for i in tri}) == 3 * len(t)
        # ...and it is still a closed cube.
        assert boundary_edge_count(split_vertices, split_triangles) == 0

    def test_a_sheet_is_not_watertight(self) -> None:
        # One quad: a panel, a pane, anything that has area and encloses nothing.
        sheet: list[Vec3] = [(0.0, 0.0, 0.0), (1.0, 0.0, 0.0), (1.0, 1.0, 0.0), (0.0, 1.0, 0.0)]
        assert boundary_edge_count(sheet, [(0, 1, 2), (0, 2, 3)]) == 4

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


class TestHalfSpaceIntersection:
    """The exact cell construction of D09-S5.2 (see DEV-005).

    Pure geometry, so it is unit-testable without Blender — which matters, because this is
    the code that decides whether a fracture is a Voronoi decomposition at all, and the
    property tests that check it end-to-end need a Blender host to run.
    """

    @staticmethod
    def unit_box() -> Polytope:
        return Polytope.box((-1.0, -1.0, -1.0), (1.0, 1.0, 1.0))

    def test_seed_box_has_its_analytic_volume(self) -> None:
        assert self.unit_box().volume() == pytest.approx(8.0)

    def test_clipping_by_a_plane_through_the_centre_halves_it(self) -> None:
        half = clip_polytope(self.unit_box(), (1.0, 0.0, 0.0), 0.0)
        assert half.volume() == pytest.approx(4.0)

    def test_three_orthogonal_clips_give_an_octant(self) -> None:
        octant = intersect_halfspaces(
            self.unit_box(),
            [((1.0, 0.0, 0.0), 0.0), ((0.0, 1.0, 0.0), 0.0), ((0.0, 0.0, 1.0), 0.0)],
        )
        assert octant.volume() == pytest.approx(1.0)
        assert len(octant.faces) == 6

    def test_reconstructing_a_box_from_its_own_face_planes_is_exact(self) -> None:
        """The property the convex fracture path rests on.

        A convex solid *is* its face half-spaces, so intersecting a larger seed with them
        must reproduce it exactly — no boolean, no mesh cutting, no tolerance.
        """
        target, target_tris = cube(1.0)
        planes = face_planes(target, target_tris)
        assert len(planes) == 6

        seed = Polytope.box((-5.0, -5.0, -5.0), (5.0, 5.0, 5.0))
        rebuilt = intersect_halfspaces(seed, planes)
        assert rebuilt.volume() == pytest.approx(mesh_volume(target, target_tris), rel=1e-9)

    def test_clipping_everything_away_yields_an_empty_polytope(self) -> None:
        empty = intersect_halfspaces(
            self.unit_box(), [((1.0, 0.0, 0.0), -2.0), ((-1.0, 0.0, 0.0), -2.0)]
        )
        assert empty.is_empty()
        assert empty.volume() == 0.0

    def test_a_plane_that_misses_leaves_the_polytope_untouched(self) -> None:
        poly = clip_polytope(self.unit_box(), (1.0, 0.0, 0.0), 50.0)
        assert poly.volume() == pytest.approx(8.0)

    def test_repeated_clips_stay_exact(self) -> None:
        """Clips after the first meet vertices lying exactly on the plane.

        That case is what broke cap construction by segment chaining: a coincident vertex
        both ends and begins a boundary segment. Slicing a box repeatedly along the same
        axis exercises it directly.
        """
        poly = self.unit_box()
        for offset in (0.9, 0.8, 0.7, 0.6, 0.5):
            poly = clip_polytope(poly, (1.0, 0.0, 0.0), offset)
        assert poly.volume() == pytest.approx(6.0)

    def test_cells_of_two_sites_tile_the_seed(self) -> None:
        """Two sites split space along their bisector; the halves must sum to the whole."""
        seed = self.unit_box()
        a, b = (-0.5, 0.0, 0.0), (0.5, 0.0, 0.0)
        left = intersect_halfspaces(seed, bisector_planes(a, [b]))
        right = intersect_halfspaces(seed, bisector_planes(b, [a]))
        assert left.volume() + right.volume() == pytest.approx(seed.volume())
        assert left.volume() == pytest.approx(right.volume())


class TestConvexity:
    def test_a_box_is_convex(self) -> None:
        vertices, triangles = cube(1.0)
        assert is_convex(vertices, triangles)

    def test_a_sphere_is_convex(self) -> None:
        points = sphere_points()
        hull_points, hull_tris = convex_hull(points)
        assert is_convex(hull_points, hull_tris)

    def test_a_hollow_box_is_not_convex(self) -> None:
        """The case that selects the fallback path (DEV-004).

        An outer shell plus an inward-wound inner shell: every cavity vertex sits outside
        some outer face plane's inner side, so convexity must be rejected — otherwise the
        cavity would be carved as if the part were solid.
        """
        outer_v, outer_t = cube(1.0)
        inner_v, inner_t = cube(0.5)
        offset = len(outer_v)
        vertices = outer_v + [(v[0], v[1] + 0.25, v[2]) for v in inner_v]
        triangles = outer_t + [(k + offset, j + offset, i + offset) for i, j, k in inner_t]
        assert not is_convex(vertices, triangles)


# ---- A flat part's hull (DISC-040) ---------------------------------------------------------


def curved_pane(nx: int = 24, ny: int = 12) -> list[tuple[float, float, float]]:
    """A windscreen: 1.4 x 0.7 m with 30 mm of camber, and a rim that is exactly planar."""
    points = []
    for j in range(ny + 1):
        for i in range(nx + 1):
            u, v = i / nx, j / ny
            camber = 0.03 * math.cos(math.pi * (u - 0.5)) * math.cos(math.pi * (v - 0.5))
            points.append(((u - 0.5) * 1.4, camber, (v - 0.5) * 0.7))
    return points


def test_a_flat_parts_hull_survives_reduction_to_a_budget():
    """Direction sampling picked 32 extremes that were all on the pane's planar rim.

    Thirty-two directions spread over a sphere never land inside the couple of degrees where
    30 mm of camber outweighs 700 mm of width, so the apex was never sampled, the kept set was
    coplanar, and the hull collapsed to nothing — taking the whole part with it (exit 71).
    """
    points = curved_pane()
    hull, triangles = convex_hull(points)
    assert mesh_volume(hull, triangles) > 0.01

    reduced, reduced_triangles = simplify_hull(hull, triangles, 32)
    assert reduced_triangles, "the hull collapsed"
    assert len(reduced) <= 32
    assert mesh_volume(reduced, reduced_triangles) > 0.0


def test_the_repair_only_fires_when_the_sampling_is_degenerate():
    """A sphere's extremes are never coplanar, so the reduction is untouched by the repair."""
    points = [
        (
            math.cos(theta) * math.sin(phi),
            math.sin(theta) * math.sin(phi),
            math.cos(phi),
        )
        for theta in [i * math.tau / 24 for i in range(24)]
        for phi in [j * math.pi / 12 for j in range(1, 12)]
    ]
    hull, triangles = convex_hull(points)
    reduced, reduced_triangles = simplify_hull(hull, triangles, 32)
    assert reduced_triangles
    assert len(reduced) <= 32
    # A sphere reduced to 32 directions keeps most of its volume; a collapse or a rescue that
    # fired wrongly would not.
    assert mesh_volume(reduced, reduced_triangles) > 0.6 * mesh_volume(
        hull, triangles
    )


# ---- A glass shard's hull (DISC-040) --------------------------------------------------------


def euler_holds(hull: list[Vec3], triangles: list[tuple[int, int, int]]) -> bool:
    """Whether a closed triangulated polyhedron's face and vertex counts agree.

    ``V - E + F = 2`` with ``3F = 2E`` gives ``F = 2V - 4``. It is the cheapest statement of
    "this is a polyhedron and not a bag of triangles", and it is what the broken hull failed:
    30 vertices carrying 71 triangles where 56 is the only possible answer.
    """
    return len(triangles) == 2 * len(hull) - 4


def test_a_glass_shards_hull_is_a_polyhedron_that_encloses_it():
    """The visible-face set went non-disc, and the fan built across it self-intersected.

    Points of a 5 mm slab sit on each other's face planes, so a face in the middle of the
    visible cap reads as hidden and the horizon comes back as two cycles rather than one.
    The hull that resulted was not convex, not closed, and left four of its own vertices
    22 mm outside itself — which the pipeline saw only later, as a part that would not
    fracture (exit 71).
    """
    hull, triangles = convex_hull(GLASS_SHARD_VERTICES)
    assert triangles, "the hull collapsed"
    assert euler_holds(hull, triangles), (
        f"{len(hull)} vertices carrying {len(triangles)} triangles is not a polyhedron"
    )
    assert is_convex(hull, triangles)
    assert max_outside_distance(hull, triangles, GLASS_SHARD_VERTICES) <= HULL_ENCLOSE_M


def test_a_glass_shards_hull_is_built_rather_than_inflated_into_enclosing_it():
    """Enclosure has to come from the hull being right, not from `build_hull` growing it.

    A hull that encloses only because it was inflated past the shortfall is a hull whose
    every face has been pushed out by that much, and 22 mm of collision margin on a shard
    90 mm across is a shard that never touches anything. This asserts the raw hull is
    already within a tolerance that leaves the inflate nothing to do.
    """
    hull, triangles = convex_hull(GLASS_SHARD_VERTICES)
    assert max_outside_distance(hull, triangles, GLASS_SHARD_VERTICES) < 1e-6


def test_the_cap_repair_leaves_a_well_formed_classification_alone():
    """The repair reclassifies only what is already impossible, so a clean cap is untouched.

    A cube seen from beyond one face has exactly two visible triangles and six hidden ones,
    both sides connected. Anything the repair changed there would be a change it had no
    business making, and would show up as a hull that tracks its source less closely.
    """
    corners, triangles = cube(size=2.0, base_at_zero=False)
    faces = [
        (corners[i], corners[j], corners[k], normalize(
            cross(sub(corners[j], corners[i]), sub(corners[k], corners[i]))
        ))
        for i, j, k in triangles
    ]
    viewpoint = (0.0, 0.0, 5.0)
    flags = [_signed_distance(face, viewpoint) > 1e-9 for face in faces]
    assert sum(flags) == 2, "the fixture is not the two-faces-visible case it claims to be"
    assert _repair_visible_cap(faces, list(flags)) == flags
