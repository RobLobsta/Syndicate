/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.Component;

/**
 * A projectile integrated outside Bullet (docs/04_entity_component_model.md#D04-S4.3.1,
 * docs/07_damage_destruction_model.md#D07-S5.2).
 *
 * <p>Projectiles carry this <em>instead of</em> {@code RigidBodyComponent} when their flight is a
 * closed-form arc plus a ray cast per tick. A rigid body per bullet would put hundreds of tiny
 * fast-moving objects through the broadphase, where they also tunnel; a swept ray cast is both
 * cheaper and more accurate for the same trajectory.
 */
public final class BallisticMotionComponent implements Component {

    /** Metres per second, world space. */
    public final Vector3 velocity = new Vector3();

    /** Multiplier on world gravity. {@code 0} gives a straight line; {@code 1} a normal arc. */
    public float gravityScale = 1f;

    /** Linear drag coefficient; {@code 0} disables drag. */
    public float dragCoefficient;

    @Override
    public void reset() {
        velocity.set(0f, 0f, 0f);
        gravityScale = 1f;
        dragCoefficient = 0f;
    }
}
