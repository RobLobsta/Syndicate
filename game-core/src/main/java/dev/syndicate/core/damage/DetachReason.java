/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

/**
 * Why a part left its vehicle (docs/07_damage_destruction_model.md#D07-S5.7).
 *
 * <p>The reason is replicated with the {@code PartDetached} event (D07-S5.9) because it selects what
 * the client does next: a fractured part is replaced by shards the client spawns itself, while a
 * merely detached one becomes a single debris body. A client that knew a part had gone but not why
 * would have to guess between those.
 */
public enum DetachReason {

    /** The part was destroyed and broke into shards (D07-S5.6). */
    FRACTURED,

    /** The part was destroyed and has no fracture manifest, so it leaves in one piece. */
    DESTROYED,

    /** A constraint holding a separate-body part exceeded its break impulse (D06-S5.6). */
    JOINT_BROKE,

    /** The chassis died and the whole assembly came apart (D07-S5.7 T4). */
    VEHICLE_WRECKED,

    /** The part's parent left, and a part carries its children with it (D05-S5.5 step 1). */
    PARENT_DETACHED
}
