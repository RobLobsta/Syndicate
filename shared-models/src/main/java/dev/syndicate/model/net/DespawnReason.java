/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * Why an entity left the world, as carried by {@code DespawnEntity}
 * (docs/10_networking_multiplayer.md#D10-S4.2).
 *
 * <p>The reason is not decoration: a client shows a wrecked vehicle differently from one whose
 * driver disconnected, and a projectile that expired differently from one that hit something.
 */
public enum DespawnReason {

    /** Destroyed in play — wrecked, expired, or otherwise consumed by the simulation. */
    DESTROYED,

    /** Its owner left and the grace period of D10-S5.8 elapsed. */
    OWNER_LEFT,

    /** It moved out of this peer's relevance set (D10-S5.10) rather than ceasing to exist. */
    NO_LONGER_RELEVANT,

    /** The match ended and the world is being torn down. */
    MATCH_ENDED
}
