"""Convex decomposition of a non-convex source (D09-S5.2, DEV-004).

Pure Python, no Blender: the decomposition and the exact cell construction that consumes it
never touch ``bpy``, and CI runs only this suite (the in-Blender suite needs a host CI does
not have). So this is where the DEV-004 regression guard has to live.

Geometry is built inline rather than imported from ``fixtures``, so every expected value here
is analytic — a hollow 1 m box with 0.05 m walls holds 1 - 0.9^3 = 0.271 m3 of material, and
a test that measured the fixture generator instead would agree with itself about a wrong
number.
"""

from __future__ import annotations

import pytest

from syndicate_fracture.decompose import convex_decomposition
from syndicate_fracture.fracture import _build_cells, _inside_predicate
from syndicate_fracture.geometry import (
    Tri,
    Vec3,
    aabb_of,
    distance,
    dot,
    mesh_centroid,
    mesh_volume,
)
from syndicate_fracture.rng import Pcg32
from syndicate_fracture.sites import uniform_sites

# --- Geometry builders -------------------------------------------------------------------


def _box_faces(lo: Vec3, hi: Vec3) -> tuple[list[Vec3], list[list[int]]]:
    """An axis-aligned box with outward-wound faces."""
    vertices: list[Vec3] = [
        (lo[0], lo[1], lo[2]),
        (hi[0], lo[1], lo[2]),
        (hi[0], hi[1], lo[2]),
        (lo[0], hi[1], lo[2]),
        (lo[0], lo[1], hi[2]),
        (hi[0], lo[1], hi[2]),
        (hi[0], hi[1], hi[2]),
        (lo[0], hi[1], hi[2]),
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


def _triangulate(vertices: list[Vec3], faces: list[list[int]]) -> tuple[list[Vec3], list[Tri]]:
    triangles: list[Tri] = []
    for face in faces:
        for i in range(1, len(face) - 1):
            triangles.append((face[0], face[i], face[i + 1]))
    return vertices, triangles


def _combine(*shells: tuple[list[Vec3], list[list[int]]]) -> tuple[list[Vec3], list[list[int]]]:
    vertices: list[Vec3] = []
    faces: list[list[int]] = []
    for shell_vertices, shell_faces in shells:
        offset = len(vertices)
        vertices.extend(shell_vertices)
        faces.extend([index + offset for index in face] for face in shell_faces)
    return vertices, faces


def _flip(shell: tuple[list[Vec3], list[list[int]]]) -> tuple[list[Vec3], list[list[int]]]:
    vertices, faces = shell
    return vertices, [list(reversed(face)) for face in faces]


def _hollow_box(wall: float = 0.05) -> tuple[list[Vec3], list[Tri]]:
    """A 1 m box with a 0.9 m cubic cavity: 0.271 m3 of material."""
    outer = _box_faces((-0.5, -0.5, 0.0), (0.5, 0.5, 1.0))
    inner = _box_faces((-0.45, -0.45, wall), (0.45, 0.45, 1.0 - wall))
    return _triangulate(*_combine(outer, _flip(inner)))


def _ribbed_hollow_box(wall: float = 0.05) -> tuple[list[Vec3], list[Tri]]:
    """A hollow box with an internal rib splitting the cavity in two: 0.3115 m3.

    Harder than the plain shell in the way that matters — the cavity is two disjoint regions
    rather than one, so a decomposition that happens to work by treating "the cavity" as a
    single subtracted box gets this wrong. It is also the geometry D14-S7.1 actually
    describes for ``test_complex_hollow``; the checked-in fixture has no rib (DEV-006).
    """
    outer = _box_faces((-0.5, -0.5, 0.0), (0.5, 0.5, 1.0))
    left = _box_faces((-0.45, -0.45, wall), (-wall / 2.0, 0.45, 1.0 - wall))
    right = _box_faces((wall / 2.0, -0.45, wall), (0.45, 0.45, 1.0 - wall))
    return _triangulate(*_combine(outer, _flip(left), _flip(right)))


def _l_shape() -> tuple[list[Vec3], list[Tri]]:
    """Two unit blocks meeting along a face, one reflex edge: 2 m3."""
    return _triangulate(
        *_combine(
            _box_faces((0.0, 0.0, 0.0), (2.0, 1.0, 1.0)),
            _box_faces((0.0, 0.0, 1.0), (1.0, 1.0, 2.0)),
        )
    )


def _blocks_sharing_a_plane() -> tuple[list[Vec3], list[Tri]]:
    """Two blocks whose only common plane is x = 0, with material on opposite sides of it.

    The case that says whether coplanar faces may simply be discarded when a split plane is
    chosen: face-on-x=0 of the left block points +x, that of the right block points -x, and
    dropping the second would classify the whole right-hand region as empty.
    """
    return _triangulate(
        *_combine(
            _box_faces((-1.0, 0.0, 0.0), (0.0, 1.0, 1.0)),
            _box_faces((0.0, 2.0, 0.0), (1.0, 3.0, 1.0)),
        )
    )


def _open_box() -> tuple[list[Vec3], list[Tri]]:
    """A unit box with its top face missing — not a solid, and not decomposable."""
    vertices, faces = _box_faces((0.0, 0.0, 0.0), (1.0, 1.0, 1.0))
    return _triangulate(vertices, [face for face in faces if face != [4, 5, 6, 7]])


def _in_piece(point: Vec3, piece, slack: float = 1e-9) -> bool:
    return all(dot(normal, point) - offset <= slack for normal, offset in piece.planes)


# --- The decomposition itself --------------------------------------------------------------


def test_convex_source_decomposes_to_one_piece() -> None:
    """A convex source is the one-piece case, so the decomposition generalises the old path.

    Without the convex early-out the BSP descends once per face plane and returns a stack of
    slabs — correct, but it would multiply the fracture stage's cost by their number.
    """
    vertices, triangles = _triangulate(*_box_faces((-0.5, -0.5, 0.0), (0.5, 0.5, 1.0)))
    result = convex_decomposition(vertices, triangles)

    assert result.ok, result.reason
    assert len(result.pieces) == 1
    assert result.pieces[0].volume_m3 == pytest.approx(1.0, rel=1e-12)


def test_hollow_box_decomposes_to_its_wall_volume() -> None:
    """DEV-004's fixture: 0.271 m3 of wall, not the 1 m3 of the bounding box."""
    vertices, triangles = _hollow_box()
    assert mesh_volume(vertices, triangles) == pytest.approx(0.271, rel=1e-12)

    result = convex_decomposition(vertices, triangles)

    assert result.ok, result.reason
    assert result.volume_m3 == pytest.approx(0.271, rel=1e-9)
    # Six walls is the minimum a rectangular shell can decompose into, and the BSP finds it.
    assert len(result.pieces) == 6


def test_hollow_box_pieces_cover_the_walls_and_not_the_cavity() -> None:
    """Exactly one piece contains each material point, and none contains a cavity point.

    Volume alone cannot distinguish a correct decomposition from one that overlaps in one
    place and leaves a gap of the same size in another. Membership can.
    """
    vertices, triangles = _hollow_box()
    result = convex_decomposition(vertices, triangles)
    assert result.ok, result.reason

    # Membership is decided analytically — inside the outer box and outside the inner one —
    # rather than with `_inside_predicate`. Ray parity double-counts a ray that grazes the
    # edge two triangles share (DISC-006), which a regular sample grid hits often enough to
    # matter, and ground truth for this test has to be something other than the tool.
    def is_material(p: Vec3) -> bool:
        outer = all(-0.5 < p[a] < 0.5 for a in (0, 1)) and 0.0 < p[2] < 1.0
        inner = all(-0.45 < p[a] < 0.45 for a in (0, 1)) and 0.05 < p[2] < 0.95
        return outer and not inner

    steps = 13
    material = cavity = 0
    for i in range(steps):
        for j in range(steps):
            for k in range(steps):
                point = (
                    -0.5 + (i + 0.5) / steps,
                    -0.5 + (j + 0.5) / steps,
                    (k + 0.5) / steps,
                )
                containing = sum(1 for piece in result.pieces if _in_piece(point, piece))
                if is_material(point):
                    material += 1
                    assert containing == 1, f"{point} is in {containing} pieces, expected 1"
                else:
                    cavity += 1
                    assert containing == 0, f"cavity point {point} is in {containing} pieces"
    assert material > 0 and cavity > 0


def test_two_disjoint_cavities_decompose_exactly() -> None:
    """A shell whose cavity is in two parts, which is the geometry D14-S7.1 asks for."""
    vertices, triangles = _ribbed_hollow_box()
    expected = 1.0 - 2 * (0.425 * 0.9 * 0.9)
    assert mesh_volume(vertices, triangles) == pytest.approx(expected, rel=1e-12)

    result = convex_decomposition(vertices, triangles)

    assert result.ok, result.reason
    assert result.volume_m3 == pytest.approx(expected, rel=1e-9)


def test_reflex_source_decomposes_exactly() -> None:
    vertices, triangles = _l_shape()
    result = convex_decomposition(vertices, triangles)

    assert result.ok, result.reason
    assert result.volume_m3 == pytest.approx(3.0, rel=1e-9)
    assert all(piece.volume_m3 > 0.0 for piece in result.pieces)


def test_blocks_sharing_a_plane_keep_both_sides() -> None:
    """Both blocks survive, which they do not if coplanar faces are discarded blindly."""
    vertices, triangles = _blocks_sharing_a_plane()
    result = convex_decomposition(vertices, triangles)

    assert result.ok, result.reason
    assert result.volume_m3 == pytest.approx(2.0, rel=1e-9)
    left = [p for p in result.pieces if p.aabb.max[0] <= 1e-9]
    right = [p for p in result.pieces if p.aabb.min[0] >= -1e-9]
    assert sum(p.volume_m3 for p in left) == pytest.approx(1.0, rel=1e-9)
    assert sum(p.volume_m3 for p in right) == pytest.approx(1.0, rel=1e-9)


def test_decomposition_is_deterministic() -> None:
    """G11: the same source gives the same pieces, in the same order, every time."""
    vertices, triangles = _hollow_box()
    first = convex_decomposition(vertices, triangles)
    second = convex_decomposition(vertices, triangles)

    assert [p.planes for p in first.pieces] == [p.planes for p in second.pieces]
    assert [p.vertices for p in first.pieces] == [p.vertices for p in second.pieces]


def test_piece_cap_is_reported_rather_than_raised() -> None:
    """Exceeding the budget is a reason, not an exception: the caller falls back and logs."""
    vertices, triangles = _hollow_box()
    result = convex_decomposition(vertices, triangles, max_pieces=2)

    assert not result.ok
    assert "2 pieces" in result.reason
    assert result.pieces == []


def test_a_source_that_is_not_a_closed_solid_is_rejected() -> None:
    """The volume check is what stands between a bad assumption and a plausible manifest.

    A BSP is exact only for a closed, consistently wound mesh, and nothing upstream proves
    the source is one — stage 1 only requires a positive volume. An open box decomposes into
    regions that do not sum to what the divergence theorem measured, and that disagreement is
    the whole point of checking.
    """
    vertices, triangles = _open_box()
    result = convex_decomposition(vertices, triangles)

    assert not result.ok
    assert result.reason


# --- The cells built from the pieces -------------------------------------------------------


def _hollow_cells(shards: int = 14, seed: int = 1004):
    """Fracture the hollow box exactly as ``voronoi_fracture`` would, minus the Blender parts.

    ``_build_cells`` reaches ``bpy`` only on the mesh-cutting fallback, so the path under test
    here — decomposition, then one exact polytope intersection per (site, piece) pair — runs
    with no Blender host at all. Cells are returned in site order, ``None`` included, because
    the Voronoi property is about which site a cell belongs to.
    """
    vertices, triangles = _hollow_box()
    bbox = aabb_of(vertices)
    sites = uniform_sites(shards, bbox, Pcg32(seed=seed), _inside_predicate(vertices, triangles))
    cells = _build_cells(None, sites, bbox, vertices, triangles)
    return sites, cells, mesh_volume(vertices, triangles)


def test_cells_of_a_hollow_box_tile_the_wall_volume() -> None:
    """DEV-004 itself: the shard volumes summed to 6.4x the part's, and now they do not.

    Tolerance is the 0.5% of the in-Blender tiling test, which is above what the cell margin
    costs and well below the 2% of MASS_TOLERANCE_FRAC — so a regression fails here, pointing
    at the fracture, rather than downstream as an inexplicable mass error.
    """
    _, cells, source_volume = _hollow_cells()

    live = [cell for cell in cells if cell is not None]
    assert len(live) >= 2
    total = sum(mesh_volume(v, t) for v, t in live)
    assert total == pytest.approx(source_volume, rel=0.005)


def test_cells_of_a_hollow_box_are_voronoi_cells() -> None:
    """Every interior point of a cell is nearer its own site than any other (D09-S5.2).

    A cell is a convex region even when the shard it carves out of the source is not, so
    probing along the segment from a shard vertex to the shard's centroid stays inside that
    region — including where the segment crosses the cavity, which is exactly the part a
    mesh-cutting implementation gets wrong.
    """
    sites, cells, _ = _hollow_cells()
    probe = Pcg32(seed=7)
    violations = samples = 0

    for index, cell in enumerate(cells):
        if cell is None:
            continue
        verts, tris = cell
        centroid = mesh_centroid(verts, tris)
        for _ in range(150):
            anchor = verts[probe.next_int(len(verts))]
            t = 0.15 + 0.7 * probe.next_float()
            point = tuple(centroid[k] + (anchor[k] - centroid[k]) * t for k in range(3))
            nearest = min(range(len(sites)), key=lambda j: distance(point, sites[j]))
            samples += 1
            violations += nearest != index

    assert samples > 0
    assert violations == 0, f"{violations}/{samples} interior points had a nearer foreign site"
