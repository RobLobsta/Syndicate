/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.net.NetConstants;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Everything the authority knows about one connected peer
 * (docs/10_networking_multiplayer.md#D10-S5.2, #S5.4, #S5.8, #S5.9).
 *
 * <p>This is the only per-peer state in the replication layer, and keeping it in one object is what
 * makes the authority's own code peer-agnostic: slot 2 asks a session for the input to apply, slot
 * 18 asks it what baseline to delta against, and neither has to know how many peers there are.
 *
 * <p>The snapshot history is per peer for the reason D10-R16 gives: each client acknowledges what it
 * actually received, and the authority deltas against that specific frame. A shared baseline would
 * force a full snapshot for everybody whenever any one client lost a packet.
 */
public final class PeerSession {

    /** How the peer identifies itself on the transport. */
    public final int peerId;

    /** The peer's player entity, or {@link EntityId#NULL} before it has spawned. */
    public int playerEntity = EntityId.NULL;

    /** The vehicle it is currently driving, or {@link EntityId#NULL}. */
    public int vehicleEntity = EntityId.NULL;

    /** That vehicle's wire identity, for relevance and for prediction filtering. */
    public int vehicleNetworkId = NetworkId.NONE;

    /** The jitter buffer its input arrives into. */
    public final InputBuffer inputBuffer = new InputBuffer();

    /** The last command actually applied, repeated when nothing arrives (D10-R15). */
    public final InputCommand lastInput = new InputCommand();

    /** True once any command has been applied, so tick 0 does not repeat an empty one. */
    public boolean hasAppliedInput;

    /** What the next snapshot acknowledges (D10-S5.5). */
    public final InputAck ack = new InputAck();

    /** Ticks on which no command was available. A rising count is a connection going bad. */
    public int missedInputTicks;

    /** D10-R27's suspicion counter: logged, exposed to admins, never an automatic ban. */
    public int suspicion;

    /** Which network ids this peer has been told to spawn, so a despawn is only sent once. */
    public final TreeSet<Integer> knownNetworkIds = new TreeSet<>();

    /** Snapshots sent and not yet acknowledged, oldest first, keyed by tick. */
    private final TreeMap<Long, SnapshotFrame> pendingSnapshots = new TreeMap<>();

    /** Frames recycled out of {@link #pendingSnapshots}, so a match allocates a bounded number. */
    private final Deque<SnapshotFrame> freeFrames = new ArrayDeque<>();

    /** The frame the peer last acknowledged, or null while it has acknowledged nothing. */
    private SnapshotFrame lastAcked;

    /** Consecutive NACKs; at {@code MAX_NACKS_BEFORE_FULL} the authority stops trying to delta. */
    public int consecutiveNacks;

    /** True once the handshake has been accepted. Nothing but handshake traffic is read before it. */
    public boolean handshakeComplete;

    /** The last tick anything at all arrived from this peer, for the timeout and the grace period. */
    public long lastPacketTick;

    /** Commands accepted since {@link #inputRateWindowStartTick}, for D10-S5.9's rate limit. */
    public int inputsThisWindow;

    public long inputRateWindowStartTick;

    /** Which tick within a snapshot interval this peer's snapshot is sent on (D10-S5.2 step 3). */
    public int staggerOffset;

    public PeerSession(int peerId) {
        this.peerId = peerId;
    }

    /** The baseline the next snapshot should be deltaed against, or null to send a full one. */
    public SnapshotFrame baseline() {
        return consecutiveNacks >= NetConstants.MAX_NACKS_BEFORE_FULL ? null : lastAcked;
    }

    /**
     * Stores a sent snapshot so it can serve as a future baseline.
     *
     * <p>When the history is full the oldest frame is dropped and a full snapshot is forced on the
     * next send: a peer that has not acknowledged anything in {@code SNAPSHOT_HISTORY} snapshots
     * (3.2 s) has lost too much for any baseline to still be shared, and D10-R17 makes recovery a
     * full snapshot rather than a resync protocol.
     */
    public void storeSent(SnapshotFrame frame) {
        pendingSnapshots.put(frame.tick(), frame);
        while (pendingSnapshots.size() > NetConstants.SNAPSHOT_HISTORY) {
            SnapshotFrame dropped = pendingSnapshots.remove(pendingSnapshots.firstKey());
            if (dropped == lastAcked) {
                lastAcked = null;
            }
            if (dropped != null) {
                dropped.clear();
                freeFrames.addLast(dropped);
            }
        }
    }

    /** A frame to build the next snapshot in, recycled where possible. */
    public SnapshotFrame borrowFrame() {
        SnapshotFrame frame = freeFrames.pollFirst();
        if (frame == null) {
            return new SnapshotFrame();
        }
        frame.clear();
        return frame;
    }

    /**
     * Records that the peer has this snapshot (D10-S5.4's {@code onSnapshotAck}).
     *
     * <p>An acknowledgement of a tick the authority no longer holds is ignored rather than treated
     * as an error: it means the history rolled past it, and the next send will be a full snapshot
     * anyway.
     */
    public void acknowledge(long ackedTick) {
        SnapshotFrame frame = pendingSnapshots.get(ackedTick);
        if (frame == null) {
            return;
        }
        lastAcked = frame;
        consecutiveNacks = 0;
        pendingSnapshots.headMap(ackedTick).clear();
    }

    /** The peer could not decode a delta; after enough of these the authority sends a full one. */
    public void nack() {
        consecutiveNacks++;
        lastAcked = null;
    }

    /** How many snapshots are held for this peer. For tests and metrics. */
    public int pendingSnapshotCount() {
        return pendingSnapshots.size();
    }

    /** True when the peer has gone quiet for longer than D10-S5.8's timeout. */
    public boolean hasTimedOut(long currentTick) {
        return currentTick - lastPacketTick > NetConstants.PEER_TIMEOUT_TICKS;
    }

    /** True when the peer has been gone long enough for its vehicle to be destroyed (D10-R16's grace). */
    public boolean graceExpired(long currentTick) {
        return currentTick - lastPacketTick > NetConstants.DISCONNECT_GRACE_TICKS;
    }
}
