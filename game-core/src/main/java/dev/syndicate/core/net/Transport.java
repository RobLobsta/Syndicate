/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.net.Channel;
import dev.syndicate.model.net.DisconnectReason;

/**
 * The seam between replication and whatever moves the bytes
 * (docs/02_technical_architecture.md#D02-S4.3, #D02-S5.3).
 *
 * <p>D02-R5 wraps KryoNet behind this interface so the wire library can be replaced without
 * touching gameplay code, and D02-R19 makes the same interface carry single-player: the authority
 * and the local client talk through a {@link LoopbackTransport} rather than through a shortcut, so
 * <b>there is no separate single-player code path</b>. That is what makes AC-D10-19 and AC-D10-22
 * checkable at all — a bug in replication shows up in single-player, where it is cheap to find,
 * instead of waiting for a second machine.
 *
 * <p>Delivery is <b>polled, never pushed</b>. {@link #poll} is called from one known point in the
 * schedule — slot 2 on the authority, slot 19 on a client — so a message can never arrive between
 * two systems and change the world underneath one of them. A callback-driven transport would make
 * the tick's causality depend on packet timing, which is the whole of what G2 forbids.
 */
public interface Transport {

    /**
     * Queues a payload for one peer.
     *
     * <p>The bytes are consumed before this returns: callers hand over a {@link BitWriter}'s
     * scratch buffer and immediately reuse it. An implementation that wanted to defer the write
     * must copy.
     *
     * @param peerId the recipient; {@code NetConstants.SERVER_PEER_ID} from a client
     * @param channel which reliability class the message needs (D10-R2)
     */
    void send(int peerId, Channel channel, byte[] payload, int offset, int length);

    /**
     * Delivers everything that has arrived since the last call, then returns.
     *
     * <p>Ordering across a single call is {@code CONTROL} before {@code STATE} and, within each
     * channel, arrival order. A handshake or a spawn therefore lands before any snapshot that
     * depends on it, which is what keeps D10-E17's "snapshot for an unspawned id" a rare case
     * rather than the normal one.
     */
    void poll(TransportListener listener);

    /** True while {@code peerId} is reachable. */
    boolean isConnected(int peerId);

    /**
     * Every connected peer, in ascending id order.
     *
     * <p>Ascending because the authority iterates peers to apply their input (D10-S5.2 step 1) and
     * that iteration decides simulation outcomes; hash order would make the result depend on the
     * order peers happened to connect in (G3).
     */
    int[] connectedPeers();

    /** Ends one peer's connection, telling it why if the channel still carries. */
    void disconnect(int peerId, DisconnectReason reason);

    /** Releases sockets, threads and buffers. Idempotent. */
    void dispose();
}
