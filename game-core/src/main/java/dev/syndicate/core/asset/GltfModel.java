/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A parsed glTF document: its geometry in scene space, its materials, and where its images live
 * (docs/08_asset_pipeline.md#D08-S4.5).
 *
 * <p>Deliberately not a libGDX {@code Model} or {@code Mesh}. Those are GPU buffers and need a GL
 * context; this is CPU-side data, so one parse serves the dedicated server, the verification harness
 * and the client alike (G17, D02-R9). It is the same reasoning DEC-008 used for the harness's
 * reader, applied to the module that has to be headless rather than the one that chooses to be.
 *
 * <p>The full node hierarchy is retained — names and parents for every node, geometry-bearing or not
 * — because D08-R13 addresses geometry by node name and a collision source may be a parent whose
 * children are its convex pieces (D08-R3).
 */
public final class GltfModel {

    private final Path source;
    private final String generator;
    private final List<GltfMeshNode> meshNodes;
    private final List<GltfMaterial> materials;
    private final List<GltfImage> images;
    private final String[] nodeNames;
    private final int[] nodeParents;

    GltfModel(
            Path source,
            String generator,
            List<GltfMeshNode> meshNodes,
            List<GltfMaterial> materials,
            List<GltfImage> images,
            String[] nodeNames,
            int[] nodeParents) {
        this.source = source;
        this.generator = generator;
        this.meshNodes = List.copyOf(meshNodes);
        this.materials = List.copyOf(materials);
        this.images = List.copyOf(images);
        this.nodeNames = nodeNames;
        this.nodeParents = nodeParents;
    }

    /** The file this was read from. */
    public Path source() {
        return source;
    }

    /** The {@code asset.generator} string, or {@code "unknown"}. */
    public String generator() {
        return generator;
    }

    /** Every geometry-bearing node, in scene traversal order (G3: the same order every read). */
    public List<GltfMeshNode> meshNodes() {
        return meshNodes;
    }

    /** Every material, index-aligned with {@link GltfPrimitive#materialIndex()}. */
    public List<GltfMaterial> materials() {
        return materials;
    }

    /** Every image reference, index-aligned with {@link GltfMaterial#baseColorImageIndex()}. */
    public List<GltfImage> images() {
        return images;
    }

    /** How many nodes the document declares, geometry-bearing or not. */
    public int nodeCount() {
        return nodeNames.length;
    }

    /** The material a primitive draws with, substituting the default for {@code -1} (glTF §3.9.2). */
    public GltfMaterial materialFor(GltfPrimitive primitive) {
        int index = primitive.materialIndex();
        return index >= 0 && index < materials.size() ? materials.get(index) : GltfMaterial.defaultMaterial();
    }

    /** The first geometry-bearing node with this name. */
    public Optional<GltfMeshNode> meshNode(String name) {
        for (GltfMeshNode node : meshNodes) {
            if (node.name().equals(name)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /** True when the document declares a node of this name, whether or not it carries geometry. */
    public boolean hasNode(String name) {
        for (String candidate : nodeNames) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every geometry-bearing node at or beneath the named node, in traversal order.
     *
     * <p>A collision source names one node and D08-R3 allows that node to be a parent whose children
     * are its convex pieces, so "the geometry of {@code chassis_01_col}" is a subtree rather than a
     * node. An empty list means the name matched nothing.
     */
    public List<GltfMeshNode> meshNodesUnder(String name) {
        List<GltfMeshNode> found = new ArrayList<>();
        for (GltfMeshNode node : meshNodes) {
            if (isAtOrUnder(node.nodeIndex(), name)) {
                found.add(node);
            }
        }
        return found;
    }

    private boolean isAtOrUnder(int nodeIndex, String name) {
        for (int index = nodeIndex; index >= 0; index = nodeParents[index]) {
            if (nodeNames[index].equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Total vertices across every primitive of every node. */
    public int vertexCount() {
        int total = 0;
        for (GltfMeshNode node : meshNodes) {
            total += node.vertexCount();
        }
        return total;
    }

    /** Total triangles across every primitive of every node. */
    public int triangleCount() {
        int total = 0;
        for (GltfMeshNode node : meshNodes) {
            total += node.triangleCount();
        }
        return total;
    }

    /**
     * The scene-space axis-aligned bounds of every primitive, written into {@code outMin} and
     * {@code outMax}.
     *
     * @return false when the document holds no geometry, in which case the outputs are untouched
     */
    public boolean bounds(Vector3 outMin, Vector3 outMax) {
        if (meshNodes.isEmpty()) {
            return false;
        }
        outMin.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        outMax.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        boolean any = false;
        for (GltfMeshNode node : meshNodes) {
            for (GltfPrimitive primitive : node.primitives()) {
                float[] positions = primitive.positions();
                for (int i = 0; i < positions.length; i += 3) {
                    any = true;
                    outMin.x = Math.min(outMin.x, positions[i]);
                    outMin.y = Math.min(outMin.y, positions[i + 1]);
                    outMin.z = Math.min(outMin.z, positions[i + 2]);
                    outMax.x = Math.max(outMax.x, positions[i]);
                    outMax.y = Math.max(outMax.y, positions[i + 1]);
                    outMax.z = Math.max(outMax.z, positions[i + 2]);
                }
            }
        }
        return any;
    }

    /** Every position in the document, concatenated — the point set a convex hull is built from. */
    public float[] allPositions() {
        return positionsOf(meshNodes);
    }

    /** Every position at or beneath the named node, concatenated. Empty when the name matches nothing. */
    public float[] positionsUnder(String name) {
        return positionsOf(meshNodesUnder(name));
    }

    private static float[] positionsOf(List<GltfMeshNode> nodes) {
        int floats = 0;
        for (GltfMeshNode node : nodes) {
            for (GltfPrimitive primitive : node.primitives()) {
                floats += primitive.positions().length;
            }
        }
        float[] out = new float[floats];
        int cursor = 0;
        for (GltfMeshNode node : nodes) {
            for (GltfPrimitive primitive : node.primitives()) {
                float[] positions = primitive.positions();
                System.arraycopy(positions, 0, out, cursor, positions.length);
                cursor += positions.length;
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "GltfModel[" + source.getFileName() + ", " + meshNodes.size() + " mesh nodes, " + vertexCount()
                + " vertices, " + triangleCount() + " triangles]";
    }
}
