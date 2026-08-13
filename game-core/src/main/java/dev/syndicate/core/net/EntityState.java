/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import java.util.Arrays;

/**
 * One replicated entity's state, already quantised
 * (docs/10_networking_multiplayer.md#D10-S4.4).
 *
 * <p>Quantised rather than in metres and radians, deliberately. The comparison that decides what a
 * delta contains has to be made against the values that were <em>sent</em>, not the values that
 * were measured: a vehicle drifting by half a millimetre encodes to the same 16-bit position it did
 * last tick, and comparing floats would put it in every snapshot forever. Comparing on the lattice
 * is what makes a parked car cost zero bits.
 *
 * <p>It also makes idempotence (G16, AC-D10-5) a property of the representation rather than of the
 * apply path: the value a client stores is exactly the value it received, so applying the same
 * snapshot again changes nothing.
 */
public final class EntityState {

    /** The wire identity this state belongs to. */
    public int networkId = NetworkId.NONE;

    /** Bit per {@link ReplicatedComponent} the entity carries, whether or not it changed. */
    public int componentMask;

    /** Quantised field values, indexed by {@link ReplicatedField#slot()}. */
    public final int[] values = new int[ReplicatedField.TOTAL_SLOTS];

    /** The authority tick this state was captured or received at. */
    public long tick;

    /** Copies {@code other} wholesale. */
    public void set(EntityState other) {
        networkId = other.networkId;
        componentMask = other.componentMask;
        tick = other.tick;
        System.arraycopy(other.values, 0, values, 0, values.length);
    }

    /** True when the entity carries this component. */
    public boolean has(ReplicatedComponent component) {
        return (componentMask & component.maskBit()) != 0;
    }

    /** Records that the entity carries this component. */
    public void mark(ReplicatedComponent component) {
        componentMask |= component.maskBit();
    }

    /** True when any of {@code field}'s values differ from {@code other}'s. */
    public boolean differs(EntityState other, ReplicatedField field) {
        int slot = field.slot();
        for (int i = 0; i < field.valueCount(); i++) {
            if (values[slot + i] != other.values[slot + i]) {
                return true;
            }
        }
        return false;
    }

    /** Copies one field's values from {@code other}. */
    public void copyField(EntityState other, ReplicatedField field) {
        int slot = field.slot();
        System.arraycopy(other.values, slot, values, slot, field.valueCount());
    }

    /** Returns to an unassigned, all-zero state so the instance can be pooled. */
    public void reset() {
        networkId = NetworkId.NONE;
        componentMask = 0;
        tick = 0L;
        Arrays.fill(values, 0);
    }

    @Override
    public String toString() {
        return "EntityState[" + NetworkId.toString(networkId) + " mask=" + Integer.toBinaryString(componentMask) + "]";
    }
}
