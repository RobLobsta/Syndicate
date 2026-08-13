/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.net.Channel;
import dev.syndicate.model.net.DisconnectReason;

/**
 * What {@link Transport#poll} calls back into.
 *
 * <p>Implemented by {@code NetworkAuthority} and {@code NetworkClient}, which are the two ends of
 * the replication protocol. The payload handed to {@link #onMessage} is <b>borrowed</b>: it is the
 * transport's receive buffer and is valid only for the duration of the call. A handler that needs
 * the bytes afterwards copies them, which no handler here does — every message is decoded into
 * world state or into a pooled record before returning.
 */
public interface TransportListener {

    /** A peer became reachable. On the authority this precedes its {@code ClientHello}. */
    default void onPeerConnected(int peerId) {
        // Most listeners learn about a peer from its handshake rather than from the socket.
    }

    /** A peer became unreachable, whether it asked to or not. */
    default void onPeerDisconnected(int peerId, DisconnectReason reason) {
        // Default: nothing. A listener that owns per-peer state overrides this.
    }

    /**
     * One message arrived whole.
     *
     * @param payload the transport's buffer, valid only until this call returns
     */
    void onMessage(int peerId, Channel channel, byte[] payload, int offset, int length);
}
