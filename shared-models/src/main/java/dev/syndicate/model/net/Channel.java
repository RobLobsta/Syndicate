/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.net;

/**
 * The two transport channels of docs/10_networking_multiplayer.md#D10-S4.1.
 *
 * <p>D10-R2 states the rule for choosing between them in one line: <b>if losing the message would
 * cause permanent divergence it is {@link #CONTROL}; if the next message supersedes it, it is
 * {@link #STATE}</b>. A detached part is CONTROL, because a client that misses it drives a heavier
 * vehicle for the rest of the match; a health value is STATE, because the next snapshot carries the
 * current one.
 *
 * <p>The channel is a property of the message type ({@link MessageType#channel()}), not a decision
 * taken at each send site. A message that could be sent on either channel depending on the caller
 * would be a message whose loss behaviour nobody can reason about.
 */
public enum Channel {

    /** Reliable and ordered. TCP under KryoNet; an in-order queue under loopback. */
    CONTROL,

    /** Unreliable and unordered, tick-stamped so the receiver can discard the stale (G16). */
    STATE
}
