/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.AudioMaterial;
import dev.syndicate.model.DamageType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * One row of the material table (docs/08_asset_pipeline.md#D08-S4.3,
 * docs/15_vehicle_preparation_pipeline.md#D15-S5.7, #D15-S8).
 *
 * <p><b>This is the shared material.</b> Three questions about what a piece of a car is
 * <em>made of</em> — what it weighs, how it resists each damage type, what it sounds like — all
 * resolve through one {@code materialId} in one table, read by every consumer: the runtime damage
 * formula, the Blender fracture tool (D09-S6.3), and the audio bank (D15-R37).
 *
 * <p>The alternative was a table per concern keyed by convention. It was rejected for the reason G7
 * exists: mass conservation compares a part's mass against the sum of its shards', and that check is
 * only meaningful because both numbers come from the same density. A windscreen that clangs is the
 * same class of bug — one nothing can catch when the two facts live in different files.
 *
 * <p><b>How a part fails is deliberately not here.</b> {@link dev.syndicate.model.DestructionClass}
 * is a property of what a part <em>is</em>, not what it is made of (D15-R32): a chassis rail and a
 * door skin can be the same steel and fail completely differently. It lives on {@code PartType}.
 *
 * @param materialId the material's id
 * @param densityKgPerM3 kilograms per cubic metre; what turns a shard's volume into its mass
 * @param resistance per damage type, a multiplier on incoming damage (D07-S4.3). Missing entries
 *     read as 1.0 — an unlisted damage type is unmodified, never immune.
 * @param fractureBrittleness {@code [0,1]}, biasing the tool's shard count and size distribution
 *     (D08-R8). Authoring data: the game loads it so the table round-trips, and ignores it.
 * @param audioMaterial which sound bank a part of this material draws on (D15-S8)
 */
public record MaterialDef(
        AssetId materialId,
        float densityKgPerM3,
        Map<DamageType, Float> resistance,
        float fractureBrittleness,
        AudioMaterial audioMaterial) {

    /** The multiplier applied when a material lists no resistance for a damage type. */
    public static final float NEUTRAL_RESISTANCE = 1.0f;

    /** What a material that declares no audio family gets. Metal is what most of a car is. */
    public static final AudioMaterial DEFAULT_AUDIO_MATERIAL = AudioMaterial.METAL;

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
        audioMaterial = audioMaterial == null ? DEFAULT_AUDIO_MATERIAL : audioMaterial;
    }

    /** This material's multiplier for a damage type, or {@link #NEUTRAL_RESISTANCE} if unlisted. */
    public float resistanceTo(DamageType damageType) {
        return resistance.getOrDefault(damageType, NEUTRAL_RESISTANCE);
    }
}
