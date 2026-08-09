/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.asset;

import dev.syndicate.core.asset.GltfException;
import dev.syndicate.core.asset.GltfMeshNode;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfOptions;
import dev.syndicate.core.asset.GltfPrimitive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads node names, vertex positions, and triangle indices out of a glTF file, as the harness's
 * {@link MeshData} (docs/14_test_environment.md#D14-S5.3).
 *
 * <p><b>An adapter, not a parser.</b> It was a parser: DEC-008 gave the harness its own ~200-line
 * GLB reader because the alternative, gdx-gltf, builds libGDX {@code Mesh} objects and those need a
 * GL context that D14-S5.13 forbids the headless runner from creating. That reasoning still holds —
 * and {@code game-core} now has a headless reader of its own (DEV-010), so the harness can take that
 * one instead of keeping a second implementation of the same file format.
 *
 * <p>Two things the old reader got wrong come with the change. It ignored node transforms, which was
 * harmless only because every fixture the Blender tool writes has an identity transform; and it read
 * {@code .glb} alone, so a {@code .gltf} beside its {@code .bin} — the form downloaded art arrives
 * in — was simply unreadable.
 *
 * <p>What is deliberately still true: the harness's volume and centroid maths in {@link MeshData}
 * remain its own, because D09-S6.2 makes agreement between two independent implementations the
 * evidence that both are right. Sharing a *parser* costs nothing there; sharing the *measurement*
 * would remove the check.
 */
public final class GlbReader {

    private GlbReader() {
        throw new AssertionError("no instances");
    }

    /**
     * Every mesh node in the file, in the file's own node order.
     *
     * <p>Node order is the exporter's, so callers that need a stable order sort by name. The
     * manifest's shard array is the authority on ordering (D09-R8); this is just what is present.
     *
     * @throws AssetLoadException when the file is not readable glTF, or contains no usable mesh
     */
    public static List<MeshData> read(Path path) {
        GltfModel model;
        try {
            model = dev.syndicate.core.asset.GltfReader.read(path, GltfOptions.GEOMETRY);
        } catch (GltfException | UncheckedIOException e) {
            throw new AssetLoadException(e.getMessage() == null ? "cannot read " + path : e.getMessage(), e);
        }

        List<MeshData> out = new ArrayList<>();
        for (GltfMeshNode node : model.meshNodes()) {
            // Primitives of one mesh are concatenated: they are one renderable object, and a shard
            // split across two materials is still one rigid body.
            int vertexFloats = 0;
            int indexCount = 0;
            for (GltfPrimitive primitive : node.primitives()) {
                vertexFloats += primitive.positions().length;
                indexCount += primitive.indices().length;
            }
            float[] positions = new float[vertexFloats];
            int[] indices = new int[indexCount];
            int vertexCursor = 0;
            int indexCursor = 0;
            for (GltfPrimitive primitive : node.primitives()) {
                float[] source = primitive.positions();
                System.arraycopy(source, 0, positions, vertexCursor, source.length);
                int base = vertexCursor / 3;
                for (int index : primitive.indices()) {
                    indices[indexCursor++] = index + base;
                }
                vertexCursor += source.length;
            }
            if (positions.length == 0 || indices.length == 0) {
                continue;
            }
            out.add(new MeshData(node.name(), positions, indices));
        }
        if (out.isEmpty()) {
            throw new AssetLoadException("glTF contains no usable mesh: " + path);
        }
        return out;
    }

    /** A glTF file that cannot be parsed, or holds no mesh. Maps to exit 22 (D14-S4.2). */
    public static final class AssetLoadException extends UncheckedIOException {
        public AssetLoadException(String message) {
            this(message, new IOException(message));
        }

        public AssetLoadException(String message, Throwable cause) {
            super(new IOException(message, cause));
        }
    }
}
