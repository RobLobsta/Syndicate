/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.dynamics.btTypedConstraint;
import com.badlogic.gdx.physics.bullet.linearmath.btMotionState;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.vehicle.SlotNode;
import java.util.Arrays;
import java.util.Objects;

/**
 * Schedule slot 27: tears down entities queued for destruction
 * (docs/04_entity_component_model.md#D04-S5.5).
 *
 * <p>This system runs in the CLEANUP phase, after all gameplay systems have finished. It is
 * responsible for:
 *
 * <ol>
 *   <li>Cascading destruction to child entities (e.g. parts of a vehicle).
 *   <li>Disposing native resources in dependency order (constraints, then bodies).
 *   <li>Returning components to their pools and recycling the entity ID.
 * </ol>
 *
 * <p>By deferring native teardown to this system, no other system can read a half-destroyed entity,
 * and native disposal never races Bullet's step (D04-R15, D04-E5).
 */
public final class EntityDestroySystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 27;

    private final PhysicsWorld physics;

    /**
     * Nullable. A vehicle's compound shape is per-instance and mutable, so unlike a part hull it has
     * a single owner that dies with it (D06-R8, D02-S5.7 rule 2); the cache is handed over so slot 27
     * can release it. A schedule built without a shape cache — the physics-only test scenes — leaves
     * this null and simply skips that release.
     */
    private final ShapeCache shapes;

    public EntityDestroySystem(PhysicsWorld physics) {
        this(physics, null);
    }

    public EntityDestroySystem(PhysicsWorld physics, ShapeCache shapes) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.shapes = shapes;
    }

    @Override
    public Phase phase() {
        return Phase.CLEANUP;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int start = 0;

        // 1. Expand the queue recursively by traversing children.
        while (start < world.destroyQueueSize()) {
            int end = world.destroyQueueSize();

            // G3: teardown order is deterministic even when destroy calls arrive in a data-dependent order.
            Arrays.sort(world.destroyQueue(), start, end);

            for (int i = start; i < end; i++) {
                int entityId = world.destroyQueue()[i];
                Entity entity = world.getEntityForTeardown(entityId);

                if (entity == null) {
                    continue;
                }

                int chassisTypeIndex = world.componentTypes().indexOfOrAbsent(VehicleChassisComponent.class);
                if (chassisTypeIndex >= 0 && entity.componentAt(chassisTypeIndex) != null) {
                    VehicleChassisComponent chassis = (VehicleChassisComponent) entity.componentAt(chassisTypeIndex);
                    for (int w = 0; w < chassis.wheelCount; w++) {
                        int wheelId = chassis.wheelEntities[w];
                        if (wheelId != EntityId.NULL) {
                            world.destroyEntity(wheelId);
                        }
                    }
                    if (chassis.chassisPartEntity != EntityId.NULL) {
                        world.destroyEntity(chassis.chassisPartEntity);
                    }
                }

                int graphTypeIndex = world.componentTypes().indexOfOrAbsent(SlotGraphComponent.class);
                if (graphTypeIndex >= 0 && entity.componentAt(graphTypeIndex) != null) {
                    SlotGraphComponent graph = (SlotGraphComponent) entity.componentAt(graphTypeIndex);
                    for (int n = 0; n < graph.nodes.size(); n++) {
                        SlotNode node = graph.nodes.get(n);
                        if (node.childEntity != EntityId.NULL) {
                            world.destroyEntity(node.childEntity);
                        }
                    }
                }
            }

            start = end;
        }

        // 2. Tear down constraints for all entities in the queue first.
        // This guarantees a constraint joining two bodies is removed before either body is destroyed.
        int attachmentTypeIndex = world.componentTypes().indexOfOrAbsent(SlotAttachmentComponent.class);
        if (attachmentTypeIndex >= 0) {
            for (int i = 0; i < world.destroyQueueSize(); i++) {
                int entityId = world.destroyQueue()[i];
                Entity entity = world.getEntityForTeardown(entityId);
                if (entity != null && entity.componentAt(attachmentTypeIndex) != null) {
                    SlotAttachmentComponent attachment =
                            (SlotAttachmentComponent) entity.componentAt(attachmentTypeIndex);
                    if (attachment.constraintHandle != null) {
                        btTypedConstraint handle = attachment.constraintHandle;
                        // Through PhysicsWorld rather than its dynamicsWorld: the world tracks its
                        // own constraints (D06-S5.6), and a constraint removed behind its back is
                        // one it would try to free again at teardown.
                        physics.removeConstraint(handle);
                        handle.dispose();
                        dev.syndicate.core.util.NativeResourceTracker.release(
                                handle.getClass().getSimpleName());
                        attachment.constraintHandle = null;
                    }
                }
            }
        }

        // 3. Tear down bodies and recycle entities.
        int rigidBodyTypeIndex = world.componentTypes().indexOfOrAbsent(RigidBodyComponent.class);
        for (int i = 0; i < world.destroyQueueSize(); i++) {
            int entityId = world.destroyQueue()[i];
            Entity entity = world.getEntityForTeardown(entityId);
            if (entity == null) {
                continue;
            }

            // The ray-cast controller goes before the chassis body it wraps (D02-S5.7 rule 5): it
            // holds a pointer to that body and is stepped as a world action, so a controller left
            // behind dereferences freed memory on the next step. PhysicsWorld owns it, unlike the
            // body, so this both removes and disposes (D06-S5.5).
            int chassisIndex = world.componentTypes().indexOfOrAbsent(VehicleChassisComponent.class);
            if (chassisIndex >= 0 && entity.componentAt(chassisIndex) != null) {
                VehicleChassisComponent chassis = (VehicleChassisComponent) entity.componentAt(chassisIndex);
                if (chassis.vehicleController != null) {
                    physics.removeRaycastVehicle(chassis.vehicleController);
                    chassis.vehicleController = null;
                }
            }

            if (rigidBodyTypeIndex >= 0 && entity.componentAt(rigidBodyTypeIndex) != null) {
                RigidBodyComponent rigidBody = (RigidBodyComponent) entity.componentAt(rigidBodyTypeIndex);
                if (rigidBody.body != null) {
                    btRigidBody body = rigidBody.body;
                    btMotionState motionState = rigidBody.motionState;
                    physics.removeBody(body);
                    if (motionState != null) {
                        motionState.dispose();
                        dev.syndicate.core.util.NativeResourceTracker.release(
                                motionState.getClass().getSimpleName());
                        rigidBody.motionState = null;
                    }
                    body.dispose();
                    dev.syndicate.core.util.NativeResourceTracker.release(
                            body.getClass().getSimpleName());
                    rigidBody.body = null;
                }
            }

            // The vehicle's compound shape dies with the vehicle — it is per-instance and mutable, so
            // nothing else can be using it — but only *after* the body that references it has been
            // disposed (D02-S5.7 rule 5). Its child hulls are shared by every instance of the same
            // part type and survive until the cache itself is disposed (D06-R8).
            if (shapes != null) {
                shapes.releaseVehicleCompound(entityId);
            }

            world.recycleEntity(entityId);
        }

        world.clearDestroyQueue();
    }
}
