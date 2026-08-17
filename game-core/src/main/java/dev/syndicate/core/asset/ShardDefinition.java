/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.util.Transform;
import java.util.Objects;

/**
 * One shard of a fracture manifest, as the runtime reads it
 * (docs/09_blender_destruction_tool.md#D09-S4.4, docs/07_damage_destruction_model.md#D07-S5.6).
 *
 * <p>{@link #massKg()} is <b>taken from the manifest, never recomputed</b> (D07-R19). The manifest
 * is validated at load (D08-S5.4) and independently re-derived by the harness (D14 ASSET-004/006),
 * so mass conservation (G7) is an asset-time guarantee. A runtime that recomputed shard mass from
 * geometry would be a second implementation of the tool's volume integration, free to disagree with
 * it in the one place where disagreement is unrecoverable.
 *
 * <p>Immutable. Shards are shared by every fracture of the same part type in a match, and the
 * vectors would otherwise be aliased into a body's transform and mutated by the caller.
 */
public final class ShardDefinition {

    private final String shardId;
    private final String meshNodeName;
    private final int index;
    private final float massKg;
    private final Vector3 centroidLocal = new Vector3();
    private final Transform localTransform = new Transform();
    private final MeshData hullMesh;

    /**
     * @param shardId the manifest's shard id; ties are broken by it so iteration is deterministic
     *     (G3)
     * @param meshNodeName the manifest's {@code name} — the node this shard's geometry sits on in
     *     {@code shards.glb} (D09-S4.4, A501). Null falls back to {@code shardId}
     * @param index the shard's index within the manifest, which is also its {@code ShapeCacheKey}
     *     index
     * @param massKg from the manifest, {@code > MIN_BODY_MASS_KG} for a spawnable shard
     * @param centroidLocal the shard's centroid in the <em>part's</em> local space; its direction
     *     from the origin is the outward scatter direction of D07-S5.6
     * @param localTransform where the shard sits within the intact part
     * @param hullMesh the shard's collision mesh in the shard's <em>own</em> space, decimated
     *     further than a part's (D06-R6)
     */
    public ShardDefinition(
            String shardId,
            String meshNodeName,
            int index,
            float massKg,
            Vector3 centroidLocal,
            Transform localTransform,
            MeshData hullMesh) {
        this.shardId = Objects.requireNonNull(shardId, "shardId");
        this.meshNodeName = meshNodeName == null || meshNodeName.isBlank() ? this.shardId : meshNodeName;
        if (index < 0) {
            throw new IllegalArgumentException("shard index must be >= 0, got " + index);
        }
        if (!Float.isFinite(massKg) || massKg <= 0f) {
            throw new IllegalArgumentException("shard " + shardId + " has non-positive mass " + massKg);
        }
        this.index = index;
        this.massKg = massKg;
        this.centroidLocal.set(centroidLocal);
        this.localTransform.set(localTransform);
        this.hullMesh = Objects.requireNonNull(hullMesh, "hullMesh");
    }

    public String shardId() {
        return shardId;
    }

    /**
     * The node this shard's geometry sits on in {@code shards.glb}.
     *
     * <p>Carried rather than derived from {@link #shardId} because A501 pairs the two by exact
     * match, and because the client resolves a shard's drawable model by the same name the loader
     * resolved its hull by — one field instead of two conventions that can drift.
     */
    public String meshNodeName() {
        return meshNodeName;
    }

    public int index() {
        return index;
    }

    public float massKg() {
        return massKg;
    }

    /** Writes the shard's part-local centroid into {@code out}. */
    public Vector3 centroidLocal(Vector3 out) {
        return out.set(centroidLocal);
    }

    /** Writes the shard's part-local placement into {@code out} as a matrix. */
    public Matrix4 localTransform(Matrix4 out) {
        return localTransform.toMatrix(out);
    }

    public MeshData hullMesh() {
        return hullMesh;
    }

    @Override
    public String toString() {
        return "Shard[" + shardId + " #" + index + ", " + massKg + " kg]";
    }
}
