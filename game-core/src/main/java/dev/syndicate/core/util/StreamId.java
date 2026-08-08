/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

/**
 * The fixed set of independent gameplay random streams (docs/06_physics_simulation.md#D06-S5.8).
 *
 * <p>A subsystem draws only from its own stream (D06-R25). Sharing one couples unrelated systems:
 * adding a single bot decision would shift every weapon's spread, and a determinism regression test
 * would then fail for reasons that have nothing to do with the change being tested.
 *
 * <p>Adding a value here requires updating the STREAMS table in D06-S5.8 in the same change.
 */
public enum StreamId {

    /** Weapon spread cones. */
    DAMAGE_SPREAD,

    /** Shard scatter jitter at fracture. Authoritative, not cosmetic (D07-S5.6). */
    FRACTURE_SCATTER,

    /** Bot reaction jitter and target choice. */
    BOT_DECISION,

    /** Spawn point selection. */
    SPAWN_SELECT,

    /** Everything else gameplay-relevant. */
    MATCH_MISC;
}
