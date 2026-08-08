/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import dev.syndicate.model.AssetId;
import java.util.Objects;

/**
 * The identity of a collision shape in the shape cache
 * (docs/04_entity_component_model.md#D04-S4.3.1, docs/06_physics_simulation.md#D06-S4.3).
 *
 * <p>Shapes are immutable, stateless, and shared by reference: D02-S5.7 rule 2 makes the cache
 * their sole owner, and bodies never dispose them. That sharing is only safe while two callers
 * asking for "the same shape" are guaranteed to mean it, which is what this key provides — a value
 * type with structural equality rather than a native pointer.
 *
 * @param assetId which asset the shape was built from
 * @param variant which shape of that asset: the intact hull, a shard, or the compound
 * @param index disambiguates within a variant — the shard index, or 0 when there is only one
 */
public record ShapeCacheKey(AssetId assetId, Variant variant, int index) {

    /** What kind of shape an entry holds. */
    public enum Variant {
        /** The intact part's convex hull. */
        PART_HULL,
        /** One shard's convex hull, selected by {@link ShapeCacheKey#index}. */
        SHARD_HULL,
        /** A vehicle's assembled {@code btCompoundShape} (D06-S5.3). */
        COMPOUND,
        /** Static arena geometry: a mesh shape, never used for a dynamic body. */
        STATIC_MESH,
        /** A primitive built from parameters rather than art — boxes, spheres, the ground plane. */
        PRIMITIVE
    }

    public ShapeCacheKey {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(variant, "variant");
        if (index < 0) {
            throw new IllegalArgumentException("shape index must be >= 0, got " + index);
        }
    }

    /** The single shape of an asset that has only one of this variant. */
    public static ShapeCacheKey of(AssetId assetId, Variant variant) {
        return new ShapeCacheKey(assetId, variant, 0);
    }

    /** The hull of shard {@code index} of a fractured part. */
    public static ShapeCacheKey shard(AssetId assetId, int index) {
        return new ShapeCacheKey(assetId, Variant.SHARD_HULL, index);
    }

    @Override
    public String toString() {
        return assetId.value() + "#" + variant + (index == 0 ? "" : ":" + index);
    }
}
