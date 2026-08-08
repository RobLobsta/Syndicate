"""The fracture really is a Voronoi decomposition (D09-S5.2).

Needs Blender, so it lives in ``tests/blender/`` and runs under `:blender-tool:blenderTest`
rather than in the pure-Python unit suite.

Three properties *define* a Voronoi decomposition, and each is checkable without trusting the
implementation that produced it. They exist as a test because D09-S5.2 specifies the result by
naming an add-on rather than by stating what the result must satisfy — so without them, "is
this still a Voronoi fracture?" can only be answered by reading the code, and every future
change to the clipping stage reopens the question.
"""

from __future__ import annotations

import random
from pathlib import Path

import pytest

from syndicate_fracture import blender
from syndicate_fracture.cli import parse
from syndicate_fracture.fracture import _build_cells, _generate_sites
from syndicate_fracture.geometry import aabb_of, distance, mesh_centroid, mesh_volume
from syndicate_fracture.rng import Pcg32, mix, stable_hash

pytestmark = pytest.mark.skipif(not blender.HAVE_BPY, reason="needs a Blender host")

# Fixtures are addressed from the repository root, not the working directory: this suite is
# run both by `:blender-tool:blenderTest` (which runs in `blender-tool/`) and directly.
REPO_ROOT = Path(__file__).resolve().parents[3]

FIXTURES = [
    ("test_cube_1m", 1001, 12),
    ("test_sphere_r0.5", 1006, 16),
    # A high-count case, because the properties can hold for a dozen cells and fail for a
    # hundred: more sites means more bisectors per cell, more near-degenerate clips, and
    # more chances for a cap to be built wrong.
    ("test_cube_1m", 2001, 100),
]

# Fixtures whose cells are known *not* to be true Voronoi cells, with the entry that explains
# it. Empty since the exact half-space path landed (DEV-005 resolved); the mechanism stays so
# a future source type that regresses can be recorded here rather than by deleting a test.
KNOWN_NOT_VORONOI: dict[str, str] = {}


def _voronoi_marks(name: str):
    reason = KNOWN_NOT_VORONOI.get(name)
    return [pytest.mark.xfail(reason=reason, strict=True)] if reason else []


VORONOI_CASES = [pytest.param(*f, marks=_voronoi_marks(f[0])) for f in FIXTURES]


def _fracture_cells(name: str, seed: int, shards: int):
    args = parse(
        [
            "--input", str(REPO_ROOT / "fixtures" / "meshes" / f"{name}.glb"),
            "--out", "/tmp/unused",
            "--seed", str(seed),
            "--shards", str(shards),
        ]
    )
    blender.load_input(args.input)
    obj = blender.mesh_objects()[0]
    blender.apply_transforms(obj)
    source_vertices, source_triangles = blender.read_mesh(obj)
    bbox = aabb_of(source_vertices)
    rng = Pcg32(seed=mix(args.seed, stable_hash(obj.name)))
    sites = _generate_sites(args, rng, bbox, source_vertices, source_triangles)
    cells = _build_cells(obj, sites, bbox, source_vertices, source_triangles)
    return sites, cells, mesh_volume(source_vertices, source_triangles)


@pytest.mark.parametrize(("name", "seed", "shards"), FIXTURES)
def test_every_cell_is_bounded(name: str, seed: int, shards: int) -> None:
    """A cell is a finite closed solid.

    This is what clipping the *source* rather than an unbounded region buys: the source is
    bounded, so no cell can escape it. An implementation that built the cell from half-spaces
    alone would have unbounded outer cells and would need a separate clamping step.
    """
    _, cells, _ = _fracture_cells(name, seed, shards)
    live = [c for c in cells if c is not None]
    assert live, "fracture produced no cells"
    for verts, _tris in live:
        box = aabb_of(verts)
        assert all(abs(c) < 1e3 for c in box.min + box.max)


@pytest.mark.parametrize(("name", "seed", "shards"), FIXTURES)
def test_cells_tile_the_source_volume(name: str, seed: int, shards: int) -> None:
    """The cells partition the source: their volumes sum to the source's.

    Tolerance is 0.5%, well above the volume the cell margin costs and well below the 2% of
    MASS_TOLERANCE_FRAC — so this fails before mass conservation (G7) does, and points at the
    fracture rather than at the mass stage.
    """
    _, cells, source_volume = _fracture_cells(name, seed, shards)
    total = sum(mesh_volume(v, t) for v, t in (c for c in cells if c is not None))
    assert total == pytest.approx(source_volume, rel=0.005)


@pytest.mark.parametrize(("name", "seed", "shards"), VORONOI_CASES)
def test_interior_points_are_nearest_their_own_site(name: str, seed: int, shards: int) -> None:
    """The defining property: every point of cell i is closer to site i than to any other.

    Sampled rather than proven, by pulling each cell's vertices toward its centroid so the
    probe points are strictly interior. A cell that was clipped by the wrong half-space, or by
    a bisector against the wrong site, fails here immediately — and fails nothing else, since
    such a cell still has positive volume, a plausible mass, and tiles with its neighbours.
    That is exactly why the property is worth asserting separately: the cube passes it
    exactly, the sphere does not, and no other check in the tool or the harness can tell.
    """
    sites, cells, _ = _fracture_cells(name, seed, shards)
    rng = random.Random(7)
    violations = samples = 0

    for index, cell in enumerate(cells):
        if cell is None:
            continue
        verts, tris = cell
        centroid = mesh_centroid(verts, tris)
        for _ in range(150):
            anchor = verts[rng.randrange(len(verts))]
            t = rng.uniform(0.15, 0.85)
            point = tuple(centroid[k] + (anchor[k] - centroid[k]) * t for k in range(3))
            nearest = min(range(len(sites)), key=lambda j: distance(point, sites[j]))
            samples += 1
            violations += nearest != index

    assert samples > 0
    assert violations == 0, f"{violations}/{samples} interior points had a nearer foreign site"
