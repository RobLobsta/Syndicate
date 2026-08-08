/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;

/**
 * One received transform, timestamped, as buffered for client-side interpolation
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/10_networking_multiplayer.md#D10-S5.6).
 *
 * <p>Mutable and preallocated inside a {@code RingBuffer} for the same reason as
 * {@link InputCommand}: one sample per remote entity per snapshot at 20 Hz is continuous garbage
 * otherwise (D04-S5.6).
 *
 * <p>The buffer this lives in feeds a <b>cosmetic</b> component ({@code InterpolationComponent}), so
 * nothing here may ever be read by a gameplay system — an interpolated position is a render-time
 * approximation, and letting it back into the simulation is precisely the feedback G6 forbids.
 */
public final class TransformSample {

    /** The authority tick this sample describes. */
    public long tick;

    /** World-space position in metres. */
    public final Vector3 position = new Vector3();

    /** Unit quaternion orientation. */
    public final Quaternion rotation = new Quaternion();

    /** Copies every field from {@code other}. */
    public void set(TransformSample other) {
        tick = other.tick;
        position.set(other.position);
        rotation.set(other.rotation);
    }

    /** Returns to tick 0 at the origin with identity rotation. */
    public void reset() {
        tick = 0L;
        position.set(0f, 0f, 0f);
        rotation.idt();
    }
}
