/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.net.InputCommand;
import dev.syndicate.core.util.RingBuffer;

/**
 * The unacknowledged inputs a client replays after a server correction
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/10_networking_multiplayer.md#D10-S5.5).
 *
 * <p>Present only on the local player's own vehicle. Prediction exists so the driver's own steering
 * feels instant; it is meaningless for remote entities, which are interpolated instead.
 *
 * <p>Capacity is the 128 commands of D04-S5.6 — a little over two seconds of input at
 * {@code TICK_RATE_HZ}. A client that has gone longer than that without an acknowledgement has lost
 * the connection, not fallen behind, so growing the buffer would only delay noticing.
 */
public final class PredictionComponent implements Component {

    /** Inputs sent but not yet acknowledged, newest first. */
    public final RingBuffer<InputCommand> pendingInputs = new RingBuffer<>(CAPACITY, InputCommand::new);

    /** The most recent tick the authority has confirmed. */
    public long lastAckedTick;

    /** D04-S5.6: fixed capacity 128 per client. */
    public static final int CAPACITY = 128;

    @Override
    public void reset() {
        pendingInputs.clear();
        lastAckedTick = 0L;
    }
}
