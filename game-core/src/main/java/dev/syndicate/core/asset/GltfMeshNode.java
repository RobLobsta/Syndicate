/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Matrix4;
import java.util.List;

/**
 * One node of the glTF scene graph that carries geometry
 * (docs/08_asset_pipeline.md#D08-S4.5, D08-R13).
 *
 * <p>D08-R13 addresses geometry by node name — the visual mesh is the node named after the part, the
 * collision source is {@code <partTypeId>_col}, and a shard is {@code shard_<nnn>}. So the node, not
 * the mesh, is the unit the rest of the game names, and this is what a lookup returns.
 *
 * @param name the node's name, or {@code "node_<index>"} when it declares none
 * @param nodeIndex the node's index in the document, which is also its lookup key for ancestry
 * @param worldTransform the composed transform from the scene root down to this node, already
 *     applied to every primitive's positions and normals — kept because a caller that wants to know
 *     where a part sits (a slot empty, a wheel) needs the transform rather than the geometry
 * @param primitives the node's triangles, one entry per material
 */
public record GltfMeshNode(String name, int nodeIndex, Matrix4 worldTransform, List<GltfPrimitive> primitives) {

    /** Total vertices across every primitive. */
    public int vertexCount() {
        int total = 0;
        for (GltfPrimitive primitive : primitives) {
            total += primitive.vertexCount();
        }
        return total;
    }

    /** Total triangles across every primitive. */
    public int triangleCount() {
        int total = 0;
        for (GltfPrimitive primitive : primitives) {
            total += primitive.triangleCount();
        }
        return total;
    }
}
