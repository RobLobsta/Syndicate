"""Stage 5: collision hull generation (D09-S5.5).

Hulls are generated and validated here, validated again by the harness inside Bullet
(D14 ASSET-010/011/012), and deliberately *not* stored in the manifest as geometry
(D09-R14) — the game rebuilds them from the shard meshes with the same budget. Storing them
would create a third representation to keep in sync with the other two. The manifest records
only the counts, which is enough to detect a mismatch.
"""

from __future__ import annotations

from dataclasses import dataclass

from .errors import EXIT_HULL_FAILED, ToolError
from .geometry import (
    Tri,
    Vec3,
    convex_hull,
    inflate_hull,
    max_outside_distance,
    mesh_volume,
    simplify_hull,
)

# D14-S6.4. Simplification is allowed to shave this much off the source; beyond it, the hull
# no longer encloses the mesh and shards visibly clip through each other.
HULL_ENCLOSE_M = 0.002


@dataclass
class Hull:
    """One generated hull, kept only long enough to be verified and counted."""

    name: str
    vertices: list[Vec3]
    triangles: list[Tri]
    budget: int

    @property
    def vertex_count(self) -> int:
        return len(self.vertices)

    @property
    def volume_m3(self) -> float:
        return mesh_volume(self.vertices, self.triangles)


def build_hull(name: str, source_vertices: list[Vec3], max_verts: int) -> Hull:
    """Hull, simplify to budget, validate (D09-S5.5). Raises ``ToolError(71)`` on failure."""
    points, triangles = convex_hull(source_vertices)
    if not triangles:
        raise ToolError(
            EXIT_HULL_FAILED,
            f"degenerate hull for '{name}': the source is coplanar or has fewer than 4 points",
            source=name,
            sourceVertexCount=len(source_vertices),
        )

    if len(points) > max_verts:
        points, triangles = simplify_hull(points, triangles, max_verts)
        if not triangles:
            raise ToolError(
                EXIT_HULL_FAILED,
                f"hull simplification collapsed the hull for '{name}'",
                source=name,
                budget=max_verts,
            )

        # Re-inflate to restore enclosure. Simplification always cuts corners off a convex
        # hull, so on a curved source the result is guaranteed to sit inside the mesh; the
        # inflate step puts it back outside by construction rather than leaving the
        # enclosure check to discover the shortfall (D09-S5.5).
        shortfall = max_outside_distance(points, triangles, source_vertices)
        if shortfall > 0.0:
            inflated_points, inflated_triangles = inflate_hull(points, triangles, shortfall * 1.05)
            if inflated_triangles and len(inflated_points) <= max_verts:
                points, triangles = inflated_points, inflated_triangles

    hull = Hull(name=name, vertices=points, triangles=triangles, budget=max_verts)
    validate_hull(hull, source_vertices)
    return hull


def validate_hull(hull: Hull, source_vertices: list[Vec3]) -> None:
    """The four conditions of D09-S5.5's ``validateHull``."""
    if hull.vertex_count < 4:
        raise ToolError(
            EXIT_HULL_FAILED,
            f"degenerate hull for '{hull.name}': {hull.vertex_count} vertices",
            source=hull.name,
            vertexCount=hull.vertex_count,
        )
    if hull.volume_m3 <= 0.0:
        raise ToolError(
            EXIT_HULL_FAILED,
            f"zero-volume hull for '{hull.name}'",
            source=hull.name,
            volumeM3=hull.volume_m3,
        )
    if hull.vertex_count > hull.budget:
        raise ToolError(
            EXIT_HULL_FAILED,
            f"hull for '{hull.name}' exceeds the {hull.budget}-vertex budget "
            f"with {hull.vertex_count}",
            source=hull.name,
            vertexCount=hull.vertex_count,
            budget=hull.budget,
        )

    outside = max_outside_distance(hull.vertices, hull.triangles, source_vertices)
    if outside > HULL_ENCLOSE_M:
        raise ToolError(
            EXIT_HULL_FAILED,
            f"hull for '{hull.name}' does not enclose its source: worst vertex is "
            f"{outside:.5f} m outside (limit {HULL_ENCLOSE_M} m)",
            source=hull.name,
            worstOutsideM=outside,
            toleranceM=HULL_ENCLOSE_M,
        )
