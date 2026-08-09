/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

/**
 * One row of the DEGRADATION_TABLE: how a stat falls off, and how much of it survives
 * (docs/05_vehicle_part_system.md#D05-S5.4).
 *
 * <p>{@code floor} is what remains at {@code h = 0+}, just before destruction. At {@code h == 0} the
 * part is {@code DESTROYED} and contributes nothing at all, which is a separate rule
 * ({@code recomputePartEffectiveStats} returns zero for a destroyed part regardless of the floor,
 * D05-E9) rather than the curve's value at zero.
 *
 * <p>This is also the shape of a part's authored {@code degradationOverrides} entry (D08-R5), which
 * is why the record is public rather than an implementation detail of {@link Degradation}.
 */
public record DegradationRule(DegradationProfile profile, float floor) {

    public DegradationRule {
        if (profile == null) {
            throw new IllegalArgumentException("profile");
        }
        if (!(floor >= 0f) || floor > 1f) {
            // NaN fails the first half, which is deliberate: a NaN floor would silently make every
            // stat on the part NaN and surface as a physics failure somewhere else entirely.
            throw new IllegalArgumentException("floor must be in [0,1], was " + floor);
        }
    }
}
