/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.EntityId;

/**
 * One impulse waiting to be applied at the start of the next Bullet step
 * (docs/06_physics_simulation.md#D06-S5.4).
 *
 * <p>The vectors are copied at construction rather than referenced. Callers hand over scratch
 * vectors they go on to mutate — a queue that stored the reference would apply whatever the caller
 * happened to leave in it several systems later, which is the kind of bug that reproduces only under
 * load.
 *
 * @param entityId whose body receives it; the impulse is dropped if the entity dies first
 * @param sequence queue order, which breaks ties between two impulses on the same entity
 * @param kind how {@link #impulse} is interpreted
 * @param impulse N·s for {@link Kind#CENTRAL} and {@link Kind#AT_POINT}, N·m·s for {@link Kind#TORQUE}
 * @param relativePosition world-space offset from the centre of mass; only read for {@link Kind#AT_POINT}
 */
public record PendingImpulse(int entityId, long sequence, Kind kind, Vector3 impulse, Vector3 relativePosition) {

    /** How a queued impulse applies to its body. */
    public enum Kind {
        /** Through the centre of mass: changes velocity, never spin. */
        CENTRAL,
        /** At an offset from the centre of mass: changes both. */
        AT_POINT,
        /** Pure angular impulse. */
        TORQUE
    }

    public PendingImpulse {
        if (entityId == EntityId.NULL) {
            throw new IllegalArgumentException("impulse queued for the null entity");
        }
        requireFinite(impulse, "impulse");
        requireFinite(relativePosition, "relativePosition");
        impulse = new Vector3(impulse);
        relativePosition = new Vector3(relativePosition);
    }

    private static void requireFinite(Vector3 vector, String name) {
        if (vector == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (!Float.isFinite(vector.x) || !Float.isFinite(vector.y) || !Float.isFinite(vector.z)) {
            // D00-R13: NaN and Inf are always errors. Rejecting at the queue keeps a bad value from
            // reaching the solver, where one non-finite body corrupts every island it touches.
            throw new IllegalArgumentException(name + " must be finite, got " + vector);
        }
    }
}
