/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;

/**
 * A part's hit points and who last removed some (docs/04_entity_component_model.md#D04-S4.3.3).
 *
 * <p>{@link #healthFraction} is stored rather than divided on demand because it is read far more
 * often than it is written: the degradation curve (D05-S5.4), the morph mapping (D07-S5.5), the
 * damage state machine, the HUD, and bot threat assessment all consult it, most of them every tick
 * for every part.
 *
 * <p>The two are only consistent if written together, so writers go through {@link #setCurrentHp}
 * rather than assigning the field. That method is the one exception D04-R2 allows beyond trivial
 * accessors: it is a setter with an invariant, not behaviour.
 */
public final class HealthComponent implements Component {

    /** Hit points at full health, from the part type. */
    public float maxHp;

    /** Current hit points, clamped to {@code [0, maxHp]}. */
    public float currentHp;

    /** Derived {@code currentHp / maxHp}, {@code [0,1]}. Always consistent with the two above. */
    public float healthFraction = 1f;

    /** Flat mitigation subtracted before hit points are removed (D07-S5.2). */
    public float armorValue;

    /** The tick of the most recent damage; drives damage-over-time and scoring attribution. */
    public long lastDamageTick;

    /** Who dealt that damage, for kill attribution. */
    public int lastAttacker = EntityId.NULL;

    /**
     * The contact normal of the most recent hit, world space, unit length — or zero if none.
     *
     * <p>D07-S5.7 gives a part detached by a hit a kick of up to {@code DETACH_KICK_MPS} along the
     * direction it was struck from, so parts fly off the way they were hit rather than simply
     * falling. That direction is known in slot 12 and needed in slot 14, and there is nowhere else
     * to keep it: a field on {@code DamageSystem} would be the cross-tick system state D04-R3
     * prohibits, and the event that carried it has been consumed by then.
     *
     * <p>Stored as three scalars rather than a {@code Vector3} to keep the component poolable
     * without a nested mutable object, in the same style as {@code PartFracturedEvent}'s velocities.
     */
    public float lastHitNormalX;

    public float lastHitNormalY;

    public float lastHitNormalZ;

    /**
     * Sets current hit points, clamping to {@code [0, maxHp]} and updating {@link #healthFraction}.
     *
     * <p>A zero {@link #maxHp} yields a fraction of 0 rather than a NaN. A part with no maximum is a
     * content error, but propagating NaN into the degradation curve turns it into a physics failure
     * several systems away from the cause.
     */
    public void setCurrentHp(float hp) {
        currentHp = Math.max(0f, Math.min(hp, maxHp));
        healthFraction = maxHp > 0f ? currentHp / maxHp : 0f;
    }

    @Override
    public void reset() {
        maxHp = 0f;
        currentHp = 0f;
        healthFraction = 1f;
        armorValue = 0f;
        lastDamageTick = 0L;
        lastAttacker = EntityId.NULL;
        lastHitNormalX = 0f;
        lastHitNormalY = 0f;
        lastHitNormalZ = 0f;
    }
}
