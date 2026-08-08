/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.net.TransformSample;
import dev.syndicate.core.util.RingBuffer;

/**
 * The snapshot history a client smooths a remote entity's motion from
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/10_networking_multiplayer.md#D10-S5.6).
 *
 * <p>Classified {@code C}: what comes out of this buffer is a render-time position between two
 * authoritative ones. It is <em>not</em> where the entity is, and no gameplay system may read it —
 * a hit test against an interpolated transform would resolve against a position the authority never
 * simulated (G6).
 *
 * <p>The buffer holds a second of history at the snapshot rate, which is enough for the
 * interpolation delay of D10-S5.6 plus margin for a few dropped packets. Longer would let a client
 * fall further behind before it noticed; shorter would stutter on the first loss.
 */
public final class InterpolationComponent implements Component {

    /** Snapshot transforms, newest first. */
    public final RingBuffer<TransformSample> buffer = new RingBuffer<>(CAPACITY, TransformSample::new);

    /** One second of history at {@code SNAPSHOT_RATE_HZ}. */
    public static final int CAPACITY = 20;

    @Override
    public void reset() {
        // Clears without discarding the preallocated samples: the ring exists to be reused.
        buffer.clear();
    }
}
