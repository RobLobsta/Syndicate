/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;

/**
 * A vehicle's aggregated performance, recomputed from its parts
 * (docs/04_entity_component_model.md#D04-S4.3.2, docs/05_vehicle_part_system.md#D05-S5.6).
 *
 * <p>Every field is derived. {@link #maxSpeedMps} and {@link #accelerationMps2} in particular are
 * computed from engine force, mass, and drag rather than authored (D05-R16) — content that could
 * declare a top speed directly would be able to contradict the physics that has to deliver it.
 *
 * <p>{@link #dirty} is set by {@code DamageSystem} (slot 12) and cleared by
 * {@code VehicleStatsSystem} (slot 6) on the following tick. The one-tick lag is deliberate and
 * safe: stats are read by control, not by the solver, so a vehicle drives with last tick's
 * degradation for one 16 ms frame rather than forcing an out-of-order recompute mid-tick.
 */
public final class VehicleStatsComponent implements Component {

    /** Metres per second. Derived (D05-R16). */
    public float maxSpeedMps;

    /** Metres per second squared. Derived (D05-R16). */
    public float accelerationMps2;

    /** Radians per second. */
    public float steerRateRadPerSec;

    /** Newtons, summed over driven wheels. */
    public float engineForceN;

    /** Newtons, summed over braked wheels. */
    public float brakeForceN;

    /** Mean armour rating across armour parts; feeds the HUD and bot threat assessment. */
    public float armorRatingAvg;

    /** The assembly's balance-budget total (D05-S5.7). */
    public float powerBudget;

    /** Set by {@code DamageSystem}, cleared by {@code VehicleStatsSystem}. */
    public boolean dirty = true;

    @Override
    public void reset() {
        maxSpeedMps = 0f;
        accelerationMps2 = 0f;
        steerRateRadPerSec = 0f;
        engineForceN = 0f;
        brakeForceN = 0f;
        armorRatingAvg = 0f;
        powerBudget = 0f;
        dirty = true;
    }
}
