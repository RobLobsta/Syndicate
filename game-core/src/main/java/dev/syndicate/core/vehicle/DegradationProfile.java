/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

/**
 * How a stat falls off as its part loses health (docs/05_vehicle_part_system.md#D05-S5.4).
 *
 * <p>Four curves, each chosen for the feel its category needs (D01-S5.3) rather than for
 * mathematical elegance. The curve is a property of the (category, stat) pair, not of the part —
 * a wheel's grip and its steering degrade differently — so the profile is looked up in
 * {@link Degradation#ruleFor} rather than stored per part.
 */
public enum DegradationProfile {

    /** Smooth and predictable: {@code floor + (1 - floor) * h}. */
    LINEAR,

    /** Full performance above {@code h = 0.66}, then a fast fall. Weapons "sputter" (D05-S5.4). */
    THRESHOLD,

    /** Early loss with a long tail: {@code floor + (1 - floor) * h²}. Wheels lose grip at once. */
    EXPONENTIAL,

    /** No degradation at all. Mass (D05-R20) and damage per shot (D05-R21) are on this curve. */
    NONE
}
