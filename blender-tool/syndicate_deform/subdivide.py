"""Vertex density for the dent to land in (D15-S5.7).

Moved out of ``syndicate_prepare.exporter``, where it was one orchestrator's private helper, into
the tool whose transform it exists to serve. Nothing else in the suite subdivides, and nothing
else has a reason to: the target edge length is a property of the destruction class, and the
class's other property — whether it deforms at all — decides whether this is called.

The two numbers differ by an order of magnitude between classes and that is deliberate rather than
taste. A panel crumples locally and keeps its area, so it needs density where the dent is or the
dent is a facet. A chassis buckles and shears globally, and fine subdivision makes it squish like a
sponge, which is the failure mode ``STRUCTURAL``'s coarse lattice exists to avoid.
"""

from __future__ import annotations

#: Face budget a subdivided part may not exceed. A door skin that arrives as four quads needs
#: several passes; a photoscanned bonnet that arrives at 30k faces needs none, and running the
#: passes anyway would produce a part nothing can draw at frame rate.
MAX_SUBDIVIDED_FACES = 40_000

#: Passes of edge splitting. Three halvings take a 1 m quad to 0.125 m, past both classes'
#: targets, so a fourth pass only ever costs faces.
MAX_SUBDIVISION_PASSES = 3


def subdivide_to(obj, target_edge_m: float) -> None:
    """Subdivide edges longer than ``target_edge_m``, bounded by :data:`MAX_SUBDIVIDED_FACES`."""
    import bmesh

    if target_edge_m <= 0.0:
        return
    for _pass in range(MAX_SUBDIVISION_PASSES):
        mesh = bmesh.new()
        mesh.from_mesh(obj.data)
        long_edges = [edge for edge in mesh.edges if edge.calc_length() > target_edge_m]
        if not long_edges or len(mesh.faces) >= MAX_SUBDIVIDED_FACES:
            mesh.free()
            return
        bmesh.ops.subdivide_edges(mesh, edges=long_edges, cuts=1, use_grid_fill=True)
        mesh.to_mesh(obj.data)
        mesh.free()
        obj.data.update()


__all__ = ["MAX_SUBDIVIDED_FACES", "MAX_SUBDIVISION_PASSES", "subdivide_to"]
