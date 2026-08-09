/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import dev.syndicate.model.AssetId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pending spawn requests {@code SpawnSystem} (slot 5) drains each tick
 * (docs/04_entity_component_model.md#D04-S4.4 row 5).
 *
 * <p>A collaborator injected into the system rather than state on it, for the same reason
 * {@code PhysicsWorld} holds the pending-impulse queue rather than {@code PhysicsSystem} (DEC-012):
 * D04-R3 requires systems to be stateless with respect to gameplay, so a queue that lived on the
 * system would be cross-tick simulation state hidden from replication and from a client's rewind.
 * Producers get a reference to this; the system gets the same one.
 *
 * <p>Draining is by ascending {@link SpawnRequest#sequence()}, never by insertion order and never by
 * whatever order the producers happened to run in. The two are usually the same and the sort is what
 * makes them <em>always</em> the same, which is what lets two peers allocate the same entity ids to
 * the same vehicles (G3, D04-R24).
 */
public final class SpawnQueue {

    private static final Comparator<SpawnRequest> BY_SEQUENCE = Comparator.comparingLong(SpawnRequest::sequence);

    private final List<SpawnRequest> pending = new ArrayList<>();

    private long nextSequence;

    /**
     * Queues a request, stamping it with the next sequence number.
     *
     * @return the queued request
     */
    public SpawnRequest request(AssetId assemblyId, Matrix4 spawnTransform, int ownerEntity, int teamId) {
        SpawnRequest request = new SpawnRequest(assemblyId, spawnTransform, ownerEntity, teamId, nextSequence++);
        pending.add(request);
        return request;
    }

    /** Queues an already-built request, keeping the sequence it carries. */
    public void enqueue(SpawnRequest request) {
        nextSequence = Math.max(nextSequence, request.sequence() + 1);
        pending.add(request);
    }

    /**
     * Removes and returns everything pending, in ascending sequence.
     *
     * <p>The queue is emptied whether or not the caller succeeds with each request: a request that
     * names an unloaded assembly is refused once, not retried every tick forever.
     */
    public List<SpawnRequest> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        pending.sort(BY_SEQUENCE);
        List<SpawnRequest> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    /** How many requests are waiting. */
    public int size() {
        return pending.size();
    }

    /** True when nothing is waiting. */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** Drops everything pending, for a match teardown that must not spawn on its way out. */
    public void clear() {
        pending.clear();
    }
}
