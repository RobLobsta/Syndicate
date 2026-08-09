/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * One row of the material table (docs/08_asset_pipeline.md#D08-S4.3).
 *
 * <p>{@code assets/materials/materials.json} is the single authority for density, and the Blender
 * tool reads the same file (D09-S6.3) — so a shard's mass and the part's mass are derived from one
 * number, and the mass-conservation check of G7 cannot fail because two tools disagreed about what
 * steel weighs.
 *
 * @param materialId the material's id
 * @param densityKgPerM3 kilograms per cubic metre; what turns a shard's volume into its mass
 * @param resistance per damage type, a multiplier on incoming damage (D07-S4.3). Missing entries
 *     read as 1.0 — an unlisted damage type is unmodified, never immune.
 * @param fractureBrittleness {@code [0,1]}, biasing the tool's shard count and size distribution
 *     (D08-R8). Authoring data: the game loads it so the table round-trips, and ignores it.
 */
public record MaterialDef(
        AssetId materialId, float densityKgPerM3, Map<DamageType, Float> resistance, float fractureBrittleness) {

    /** The multiplier applied when a material lists no resistance for a damage type. */
    public static final float NEUTRAL_RESISTANCE = 1.0f;

    public MaterialDef {
        Objects.requireNonNull(materialId, "materialId");
        if (densityKgPerM3 <= 0f) {
            throw new IllegalArgumentException("material " + materialId.value() + " has density " + densityKgPerM3
                    + "; a part built from it would have no mass (G7)");
        }
        Map<DamageType, Float> copy = new EnumMap<>(DamageType.class);
        if (resistance != null) {
            copy.putAll(resistance);
        }
        resistance = Collections.unmodifiableMap(copy);
    }

    /** This material's multiplier for a damage type, or {@link #NEUTRAL_RESISTANCE} if unlisted. */
    public float resistanceTo(DamageType damageType) {
        return resistance.getOrDefault(damageType, NEUTRAL_RESISTANCE);
    }
}
