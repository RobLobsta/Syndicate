/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The kinds of utility module a vehicle can carry
 * (docs/05_vehicle_part_system.md#D05-S4.2, docs/08_asset_pipeline.md#D08-S4.2).
 *
 * <p>A {@code utility} part is one of these. The family fixes what the module <em>does</em>; how much
 * of it, for how long, and how often are the part's stats, so a module degrades and can be buffed
 * like everything else (the {@link WeaponFamily} argument, DEC-039).
 *
 * <p>{@link Activation} is what the simulation branches on and is the reason this enum exists rather
 * than the three passive modules of D05-S4.2 continuing to be nothing but a stat block. A passive
 * module's whole effect is its stats — a radiator is {@code heatPerShot} and nothing else — and needs
 * no identity at all. An <b>active</b> one does: it has a state (idle, running, recharging) that a
 * stat cannot represent, and the player triggers it. Cloak is the case that forced the distinction.
 *
 * <p>Every family here is a data contract with no behaviour behind it yet: the parts that use them
 * are authored content and arrive separately. What this fixes now is that they arrive into a slot,
 * a schema and a validator that already know what they are, rather than each new module widening
 * the part format.
 */
public enum ModuleFamily {

    /** Extends the vehicle's sensor range. Passive; its effect is the {@code sensorRangeM} stat. */
    RADAR(Activation.PASSIVE),

    /** Raises weapon fire rate. Passive; its effect is the {@code fireIntervalS} stat (D05-S4.2). */
    AMMO_FEED(Activation.PASSIVE),

    /** Sheds weapon heat. Passive; its effect is the {@code heatPerShot} stat (D05-S4.2). */
    RADIATOR(Activation.PASSIVE),

    /** Raises the health of the vehicle's panels. Passive; the {@code maxHpMul} stat (D05-S4.2). */
    REINFORCER(Activation.PASSIVE),

    /** Hides the vehicle from opposing sensors for a duration. Active. */
    CLOAK(Activation.ACTIVE),

    /** A burst of tractive force for a duration. Active. */
    BOOSTER(Activation.ACTIVE),

    /** Breaks a sensor lock and obscures a region. Active. */
    SMOKE(Activation.ACTIVE),

    /** Restores hit points to the parts around it over a duration. Active. */
    REPAIR(Activation.ACTIVE);

    /** How a module's effect is brought about. */
    public enum Activation {
        /** Always on while the part lives. Its whole effect is its stats (D05-S5.6 phase 2). */
        PASSIVE,
        /** Triggered by the player, runs for a duration, then recharges for a cooldown. */
        ACTIVE
    }

    private final Activation activation;

    ModuleFamily(Activation activation) {
        this.activation = activation;
    }

    /** Whether this family is always on or triggered. */
    public Activation activation() {
        return activation;
    }

    /** True when the player triggers this module rather than merely carrying it. */
    public boolean isActive() {
        return activation == Activation.ACTIVE;
    }
}
