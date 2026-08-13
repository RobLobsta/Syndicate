/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.net.Channel;
import dev.syndicate.model.net.DisconnectReason;
import dev.syndicate.model.net.NetConstants;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Two {@link Transport} endpoints in one process, wired to each other
 * (docs/02_technical_architecture.md#D02-S5.3).
 *
 * <p>This is what makes single-player run the replication code rather than bypass it (D02-R19,
 * AC-D10-19). A local match builds a pair, hands one end to the authority and the other to the
 * client, and every snapshot, input command and structural event goes through the same encoder and
 * decoder a remote peer's would. AC-D10-22 is the test that pins it: single-player and
 * hosted-single-player, same seed and inputs, identical authoritative state.
 *
 * <p><b>It copies.</b> D02-S5.3's sketch calls loopback "zero-copy", and that cannot be honoured
 * against this {@link Transport} contract: senders hand over a {@link BitWriter}'s scratch buffer
 * and overwrite it on the next message, so retaining the reference would deliver whatever the
 * sender wrote next. The copies come from a free list rather than the allocator, which keeps the
 * steady-state garbage at zero — the property "zero-copy" was reaching for.
 *
 * <p><b>It loses nothing and reorders nothing</b>, including on {@link Channel#STATE}. A loopback
 * pair is not a network and pretending otherwise would make single-player randomly worse for no
 * gain. Loss and jitter belong in a deliberate test double, not in the shipping transport.
 */
public final class LoopbackTransport implements Transport {

    /** The two ends of one connection. */
    public record Pair(LoopbackTransport authoritySide, LoopbackTransport clientSide) {}

    /**
     * Builds a connected pair.
     *
     * @param clientPeerId the id the authority knows the client by; the client addresses the
     *     authority as {@code NetConstants.SERVER_PEER_ID}
     */
    public static Pair createPair(int clientPeerId) {
        if (clientPeerId == NetConstants.SERVER_PEER_ID) {
            throw new IllegalArgumentException(
                    "a client peer id may not be " + NetConstants.SERVER_PEER_ID + ", which names the authority");
        }
        LoopbackTransport authoritySide = new LoopbackTransport(clientPeerId);
        LoopbackTransport clientSide = new LoopbackTransport(NetConstants.SERVER_PEER_ID);
        authoritySide.other = clientSide;
        clientSide.other = authoritySide;
        authoritySide.pendingConnect = true;
        clientSide.pendingConnect = true;
        return new Pair(authoritySide, clientSide);
    }

    /** A delivered message, pooled. */
    private static final class Packet {
        Channel channel;
        byte[] bytes = new byte[256];
        int length;

        void set(Channel channel, byte[] source, int offset, int length) {
            if (bytes.length < length) {
                bytes = new byte[Math.max(length, bytes.length * 2)];
            }
            System.arraycopy(source, offset, bytes, 0, length);
            this.channel = channel;
            this.length = length;
        }
    }

    private final int remotePeerId;
    private final Deque<Packet> control = new ArrayDeque<>();
    private final Deque<Packet> state = new ArrayDeque<>();
    private final Deque<Packet> free = new ArrayDeque<>();

    private LoopbackTransport other;
    private boolean pendingConnect;
    private DisconnectReason pendingDisconnect;
    private boolean connected = true;

    private LoopbackTransport(int remotePeerId) {
        this.remotePeerId = remotePeerId;
    }

    /** The id this endpoint addresses its counterpart by. */
    public int remotePeerId() {
        return remotePeerId;
    }

    @Override
    public void send(int peerId, Channel channel, byte[] payload, int offset, int length) {
        if (peerId != remotePeerId) {
            throw new IllegalArgumentException(
                    "loopback endpoint is connected to peer " + remotePeerId + ", not " + peerId);
        }
        if (!connected || other == null) {
            return;
        }
        other.deliver(channel, payload, offset, length);
    }

    private void deliver(Channel channel, byte[] payload, int offset, int length) {
        Packet packet = free.isEmpty() ? new Packet() : free.removeFirst();
        packet.set(channel, payload, offset, length);
        (channel == Channel.CONTROL ? control : state).addLast(packet);
    }

    @Override
    public void poll(TransportListener listener) {
        if (pendingConnect) {
            pendingConnect = false;
            listener.onPeerConnected(remotePeerId);
        }
        drain(control, listener);
        drain(state, listener);
        if (pendingDisconnect != null) {
            DisconnectReason reason = pendingDisconnect;
            pendingDisconnect = null;
            connected = false;
            listener.onPeerDisconnected(remotePeerId, reason);
        }
    }

    private void drain(Deque<Packet> queue, TransportListener listener) {
        while (!queue.isEmpty()) {
            Packet packet = queue.removeFirst();
            try {
                listener.onMessage(remotePeerId, packet.channel, packet.bytes, 0, packet.length);
            } finally {
                free.addLast(packet);
            }
        }
    }

    @Override
    public boolean isConnected(int peerId) {
        return connected && peerId == remotePeerId;
    }

    @Override
    public int[] connectedPeers() {
        return connected ? new int[] {remotePeerId} : new int[0];
    }

    @Override
    public void disconnect(int peerId, DisconnectReason reason) {
        if (peerId != remotePeerId || !connected) {
            return;
        }
        connected = false;
        if (other != null) {
            other.pendingDisconnect = reason;
        }
    }

    @Override
    public void dispose() {
        connected = false;
        control.clear();
        state.clear();
        free.clear();
        other = null;
    }

    /** For tests: how many messages are waiting on a channel. */
    public int queuedMessages(Channel channel) {
        return (channel == Channel.CONTROL ? control : state).size();
    }

    /**
     * For tests: drops every queued {@link Channel#STATE} message, simulating packet loss.
     *
     * <p>Here rather than in a subclass because the loss cases D10 requires — T-D10-4's dropped
     * baseline, T-D10-5's 64 lost snapshots, T-D10-20's six lost inputs — are exercised by dropping
     * what is already in the queue, and a second transport implementation to do that would be a
     * second thing to keep correct.
     */
    public int dropQueuedState() {
        int dropped = state.size();
        while (!state.isEmpty()) {
            free.addLast(state.removeFirst());
        }
        return dropped;
    }

    /** For tests: the payload of the oldest queued message on a channel, without consuming it. */
    public byte[] peek(Channel channel) {
        Packet packet = (channel == Channel.CONTROL ? control : state).peekFirst();
        return packet == null ? null : Arrays.copyOf(packet.bytes, packet.length);
    }
}
