/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * The four destruction events that travel reliably
 * (docs/07_damage_destruction_model.md#D07-S5.9, docs/10_networking_multiplayer.md#D10-S4.2).
 *
 * <p>These are on {@link Channel#CONTROL} for the reason D10-R2 gives: each one changes a vehicle's
 * part set permanently, so a lost one leaves that client's vehicle structurally different from the
 * authority's for the rest of the match — which is exactly the divergence AC-D10-11 forbids.
 *
 * <p>What is <em>not</em> here is as deliberate as what is: no shard transform, no debris, no
 * particle. A client spawns its own shards from {@link #PART_FRACTURED} and its own debris from
 * {@link #PART_DETACHED} (D07-R5, DEC-005).
 */
public enum StructuralEventType {

    /** A part reached zero health (D07-S5.3). */
    PART_DESTROYED,

    /** A part broke into its manifest's shards (D07-S5.6). */
    PART_FRACTURED,

    /** A part left its vehicle and became debris (D07-S5.7). */
    PART_DETACHED,

    /** A vehicle's chassis died and the whole thing came apart (D07-S5.7). */
    VEHICLE_DESTROYED
}
