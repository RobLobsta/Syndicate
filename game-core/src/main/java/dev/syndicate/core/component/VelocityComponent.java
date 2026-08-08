/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.Component;

/**
 * An entity's velocity, mirrored out of Bullet each tick
 * (docs/04_entity_component_model.md#D04-S4.3.1).
 *
 * <p>Duplicating state Bullet already holds is deliberate. Systems that need velocity — scoring,
 * AI, replication, the debris budget — would otherwise each reach into a native object, and every
 * such reach is a JNI crossing and a place where a stale body pointer becomes a crash rather than a
 * null check. {@code PhysicsSystem} (slot 10) writes this once per tick; everything downstream
 * reads it.
 */
public final class VelocityComponent implements Component {

    /** Metres per second, world space. */
    public final Vector3 linear = new Vector3();

    /** Radians per second, world space. */
    public final Vector3 angular = new Vector3();

    @Override
    public void reset() {
        linear.set(0f, 0f, 0f);
        angular.set(0f, 0f, 0f);
    }
}
