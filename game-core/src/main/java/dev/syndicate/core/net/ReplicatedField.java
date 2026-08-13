/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.net.NetConstants;

/**
 * Every field a snapshot can carry, with the width D10-S4.3 gives it
 * (docs/10_networking_multiplayer.md#D10-S4.3, #D10-S4.4).
 *
 * <p>A field is the unit the {@code changedFieldMask} of D10-S4.4 has one bit for, and it is
 * deliberately coarser than a scalar: a position is one field of three 16-bit values, not three
 * fields, because the three move together and a mask bit per axis would cost more than it saves.
 *
 * <p>{@link #slot()} is where the field's quantised values live in an {@link EntityState}'s array.
 * The layout is flat and fixed so that comparing an entity against a baseline is an integer compare
 * over a small array rather than a walk over component objects — the comparison runs for every
 * replicated entity for every peer at {@code SNAPSHOT_RATE_HZ}, and it is the one part of snapshot
 * building that scales with peers × entities.
 *
 * <p><b>Nothing cosmetic may ever appear here.</b> This enum is the machine-readable form of
 * D10-S4.3's "replicated" table, and its counterpart R6 table — morph weights, shard transforms,
 * debris, particles, camera, derived mass — is enforced by there being no constant for any of them
 * (G6, AC-D10-3).
 */
public enum ReplicatedField {

    /** World position, three axes at {@code POSITION_BITS} each. */
    POSITION(0, 3, NetConstants.POSITION_BITS),

    /** Orientation as one packed smallest-three quaternion. */
    ROTATION(3, 1, 2 + 3 * NetConstants.ROTATION_COMPONENT_BITS),

    /** Linear velocity, three axes. */
    LINEAR_VELOCITY(4, 3, NetConstants.VELOCITY_BITS),

    /** Angular velocity, three axes. */
    ANGULAR_VELOCITY(7, 3, NetConstants.VELOCITY_BITS),

    /** A part's health fraction (D07-S5.9). */
    HEALTH_FRACTION(10, 1, NetConstants.HEALTH_BITS),

    /** A part's damage state ordinal. */
    DAMAGE_STATE(11, 1, NetConstants.DAMAGE_STATE_BITS),

    /** Ticks until a weapon may fire again. Owner only (D10-S4.3). */
    WEAPON_COOLDOWN(12, 1, NetConstants.WEAPON_FIELD_BITS),

    /** Rounds remaining, as a fraction of capacity. Owner only. */
    WEAPON_AMMO(13, 1, NetConstants.WEAPON_FIELD_BITS),

    /** Heat, as a fraction of the overheat threshold. Owner only. */
    WEAPON_HEAT(14, 1, NetConstants.WEAPON_FIELD_BITS);

    /** How many quantised values an {@link EntityState} holds. */
    public static final int TOTAL_SLOTS = 15;

    private final int slot;
    private final int valueCount;
    private final int bitsPerValue;

    ReplicatedField(int slot, int valueCount, int bitsPerValue) {
        this.slot = slot;
        this.valueCount = valueCount;
        this.bitsPerValue = bitsPerValue;
    }

    /** Index of this field's first value in an {@link EntityState}'s array. */
    public int slot() {
        return slot;
    }

    /** How many array slots the field occupies. */
    public int valueCount() {
        return valueCount;
    }

    /** Bits each of those values takes on the wire. */
    public int bitsPerValue() {
        return bitsPerValue;
    }
}
