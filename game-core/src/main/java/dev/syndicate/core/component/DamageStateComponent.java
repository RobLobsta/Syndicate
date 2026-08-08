/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.DamageState;

/**
 * Where a part sits in the damage state machine
 * (docs/04_entity_component_model.md#D04-S4.3.3, docs/07_damage_destruction_model.md#D07-S5.4).
 *
 * <p>{@link #stateVersion} exists so delta replication can send three bits of state only when it
 * actually changed (D10-S5.4). Comparing the enum against a baseline would work equally well for
 * that, but the counter also gives the client a way to notice it missed a transition entirely —
 * a version that jumped by two means a state was skipped, which G8's monotonicity says cannot
 * happen locally and therefore indicates packet loss rather than a simulation divergence.
 */
public final class DamageStateComponent implements Component {

    /** The current state. Transitions are monotonic in severity and never reverse (G8). */
    public DamageState state = DamageState.INTACT;

    /** The tick {@link #state} was entered. */
    public long stateEnteredTick;

    /** Incremented on every transition. Never reset mid-match. */
    public int stateVersion;

    @Override
    public void reset() {
        state = DamageState.INTACT;
        stateEnteredTick = 0L;
        stateVersion = 0;
    }
}
