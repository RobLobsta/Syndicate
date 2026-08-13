/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * Why an authority refused a connection at the handshake
 * (docs/10_networking_multiplayer.md#D10-S5.8).
 *
 * <p>Every refusal names a reason and reports both sides' values where there are two (D10-R11,
 * D03-E6). A connection that is refused without saying why turns a one-line version skew into an
 * afternoon of packet captures.
 */
public enum RejectReason {

    /** {@code protocolVersion} differs. The wire format is not compatible; nothing can be salvaged. */
    PROTOCOL_MISMATCH,

    /** {@code contentHash} differs: the two peers disagree about the assets or the component list. */
    CONTENT_MISMATCH,

    /** The server is at {@code maxPlayers}. */
    SERVER_FULL,

    /** The peer is banned. */
    BANNED
}
