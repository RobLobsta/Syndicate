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

    /**
     * Radians. The steering lock: how far the steered wheels turn at full input.
     *
     * <p>Not in D04-S4.3.2's original field list, which D05-S5.6 phase 3 nonetheless assigns
     * ({@code v.maxSteerRad = mean(...)}). D05 owns stat aggregation (D00-S11), so the table was
     * amended rather than the algorithm; without the field there is nothing for
     * {@code VehicleControlSystem} to scale a steering input by (DEC-026).
     */
    public float maxSteerRad;

    /** Radians per second. How fast steering moves toward its target, which rate-limits input. */
    public float steerRateRadPerSec;

    /** Newtons, summed over driven wheels. The traction-limited force, applied at low speed. */
    public float engineForceN;

    /**
     * Watts at the road. Caps {@link #engineForceN} at speed: tractive force is
     * {@code min(engineForceN, enginePowerW / v)}.
     *
     * <p>Without it a vehicle pushes its launch force at every speed, and a car calibrated to a real
     * 0-100 time reports a top speed several times what it has (DEC-032).
     */
    public float enginePowerW;

    /** Newtons, summed over braked wheels. */
    public float brakeForceN;

    /** Mean armour rating across armour parts; feeds the HUD and bot threat assessment. */
    public float armorRatingAvg;

    /**
     * Newtons per (m/s)². The chassis part's downforce, carried here so slot 7 can apply it without
     * an asset lookup of its own (D06-S4.5, DEC-031).
     */
    public float downforceCoefficient;

    /** The assembly's balance-budget total (D05-S5.7). */
    public float powerBudget;

    /** Set by {@code DamageSystem}, cleared by {@code VehicleStatsSystem}. */
    public boolean dirty = true;

    @Override
    public void reset() {
        maxSpeedMps = 0f;
        accelerationMps2 = 0f;
        maxSteerRad = 0f;
        steerRateRadPerSec = 0f;
        engineForceN = 0f;
        enginePowerW = 0f;
        brakeForceN = 0f;
        armorRatingAvg = 0f;
        downforceCoefficient = 0f;
        powerBudget = 0f;
        dirty = true;
    }
}
