/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * What a material <em>sounds</em> like (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>D15-R37: sounds are per class and per material, never per vehicle. Seven event families across
 * five of these is a set of tens; per-vehicle sound would be a set of hundreds and would gate every
 * new car on an audio pass.
 *
 * <p><b>Fewer of these than there are materials, on purpose.</b> Steel, hardened steel and lead are
 * three different things to a damage formula and one thing to an ear — they all clang. Collapsing
 * them here is what keeps the sound bank at a size a person can actually author and mix, and it is
 * why this is a separate axis from {@code materialId} rather than a field derived from it.
 */
public enum AudioMaterial {

    /** Steel, hardened steel, aluminium, lead. Clangs, scrapes, tears. */
    METAL,

    /** Glazing and lenses. Cracks, then shatters. The one sound a player notices missing. */
    GLASS,

    /** Tyres. Squeals, thumps, scrubs. */
    RUBBER,

    /** Trim, lamp housings, bumper covers. Cracks dully, rattles. */
    PLASTIC,

    /** Carbon and glass-fibre composite. A sharp crack, unlike metal's ring. */
    COMPOSITE;

    /** The asset-id prefix a bank for this material uses, e.g. {@code impact_metal_heavy}. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
