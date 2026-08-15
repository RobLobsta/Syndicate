/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.AssetPaths;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.mgsx.gltf.loaders.glb.GLBLoader;
import net.mgsx.gltf.scene3d.scene.SceneAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One loaded {@code mesh.glb} per part type, shared by every instance of it
 * (docs/08_asset_pipeline.md#D08-S4.2, docs/03_runtime_modes.md#D03-S5.5).
 *
 * <p>Eight cars in a match are eight copies of two chassis and four wheels. A {@link SceneAsset}
 * holds the meshes and textures — the expensive part, and the part that is identical between
 * instances — while a {@link ModelInstance} holds only the node transforms and material overrides.
 * Loading per entity instead would put the Eclipse's 19 MB of embedded texture on the GPU once per
 * car.
 *
 * <p><b>Collision nodes are removed from the instance, not from the asset.</b> A part's
 * {@code mesh.glb} carries both the visual mesh and the {@code _col} hull the physics loader reads
 * (D08-S4.2's {@code collisionSource}), and drawing the hull draws a grey box over the car. The
 * asset keeps them because {@code GltfCollisionMeshSource} reads the same file through
 * {@code game-core}'s own reader and neither path should be editing the other's data.
 *
 * <p><b>Owner of every {@link SceneAsset} it returns</b> (G19). {@link #dispose()} releases them;
 * a {@link ModelInstance} handed out here owns nothing and needs no disposal.
 */
public final class PartModels implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(PartModels.class);

    /** The suffix D08-S4.2 gives a collision node, which is never drawn. */
    public static final String COLLISION_NODE_SUFFIX = "_col";

    /** The file a part directory holds its geometry in (D08-S4.2). */
    public static final String MESH_FILE = "mesh.glb";

    private final Path assetRoot;
    private final Map<String, SceneAsset> assets = new LinkedHashMap<>();
    private final Set<String> missing = new HashSet<>();

    public PartModels(Path assetRoot) {
        this.assetRoot = Objects.requireNonNull(assetRoot, "assetRoot");
    }

    /**
     * A fresh drawable instance of a part type, or null when the part has no readable mesh.
     *
     * <p>Null rather than a thrown exception or a placeholder cube: G18 degrades on a content
     * problem, and a part that cannot be drawn is still simulated, still takes damage and still
     * comes off. The reason is logged once per part type rather than once per instance.
     */
    public ModelInstance instanceOf(AssetId partTypeId) {
        if (partTypeId == null) {
            return null;
        }
        SceneAsset asset = load(partTypeId.value());
        if (asset == null || asset.scene == null) {
            return null;
        }
        ModelInstance instance = new ModelInstance(asset.scene.model);
        removeCollisionNodes(instance);
        instance.calculateTransforms();
        return instance;
    }

    /** How many distinct part meshes are resident. */
    public int loadedCount() {
        return assets.size();
    }

    private SceneAsset load(String partTypeId) {
        SceneAsset cached = assets.get(partTypeId);
        if (cached != null || missing.contains(partTypeId)) {
            return cached;
        }
        // A part lives either in the shared library or under the vehicle that owns it
        // (D08-R14b); the resolver knows both and is the one place that does.
        Path partDirectory = AssetPaths.partDirectory(assetRoot, partTypeId);
        FileHandle handle = partDirectory == null
                ? null
                : new FileHandle(partDirectory.resolve(MESH_FILE).toFile());
        if (handle == null || !handle.exists()) {
            LOG.warn("part {} has no {}; it will simulate but not draw (D08-S4.2)", partTypeId, MESH_FILE);
            missing.add(partTypeId);
            return null;
        }
        try {
            SceneAsset asset = new GLBLoader().load(handle);
            assets.put(partTypeId, asset);
            LOG.info("loaded render mesh for {} ({} meshes)", partTypeId, asset.meshes.size);
            return asset;
        } catch (RuntimeException e) {
            // G18 again: one unreadable model must not take the client down with it.
            LOG.error("part {} has an unreadable {}; it will not draw", partTypeId, MESH_FILE, e);
            missing.add(partTypeId);
            return null;
        }
    }

    /** Drops the {@code _col} hull from an instance, at every depth. */
    private static void removeCollisionNodes(ModelInstance instance) {
        for (int i = instance.nodes.size - 1; i >= 0; i--) {
            Node node = instance.nodes.get(i);
            if (node.id != null && node.id.endsWith(COLLISION_NODE_SUFFIX)) {
                instance.nodes.removeIndex(i);
            } else {
                removeCollisionChildren(node);
            }
        }
    }

    private static void removeCollisionChildren(Node node) {
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            Node child = node.getChild(i);
            if (child.id != null && child.id.endsWith(COLLISION_NODE_SUFFIX)) {
                node.removeChild(child);
            } else {
                removeCollisionChildren(child);
            }
        }
    }

    @Override
    public void dispose() {
        assets.values().forEach(SceneAsset::dispose);
        assets.clear();
        missing.clear();
    }
}
