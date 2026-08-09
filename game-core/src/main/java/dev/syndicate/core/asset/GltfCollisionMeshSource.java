/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a part's collision geometry out of its {@code mesh.glb}
 * (docs/08_asset_pipeline.md#D08-S5.3 step 2).
 *
 * <p>This is what {@link AssetLoader.CollisionMeshSource} was a seam for. DEV-010 records why the
 * loader could not do it inline — {@code game-core} had no glTF reader and cannot have gdx-gltf's —
 * and {@link GltfReader} is the reader that gap called for. With this in place the loader reads a
 * part's geometry from the same file it reads its mass from, which is what D08-S5.3 always said.
 *
 * <p><b>The reference format</b> is {@code <file>#node=<name>}, exactly the string D08-S5.3 passes
 * to {@code GltfLoader.loadNode}. Both halves are optional:
 *
 * <ul>
 *   <li>no reference at all, or no {@code #node=}: the whole file is the hull source
 *   <li>a node name that the file does not contain: the whole file is the hull source, with a
 *       warning — D08-R3 makes {@code <partTypeId>_col} optional and says the visual mesh is used
 *       when it is absent, so a missing node is a documented fallback rather than a failure
 * </ul>
 *
 * <p>Positions come back in scene space with node transforms applied, which is the space the part's
 * slots and its authored mass properties are in (D00-R16).
 */
public final class GltfCollisionMeshSource implements AssetLoader.CollisionMeshSource {

    private static final Logger LOG = LoggerFactory.getLogger(GltfCollisionMeshSource.class);

    /** The file D08-S4.6 puts a part's mesh in, used when the part names no other. */
    public static final String DEFAULT_MESH_FILE = "mesh.glb";

    private static final String NODE_FRAGMENT = "node=";

    @Override
    public MeshData meshFor(AssetId partTypeId, String collisionSourceRef, Path partDirectory) {
        String reference = collisionSourceRef == null || collisionSourceRef.isBlank()
                ? DEFAULT_MESH_FILE
                : collisionSourceRef.trim();

        int hash = reference.indexOf('#');
        String fileName = hash < 0 ? reference : reference.substring(0, hash);
        String fragment = hash < 0 ? "" : reference.substring(hash + 1);
        String nodeName = fragment.startsWith(NODE_FRAGMENT) ? fragment.substring(NODE_FRAGMENT.length()) : null;
        if (fileName.isBlank()) {
            fileName = DEFAULT_MESH_FILE;
        }

        Path file = partDirectory.resolve(fileName).normalize();
        if (!file.startsWith(partDirectory.normalize())) {
            LOG.warn("{}: collision source \"{}\" escapes the part directory", partTypeId.value(), reference);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            // Not a warning: a part with no model yet is the normal state of an unfinished asset
            // directory, and the loader turns the null into an A503 finding that names it once.
            LOG.debug("{}: no mesh at {}", partTypeId.value(), file);
            return null;
        }

        try {
            GltfModel model = GltfReader.read(file, GltfOptions.GEOMETRY);
            float[] positions = positionsFor(model, nodeName, partTypeId);
            if (positions.length < 12) {
                LOG.warn(
                        "{}: {} yielded {} vertices, too few to enclose a volume",
                        partTypeId.value(),
                        reference,
                        positions.length / 3);
                return null;
            }
            return new MeshData(positions);
        } catch (GltfException | IllegalArgumentException e) {
            LOG.warn("{}: cannot read collision geometry from {} — {}", partTypeId.value(), file, e.getMessage());
            return null;
        }
    }

    private static float[] positionsFor(GltfModel model, String nodeName, AssetId partTypeId) {
        if (nodeName == null || nodeName.isBlank()) {
            return model.allPositions();
        }
        float[] positions = model.positionsUnder(nodeName);
        if (positions.length > 0) {
            return positions;
        }
        LOG.warn(
                "{}: {} declares no node \"{}\"; falling back to the whole mesh as the hull source (D08-R3)",
                partTypeId.value(),
                model.source().getFileName(),
                nodeName);
        return model.allPositions();
    }
}
