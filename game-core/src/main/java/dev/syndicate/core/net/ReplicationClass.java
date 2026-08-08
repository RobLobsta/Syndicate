/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

/**
 * How often an entity's authoritative state goes on the wire
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/10_networking_multiplayer.md#D10-S5.3).
 *
 * <p>The class is a property of the entity, not of the send system, so the cost of replicating a
 * new archetype is decided once at spawn rather than rediscovered as a bandwidth regression.
 */
public enum ReplicationClass {
    /** Vehicles and anything a player aims at: sent every snapshot. */
    HIGH_FREQ,

    /** Parts and slow-changing state: sent on change, rate-limited. */
    LOW_FREQ,

    /** Never sent as state; only as the structural events of D10-S4.2. */
    EVENT_ONLY
}
