/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a part's shard geometry out of its {@code shards.glb}
 * (docs/08_asset_pipeline.md#D08-S5.3 step 2).
 *
 * <p>The sibling of {@link GltfCollisionMeshSource}, and the same seam for the same reason: the
 * geometry is binary, the loader is not allowed a GL context (G17), and a test wants to stand
 * boxes in place of art. The difference is arity — a part has one collision hull and D09-S4.4
 * gives it one node per shard, so this returns the whole file keyed by node name rather than
 * one mesh. The file is parsed once per part rather than once per shard, which for a
 * twenty-four-shard windscreen is the difference between one half-megabyte read and twenty-four.
 *
 * <p>Positions come back in <em>part</em> space, with node transforms applied — the same space
 * {@code GltfCollisionMeshSource} returns and the space the manifest's per-shard AABBs are quoted
 * in. Moving each shard onto its own origin is {@code AssetLoader}'s job, because it is the half
 * that needs the manifest's {@code localTransform} to do it (D09-S4.4).
 */
public final class GltfShardMeshSource implements AssetLoader.ShardMeshSource {

    private static final Logger LOG = LoggerFactory.getLogger(GltfShardMeshSource.class);

    /** The file D09-S4.4 puts a part's shards in, used when the part names no other. */
    public static final String DEFAULT_SHARD_FILE = "shards.glb";

    @Override
    public Map<String, MeshData> shardMeshesFor(AssetId partTypeId, String shardMeshRef, Path partDirectory) {
        String fileName = shardMeshRef == null || shardMeshRef.isBlank() ? DEFAULT_SHARD_FILE : shardMeshRef.trim();

        Path file = partDirectory.resolve(fileName).normalize();
        if (!file.startsWith(partDirectory.normalize())) {
            LOG.warn("{}: shard mesh \"{}\" escapes the part directory", partTypeId.value(), shardMeshRef);
            return Map.of();
        }
        if (!Files.isRegularFile(file)) {
            // Not logged at warn: a part that declares a manifest it has not been fractured for yet
            // is an ordinary state of a content directory, and the loader turns the empty map into
            // an A501 finding that names it once.
            LOG.debug("{}: no shard mesh at {}", partTypeId.value(), file);
            return Map.of();
        }

        try {
            GltfModel model = GltfReader.read(file, GltfOptions.GEOMETRY);
            // Sorted, then copied into insertion order, so two runs over the same file hand the
            // loader the same map in the same sequence (G3). The loader looks shards up by name
            // rather than iterating, but its findings are reported in whatever order it walks.
            Map<String, MeshData> byNode = new TreeMap<>();
            for (GltfMeshNode node : model.meshNodes()) {
                if (node.name() == null || node.name().isBlank()) {
                    continue;
                }
                float[] positions = model.positionsUnder(node.name());
                if (positions.length >= 12) {
                    byNode.put(node.name(), new MeshData(positions));
                }
            }
            return new LinkedHashMap<>(byNode);
        } catch (GltfException | IllegalArgumentException e) {
            LOG.warn("{}: cannot read shard geometry from {} — {}", partTypeId.value(), file, e.getMessage());
            return Map.of();
        }
    }
}
