/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

/**
 * How far through a client's input the authority has got
 * (docs/10_networking_multiplayer.md#D10-S4.2, #D10-S5.5).
 *
 * <p>D10-S4.2 makes this piggyback on {@code Snapshot} rather than travel as its own message, and
 * the reason is that it is useless on its own: reconciliation needs the acknowledgement and the
 * authoritative state <em>from the same tick</em>, and two messages that could arrive separately
 * would let a client compare a prediction against a state one of them had not caught up to.
 *
 * <p>Mutable and reused, like the other per-tick records in this package: it is written once per
 * snapshot per peer.
 */
public final class InputAck {

    /** The newest {@code InputCommand.sequence} the authority has applied. */
    public int lastProcessedSequence;

    /** The tick it applied that command on. */
    public long lastProcessedTick;

    public void set(int lastProcessedSequence, long lastProcessedTick) {
        this.lastProcessedSequence = lastProcessedSequence;
        this.lastProcessedTick = lastProcessedTick;
    }

    public void reset() {
        lastProcessedSequence = 0;
        lastProcessedTick = 0L;
    }
}
