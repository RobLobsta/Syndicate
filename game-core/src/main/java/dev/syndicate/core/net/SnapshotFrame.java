/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import java.util.Map;
import java.util.TreeMap;

/**
 * The complete replicated state of a world at one tick, as one peer sees it
 * (docs/10_networking_multiplayer.md#D10-S5.4).
 *
 * <p>A frame is <b>whole, never a delta</b>, on both ends. The authority stores the frame it built
 * for a peer so it can delta the next one against whatever that peer acknowledges; the client
 * stores the frame it arrived at after applying a delta, so it can serve as the baseline for the
 * next. Both sides therefore hold the same object for the same tick, which is what makes D10-R16's
 * per-peer baselines work at all — a delta against a baseline only one side has is undecodable, and
 * D10-R18 makes that a NACK rather than a guess.
 *
 * <p>Entities are keyed in a {@link TreeMap} rather than a hash map because the encoder walks them
 * in ascending network id order (G3, D10-S5.4): two peers with identical relevance sets must
 * receive byte-identical snapshots, and hash order would make the bytes depend on insertion
 * history.
 */
public final class SnapshotFrame {

    private final TreeMap<Integer, EntityState> entities = new TreeMap<>();
    private long tick;

    /** The authority tick this frame describes. */
    public long tick() {
        return tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    /** How many entities the frame holds. */
    public int size() {
        return entities.size();
    }

    public boolean isEmpty() {
        return entities.isEmpty();
    }

    /** The state for a network id, or null. */
    public EntityState get(int networkId) {
        return entities.get(networkId);
    }

    /** The state for a network id, created empty if absent. */
    public EntityState getOrCreate(int networkId) {
        return entities.computeIfAbsent(networkId, id -> {
            EntityState state = new EntityState();
            state.networkId = id;
            return state;
        });
    }

    /** Drops an entity, as a despawn does. */
    public EntityState remove(int networkId) {
        return entities.remove(networkId);
    }

    /** Every entity in ascending network id order (G3). */
    public Iterable<Map.Entry<Integer, EntityState>> entries() {
        return entities.entrySet();
    }

    /** Forgets everything. */
    public void clear() {
        entities.clear();
        tick = 0L;
    }

    /** Replaces this frame's contents with {@code other}'s, reusing the states already allocated. */
    public void copyFrom(SnapshotFrame other) {
        entities.keySet().retainAll(other.entities.keySet());
        for (Map.Entry<Integer, EntityState> entry : other.entities.entrySet()) {
            getOrCreate(entry.getKey()).set(entry.getValue());
        }
        tick = other.tick;
    }

    @Override
    public String toString() {
        return "SnapshotFrame[tick=" + tick + ", entities=" + entities.size() + "]";
    }
}
