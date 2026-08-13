/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * Why a connection ended (docs/10_networking_multiplayer.md#D10-S5.8).
 *
 * <p>Both directions use the same set: a client that quits and a server that shuts down are the
 * same event seen from two ends, and giving each end its own vocabulary makes the logs of a
 * disconnect read as two unrelated incidents.
 */
public enum DisconnectReason {

    /** The peer asked to leave. */
    QUIT,

    /** Nothing arrived within the 15 s timeout of D10-S5.8. */
    TIMEOUT,

    /** An administrator removed the peer. */
    KICKED,

    /** The authority is stopping; every peer receives this. */
    SERVER_SHUTDOWN,

    /** The listen server's host quit, which ends the match — there is no host migration (D10-E10). */
    HOST_LEFT,

    /** The transport failed in a way neither end chose. */
    TRANSPORT_ERROR
}
