/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btMotionState;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.physics.ShapeCacheKey;
import dev.syndicate.model.CollisionLayer;

/**
 * An entity's presence in the Bullet world (docs/04_entity_component_model.md#D04-S4.3.1).
 *
 * <p><b>Native ownership (G19, D02-S5.7 rule 3).</b> This component owns {@link #body} and
 * {@link #motionState} and is responsible for disposing both, in the deferred-destroy phase and
 * never during {@code stepSimulation} (D04-E5). It does <b>not</b> own the collision shape: shapes
 * are owned by the shape cache and shared by reference, which is why {@link #shapeKey} is a value
 * key rather than a native pointer. Disposing a shape from here would free it out from under every
 * other body using the same asset.
 */
public final class RigidBodyComponent implements Component {

    /** OWNER: this component. Disposed in {@code EntityDestroySystem} after removal from the world. */
    public btRigidBody body;

    /** OWNER: this component. Disposed after {@link #body}. */
    public btMotionState motionState;

    /** Which cached shape the body uses. The cache owns the shape itself. */
    public ShapeCacheKey shapeKey;

    /** Kilograms. {@code 0} means static; anything else must be at least {@code MIN_BODY_MASS_KG}. */
    public float massKg;

    /** kg·m². Derived from the shape and mass; recomputed by {@code MassPropertySystem}. */
    public final Vector3 localInertia = new Vector3();

    /** Metres, in body-local space. Derived; updated in the same tick as any attach/detach (G10). */
    public final Vector3 centerOfMassLocal = new Vector3();

    /** Which layer the body belongs to (D06-S4.4). */
    public CollisionLayer layer = CollisionLayer.DEBRIS;

    /** Bitmask of the layers this body collides with (D06-S4.4). */
    public int mask;

    /** Kinematic bodies are moved by gameplay and are not integrated by the solver. */
    public boolean isKinematic;

    @Override
    public void reset() {
        // Deliberately nulls rather than disposes: disposal order across a whole entity is the
        // destroy queue's responsibility (D02-S5.7 rule 5), and a component that disposed on
        // reset() would free a body the world still holds a pointer to.
        body = null;
        motionState = null;
        shapeKey = null;
        massKg = 0f;
        localInertia.set(0f, 0f, 0f);
        centerOfMassLocal.set(0f, 0f, 0f);
        layer = CollisionLayer.DEBRIS;
        mask = 0;
        isKinematic = false;
    }
}
