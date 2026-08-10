/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import java.util.Arrays;

/**
 * Incendiary burn stacks on a part
 * (docs/04_entity_component_model.md#D04-S4.3.3, docs/07_damage_destruction_model.md#D07-S4.3).
 *
 * <p>{@code INCENDIARY} is the one damage type that keeps working after the hit: it ignores armour,
 * applies a stack, and each stack ticks {@link #BURN_DAMAGE_PER_SECOND} hit points per second for
 * {@link #BURN_DURATION_S}, up to {@link #MAX_STACKS}. That is a timer, and a timer is state, so it
 * lives on the part rather than in the system that advances it (D04-R3).
 *
 * <p><b>Per-stack timers, not one shared timer.</b> {@link #remainingS} holds one entry per live
 * stack. A single refreshed timer would be simpler and wrong in a way players feel: it would let one
 * flamer tick keep five stacks alive indefinitely, turning a weapon that is meant to reward sustained
 * contact into one that rewards a single touch. Expiring stacks individually means burn intensity
 * falls off exactly as the contact that built it stops.
 *
 * <p>Damage from a burn is authoritative and attributed: {@link #lastAttacker} is who lit the fire,
 * so a part that burns down credits the player who set it alight rather than nobody (D01-S5.4).
 */
public final class BurnStackComponent implements Component {

    /** Hit points per second one stack removes (D07-R8). */
    public static final float BURN_DAMAGE_PER_SECOND = 4.0f;

    /** Seconds one stack lives (D07-R8). */
    public static final float BURN_DURATION_S = 5.0f;

    /** How many stacks may burn at once (D07-R8). */
    public static final int MAX_STACKS = 5;

    /** Seconds left on each live stack; entries {@code [0, stackCount)} are live. */
    public final float[] remainingS = new float[MAX_STACKS];

    /** How many entries of {@link #remainingS} are live. */
    public int stackCount;

    /** The player credited with the burn damage, or {@link EntityId#NULL}. */
    public int lastAttacker = EntityId.NULL;

    @Override
    public void reset() {
        Arrays.fill(remainingS, 0f);
        stackCount = 0;
        lastAttacker = EntityId.NULL;
    }
}
