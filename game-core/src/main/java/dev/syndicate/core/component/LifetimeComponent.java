/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;

/**
 * How long an entity has left (docs/04_entity_component_model.md#D04-S4.3.4).
 *
 * <p>Every transient entity carries one. That is not a convenience: D04-E3 says exhausting
 * {@code MAX_ENTITIES} always means a leak, and debris that never despawns is the leak it means.
 * An entity spawned without a lifetime and without an explicit owner responsible for destroying it
 * is a bug waiting for a long match to surface it.
 */
public final class LifetimeComponent implements Component {

    /** What happens when {@link #remainingS} reaches zero. */
    public enum DespawnPolicy {
        /** Destroy immediately at expiry. */
        DESTROY,

        /** Fade out over the following moments, then destroy. Cosmetic only. */
        FADE,

        /** Wait until the body has slept, then destroy — used for debris that is still tumbling. */
        SLEEP_THEN_DESTROY
    }

    /** Seconds remaining. Decremented by {@code LifetimeSystem} (slot 16). */
    public float remainingS;

    /** What to do at expiry. */
    public DespawnPolicy despawnPolicy = DespawnPolicy.DESTROY;

    @Override
    public void reset() {
        remainingS = 0f;
        despawnPolicy = DespawnPolicy.DESTROY;
    }
}
