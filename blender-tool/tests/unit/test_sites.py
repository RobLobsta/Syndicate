"""Fracture site generation and Voronoi bisectors (D09-S5.2).

Worth testing more carefully than most of the tool: a site generator that quietly clusters
still produces a fracture that conserves mass, builds valid hulls, and passes every numeric
check — while looking wrong. No downstream check catches it.
"""

import pytest

from syndicate_fracture.geometry import Aabb, distance
from syndicate_fracture.rng import Pcg32
from syndicate_fracture.sites import (
    bisector_planes,
    impact_biased_sites,
    sort_sites,
    surface_biased_sites,
    uniform_sites,
)

UNIT_BOX = Aabb((-0.5, -0.5, 0.0), (0.5, 0.5, 1.0))


def always_inside(_point) -> bool:
    return True


class TestUniformSites:
    def test_places_the_requested_count(self) -> None:
        sites = uniform_sites(12, UNIT_BOX, Pcg32(1001), always_inside)
        assert len(sites) == 12

    def test_every_site_is_inside_the_bounds(self) -> None:
        sites = uniform_sites(16, UNIT_BOX, Pcg32(3), always_inside)
        for site in sites:
            for axis in range(3):
                assert UNIT_BOX.min[axis] <= site[axis] <= UNIT_BOX.max[axis]

    def test_respects_the_inside_predicate(self) -> None:
        # Only the upper half of the box is "inside"; no site may land below it.
        sites = uniform_sites(10, UNIT_BOX, Pcg32(5), lambda p: p[2] > 0.5)
        assert sites and all(site[2] > 0.5 for site in sites)

    def test_spacing_keeps_sites_apart(self) -> None:
        # The rejection constraint is what makes shards comparable in size; without it,
        # clusters produce slivers between a few huge cells.
        sites = uniform_sites(12, UNIT_BOX, Pcg32(1001), always_inside)
        min_dist = (UNIT_BOX.volume / 12) ** (1 / 3) * 0.55
        closest = min(distance(a, b) for i, a in enumerate(sites) for b in sites[i + 1 :])
        assert closest >= min_dist * 0.99

    def test_is_reproducible_for_a_seed(self) -> None:
        assert uniform_sites(12, UNIT_BOX, Pcg32(1001), always_inside) == uniform_sites(
            12, UNIT_BOX, Pcg32(1001), always_inside
        )

    def test_relaxes_rather_than_failing_when_spacing_is_impossible(self) -> None:
        # A thin plate genuinely cannot hold many well-spaced sites; rejecting there would
        # refuse legitimate content.
        thin = Aabb((-1.0, -0.5, 0.0), (1.0, 0.5, 0.02))
        sites = uniform_sites(24, thin, Pcg32(1002), always_inside)
        assert len(sites) >= 20


class TestOrdering:
    def test_sites_come_back_lexicographically_sorted(self) -> None:
        # D09-R10: cell order must not depend on the order rejection sampling accepted.
        sites = uniform_sites(10, UNIT_BOX, Pcg32(17), always_inside)
        assert sites == sorted(sites)

    def test_sort_is_stable_for_a_given_set(self) -> None:
        points = [(1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0)]
        assert sort_sites(points) == sort_sites(list(reversed(points)))


class TestBiasedModes:
    def test_impact_bias_concentrates_near_the_impact(self) -> None:
        impact = (0.0, 0.0, 0.5)
        sites = impact_biased_sites(200, UNIT_BOX, Pcg32(23), impact, lambda p: p)
        distances = sorted(distance(s, impact) for s in sites)
        median = distances[len(distances) // 2]
        # r ~ U^(1/3) puts the median at ~0.79 of the maximum radius, so half the sites sit
        # inside it. Sampling r uniformly would invert the intended bias.
        assert median < UNIT_BOX.max_extent * 0.6

    def test_impact_bias_clamps_into_the_mesh(self) -> None:
        clamped = impact_biased_sites(
            50,
            UNIT_BOX,
            Pcg32(29),
            (0.0, 0.0, 0.5),
            lambda p: tuple(min(max(p[a], UNIT_BOX.min[a]), UNIT_BOX.max[a]) for a in range(3)),
        )
        for site in clamped:
            for axis in range(3):
                assert UNIT_BOX.min[axis] <= site[axis] <= UNIT_BOX.max[axis]

    def test_surface_bias_pushes_sites_inward(self) -> None:
        # Sample the top face with an outward +Z normal; sites must end up below it.
        def sampler(_rng):
            return ((0.0, 0.0, 1.0), (0.0, 0.0, 1.0))

        sites = surface_biased_sites(5, UNIT_BOX, Pcg32(31), sampler)
        assert all(site[2] < 1.0 for site in sites)


class TestBisectors:
    def test_the_bisector_is_equidistant_from_both_sites(self) -> None:
        site = (0.0, 0.0, 0.0)
        other = (2.0, 0.0, 0.0)
        (normal, offset), = bisector_planes(site, [other])
        assert normal == pytest.approx((1.0, 0.0, 0.0))
        assert offset == pytest.approx(1.0)

    def test_the_site_is_on_the_kept_side(self) -> None:
        # The cell is `dot(n, p) <= offset`; the site must satisfy it, or clipping would
        # discard the very region it defines.
        site = (0.1, 0.2, 0.3)
        others = [(1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (-1.0, -1.0, -1.0)]
        for normal, offset in bisector_planes(site, others):
            assert sum(n * s for n, s in zip(normal, site, strict=True)) < offset

    def test_coincident_sites_contribute_no_constraint(self) -> None:
        # A duplicate site would otherwise produce a zero normal and clip everything away.
        assert bisector_planes((1.0, 1.0, 1.0), [(1.0, 1.0, 1.0)]) == []
