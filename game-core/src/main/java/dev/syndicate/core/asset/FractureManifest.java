/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a part breaks into (docs/09_blender_destruction_tool.md#D09-S4.4).
 *
 * <p>This is the runtime's view of the manifest the Blender tool writes: the fields the simulation
 * actually consumes, already parsed and already validated. Everything the tool records for
 * traceability — tool version, seed, topology hash, per-shard AABBs — is checked by the pipeline and
 * by the harness (D14 ASSET-004/006) and never read during a match, so it is deliberately absent
 * here rather than carried through the simulation as dead weight.
 *
 * <p>{@link #shards()} is sorted by shard id and that order is load-bearing: {@code FractureSystem}
 * draws from the {@code FRACTURE_SCATTER} stream once per shard, so a different iteration order
 * gives a different — still valid, but different — explosion on a peer that loaded the same asset
 * (G3, G4).
 */
public final class FractureManifest {

    private final AssetId manifestId;
    private final AssetId partTypeId;
    private final float partMassKg;
    private final List<ShardDefinition> shards;

    /**
     * @param manifestId the manifest's own asset id — the key {@code FractureDataComponent} holds
     *     and the {@code assetId} half of every shard's {@code ShapeCacheKey}
     * @param partTypeId which part type this manifest fractures
     * @param partMassKg the intact part's mass, the right-hand side of the G7 conservation check
     * @param shards one entry per shard; copied and sorted by shard id
     * @throws IllegalArgumentException if the shard set is empty, exceeds
     *     {@code MAX_SHARDS_PER_PART}, contains a duplicate id, or its masses do not sum to
     *     {@code partMassKg} within {@code MASS_TOLERANCE_FRAC} (G7)
     */
    public FractureManifest(AssetId manifestId, AssetId partTypeId, float partMassKg, List<ShardDefinition> shards) {
        this.manifestId = Objects.requireNonNull(manifestId, "manifestId");
        this.partTypeId = Objects.requireNonNull(partTypeId, "partTypeId");
        if (!Float.isFinite(partMassKg) || partMassKg < SimulationConstants.MIN_BODY_MASS_KG) {
            throw new IllegalArgumentException(
                    "manifest " + manifestId + " declares part mass " + partMassKg + " kg, below MIN_BODY_MASS_KG");
        }
        this.partMassKg = partMassKg;

        if (shards.isEmpty()) {
            throw new IllegalArgumentException("manifest " + manifestId + " declares no shards");
        }
        if (shards.size() > SimulationConstants.MAX_SHARDS_PER_PART) {
            throw new IllegalArgumentException("manifest " + manifestId + " declares " + shards.size()
                    + " shards, above MAX_SHARDS_PER_PART (" + SimulationConstants.MAX_SHARDS_PER_PART + ")");
        }
        List<ShardDefinition> sorted = new ArrayList<>(shards);
        sorted.sort(Comparator.comparing(ShardDefinition::shardId));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).shardId().equals(sorted.get(i - 1).shardId())) {
                throw new IllegalArgumentException("manifest " + manifestId + " has two shards with id "
                        + sorted.get(i).shardId());
            }
        }
        this.shards = List.copyOf(sorted);

        // G7 is asserted here rather than trusted, because this constructor is the only funnel every
        // manifest passes through at runtime. A manifest that fails it would silently create or
        // destroy mass at the exact moment a player is watching (D07-R19, AC-D07-13).
        float declared = declaredShardMassKg();
        float error = Math.abs(declared - partMassKg) / partMassKg;
        if (error > SimulationConstants.MASS_TOLERANCE_FRAC) {
            throw new IllegalArgumentException("manifest " + manifestId + " violates G7: shards sum to " + declared
                    + " kg against a part mass of " + partMassKg + " kg (" + (error * 100f) + "%, tolerance "
                    + (SimulationConstants.MASS_TOLERANCE_FRAC * 100f) + "%)");
        }
    }

    public AssetId manifestId() {
        return manifestId;
    }

    public AssetId partTypeId() {
        return partTypeId;
    }

    /** The intact part's mass in kilograms. */
    public float partMassKg() {
        return partMassKg;
    }

    /** The shards, sorted by shard id. Immutable. */
    public List<ShardDefinition> shards() {
        return shards;
    }

    /** How many shards this part breaks into. */
    public int shardCount() {
        return shards.size();
    }

    /** The sum of the declared shard masses — the left side of the G7 conservation check. */
    public float declaredShardMassKg() {
        float total = 0f;
        for (int i = 0; i < shards.size(); i++) {
            total += shards.get(i).massKg();
        }
        return total;
    }

    @Override
    public String toString() {
        return "FractureManifest[" + manifestId + ", " + shards.size() + " shards, " + partMassKg + " kg]";
    }
}
