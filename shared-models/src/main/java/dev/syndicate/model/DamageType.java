/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The five damage types of docs/07_damage_destruction_model.md#D07-S4.3 (D07-R8).
 *
 * <p>Propagation factor and hop limit are carried here because they are the type's identity, not
 * tuning: they decide which parts a hit can reach at all. The armour formula itself lives in the
 * damage pipeline (D07-S5.2), where the material and positional modifiers it composes with live.
 */
public enum DamageType {

    /** Baseline. {@code dmg - armor}, floored at 10% of dmg. */
    KINETIC(0.5f, 1),

    /** Radial falloff; hits every part in radius. */
    EXPLOSIVE(1.5f, 2),

    /** Ignores armour, applies a burn stack. */
    INCENDIARY(0.5f, 1),

    /** Single part only; ramps up over continuous beam time. */
    ENERGY(0.0f, 0),

    /** Derived from relative momentum on impact (D07-S5.2). */
    COLLISION(1.0f, 1);

    private final float propagationFactor;
    private final int maxHops;

    DamageType(float propagationFactor, int maxHops) {
        this.propagationFactor = propagationFactor;
        this.maxHops = maxHops;
    }

    /** Multiplier on {@code PROPAGATION_FRACTION} when walking the slot graph (D07-S5.4). */
    public float propagationFactor() {
        return propagationFactor;
    }

    /** Slot-graph hops this type may propagate. Zero means the struck part only. */
    public int maxHops() {
        return maxHops;
    }
}
