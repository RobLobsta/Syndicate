/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PendingImpulse;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 10: steps the Bullet world and mirrors its state back into components
 * (docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.4).
 *
 * <p>The tick's three parts are fixed:
 *
 * <ol>
 *   <li>apply the impulses queued since the last step, in ascending entity id order (G3);
 *   <li>step exactly one {@code TICK_DT} (G2);
 *   <li>pull each body's transform and velocity into its components, in ascending entity id order.
 * </ol>
 *
 * <p>Nothing is pushed <em>into</em> Bullet here beyond those impulses. Vehicle engine, steering and
 * brake forces are already applied by {@code VehicleControlSystem} in slot 7, and structural changes
 * are prohibited during the step — detachment happens in POST_SIM slot 14 and mass properties are
 * reconciled in slot 15, before the next tick's step, which is what satisfies G10 (D04-E11).
 *
 * <p>This system does not own the {@link PhysicsWorld} it steps: the world outlives the schedule and
 * is disposed after every entity has been destroyed (D03-S5.6), so {@link #dispose()} deliberately
 * leaves it alone.
 */
public final class PhysicsSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(PhysicsSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 10;

    private final PhysicsWorld physics;

    private Family bodies;

    // Scratch, reused every tick: the pull-back runs once per body per tick and must not allocate
    // (D04-S5.3's per-tick zero-GC budget).
    private final Matrix4 scratchTransform = new Matrix4();
    private final Vector3 scratchPosition = new Vector3();
    private final Quaternion scratchRotation = new Quaternion();
    private final Vector3 scratchLinear = new Vector3();
    private final Vector3 scratchAngular = new Vector3();

    public PhysicsSystem(PhysicsWorld physics) {
        this.physics = Objects.requireNonNull(physics, "physics");
    }

    @Override
    public Phase phase() {
        return Phase.SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        // Built once, maintained incrementally. A body without a transform has nowhere to report to,
        // so the family requires both; velocity is optional and looked up per entity.
        bodies = world.family(ComponentQuery.all(RigidBodyComponent.class, TransformComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        // AC-D06-1 / T-D06-1. A variable dt here would make results depend on frame rate, which is
        // the whole of G2; it is rejected rather than clamped, because a caller that got this wrong
        // has a broken accumulator and silently accepting one step would hide it.
        if (dtSeconds != SimulationConstants.TICK_DT) {
            throw new IllegalArgumentException("PhysicsSystem requires exactly TICK_DT (" + SimulationConstants.TICK_DT
                    + "s), got " + dtSeconds + "s (G2, D06-R15)");
        }

        applyQueuedImpulses(world);
        physics.step();
        pullBackState(world, tick);
    }

    /** Step 1 of D06-S5.4: drain the impulse queue into Bullet, deterministically ordered. */
    private void applyQueuedImpulses(World world) {
        List<PendingImpulse> queued = physics.drainQueuedImpulses();
        for (int i = 0; i < queued.size(); i++) {
            PendingImpulse pending = queued.get(i);
            RigidBodyComponent rigidBody = world.getComponent(pending.entityId(), RigidBodyComponent.class);
            if (rigidBody == null || rigidBody.body == null) {
                // The entity died between queueing and the step. Dropping the impulse is correct:
                // there is nothing left to push, and resurrecting the body would be worse.
                continue;
            }
            btRigidBody body = rigidBody.body;
            switch (pending.kind()) {
                case CENTRAL -> body.applyCentralImpulse(pending.impulse());
                case AT_POINT -> body.applyImpulse(pending.impulse(), pending.relativePosition());
                case TORQUE -> body.applyTorqueImpulse(pending.impulse());
            }
            // A sleeping body ignores an impulse until something wakes it, which would silently
            // swallow the first hit on a settled wreck.
            body.activate(true);
        }
    }

    /**
     * Step 3 of D06-S5.4: Bullet's state becomes component state, for every body, in ascending
     * entity id order.
     *
     * <p>Values are read into scratch and checked before anything is written. D00-R13 forbids
     * storing a NaN at all, so a body that has gone non-finite must be caught before its transform
     * reaches a component that replication, AI, or the render path would then read.
     */
    private void pullBackState(World world, long tick) {
        int count = bodies.size();
        int[] entityIds = bodies.snapshot();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            RigidBodyComponent rigidBody = world.getComponent(entityId, RigidBodyComponent.class);
            TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
            if (rigidBody == null || transform == null || rigidBody.body == null) {
                continue;
            }
            btRigidBody body = rigidBody.body;

            body.getWorldTransform(scratchTransform);
            scratchTransform.getTranslation(scratchPosition);
            scratchTransform.getRotation(scratchRotation, true);
            scratchLinear.set(body.getLinearVelocity());
            scratchAngular.set(body.getAngularVelocity());

            if (!isFinite(scratchPosition)
                    || !isFinite(scratchRotation)
                    || !isFinite(scratchLinear)
                    || !isFinite(scratchAngular)) {
                removeNonFiniteBody(world, entityId, body, tick);
                continue;
            }

            transform.position.set(scratchPosition);
            transform.rotation.set(scratchRotation);
            transform.dirty = true;

            VelocityComponent velocity = world.getComponent(entityId, VelocityComponent.class);
            if (velocity != null) {
                velocity.linear.set(scratchLinear);
                velocity.angular.set(scratchAngular);
            }
        }
    }

    /**
     * D06-E2: a body whose state has gone non-finite is removed from the world and its entity
     * destroyed.
     *
     * <p>Never a throw and never a clamp. One NaN body poisons every solver island it touches within
     * a few ticks, so the only recovery is to get it out of the world immediately; the entity's
     * natives are then released by {@code EntityDestroySystem} in slot 27, off the step (D04-R15).
     */
    private void removeNonFiniteBody(World world, int entityId, btRigidBody body, long tick) {
        LOG.error(
                "non-finite body state on entity {} at tick {} (position={}, linear={}); "
                        + "removing the body and destroying the entity (D06-E2, D00-R13)",
                EntityId.toString(entityId),
                tick,
                scratchPosition,
                scratchLinear);
        physics.removeNonFiniteBody(body);
        world.destroyEntity(entityId);
    }

    private static boolean isFinite(Vector3 vector) {
        return Float.isFinite(vector.x) && Float.isFinite(vector.y) && Float.isFinite(vector.z);
    }

    private static boolean isFinite(Quaternion quaternion) {
        return Float.isFinite(quaternion.x)
                && Float.isFinite(quaternion.y)
                && Float.isFinite(quaternion.z)
                && Float.isFinite(quaternion.w);
    }
}
