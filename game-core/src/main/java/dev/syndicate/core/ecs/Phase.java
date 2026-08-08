/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

/**
 * The execution phases of the fixed system schedule (docs/04_entity_component_model.md#D04-S4.4).
 *
 * <p>Declaration order is execution order. Every phase runs inside one tick except {@link #PRESENT},
 * whose systems run once per rendered frame (D04-R7) — that split is the only place tick/frame
 * decoupling appears in the whole design.
 */
public enum Phase {

    /** Collecting player input, receiving remote input, and bot decisions. */
    INPUT,

    /** Match flow, spawning, and stat aggregation, before anything touches physics. */
    PRE_SIM,

    /** Vehicle control, weapons, projectiles, and the Bullet step itself. */
    SIM,

    /** Collision events, damage, fracture, detachment, mass recomputation, lifetimes, scoring. */
    POST_SIM,

    /** Snapshot send, snapshot receive, reconciliation. */
    NET,

    /**
     * Presentation. Systems 22-26 run once per rendered frame rather than once per tick; systems 21
     * and 27 run per tick (D04-R7). A gameplay system never belongs here.
     */
    PRESENT,

    /** Deferred entity teardown. Always last, so nothing reads a half-destroyed entity (D04-R15). */
    CLEANUP;

    /** True when this phase runs in the render loop rather than the tick loop (D03-S5.3). */
    public boolean isPerFrame() {
        return this == PRESENT;
    }
}
