/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import java.util.BitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 21: every entity's world matrix, composed down the transform tree
 * (docs/04_entity_component_model.md#D04-S4.4, #D04-S4.3.1).
 *
 * <p>{@code TransformComponent.position} and {@code rotation} are world space for a root and
 * parent-local for anything with a parent (D04-S4.3.1), so a part's place in the world is the
 * product of its own offset with every offset above it. This is the one system that resolves that
 * product, and everything that draws, aims or reports a position reads {@code worldMatrix} rather
 * than recomputing it.
 *
 * <p><b>A wheel is the exception, and it is the interesting one.</b> A ray-cast wheel is not where
 * its slot says: it is a radius above whatever the suspension ray found, turned by the steering
 * angle, and spun about its axle by however far the car has travelled. None of that is in the slot
 * graph — Bullet accumulates it inside {@code btRaycastVehicle} — so a wheel's world matrix is taken
 * from {@code getWheelTransformWS} instead of being composed. That is what makes a wheel follow a
 * kerb and turn as the car moves, and a wheel resolved the ordinary way would slide along the road
 * without rotating.
 *
 * <p><b>The chassis part's local transform is the centre-of-mass offset.</b> A vehicle's rigid body
 * has its origin at the COM (D06-S5.7 step 2) while its art is authored about the chassis mesh
 * origin, so the chassis part hangs off the vehicle at {@code -comLocal} and every other part hangs
 * off the chassis at its slot offset. Written here rather than at spawn because the COM moves every
 * time a part comes off, and a stale offset would slide the whole body sideways under the wheels.
 *
 * <p>Runs per tick rather than per frame (D04-R7): a headless server has no frames and still needs
 * these matrices for hit resolution.
 */
public final class TransformSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(TransformSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 21;

    /**
     * How deep a transform chain may be before this system stops walking it.
     *
     * <p>A guard against a cycle, which the slot graph cannot express but a hand-built
     * {@code TransformComponent.parent} can. Sixteen is far past anything the deepest authored
     * assembly reaches — chassis, hardpoint, sub-mount, muzzle is four.
     */
    private static final int MAX_DEPTH = 16;

    private Family transforms;
    private Family vehicles;

    /** Entities whose world matrix has been composed this tick, by {@link EntityId#index}. */
    private final BitSet resolved = new BitSet();

    private final Matrix4 scratchLocal = new Matrix4();
    private boolean warnedAboutDepth;

    @Override
    public Phase phase() {
        return Phase.PRESENT;
    }

    /**
     * Per tick, not per frame — the one system in {@code PRESENT} that is (D04-R7).
     *
     * <p>A dedicated server never renders and still needs world matrices: hit resolution, AI sensing
     * and replication all read them, and a server whose transforms only updated on a frame that
     * never comes would resolve every shot against the spawn pose (G17).
     */
    @Override
    public boolean isPerFrame() {
        return false;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        transforms = world.family(ComponentQuery.all(TransformComponent.class));
        vehicles = world.family(
                ComponentQuery.all(VehicleChassisComponent.class, SlotGraphComponent.class, RigidBodyComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        resolved.clear();
        publishComOffsets(world);
        composeTree(world);
        takeWheelTransformsFromBullet(world);
    }

    /** Point each vehicle's chassis part back at the mesh origin its art is authored about. */
    private void publishComOffsets(World world) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            VehicleChassisComponent chassis = world.getComponent(entityIds[i], VehicleChassisComponent.class);
            if (chassis == null || chassis.chassisPartEntity == EntityId.NULL) {
                continue;
            }
            TransformComponent transform = world.getComponent(chassis.chassisPartEntity, TransformComponent.class);
            if (transform == null) {
                continue;
            }
            transform.position.set(chassis.comLocal).scl(-1f);
            transform.rotation.idt();
        }
    }

    /** Compose every transform, parents before children, in ascending entity id order (G3). */
    private void composeTree(World world) {
        int count = transforms.size();
        int[] entityIds = transforms.snapshot();
        for (int i = 0; i < count; i++) {
            compose(world, entityIds[i], 0);
        }
    }

    /**
     * Composes one entity's world matrix, resolving its parent first.
     *
     * <p>Recursive rather than a sorted sweep because entity ids carry no parent-before-child
     * ordering — a part created before a later respawn of its vehicle would break one — and the
     * {@link #resolved} set keeps the total work linear in the number of transforms however deep
     * the tree is.
     */
    private void compose(World world, int entity, int depth) {
        if (resolved.get(EntityId.index(entity))) {
            return;
        }
        TransformComponent transform = world.getComponent(entity, TransformComponent.class);
        if (transform == null) {
            return;
        }
        // Marked before the parent is resolved, so a cycle terminates on the second visit instead of
        // recursing to MAX_DEPTH once per entity in it.
        resolved.set(EntityId.index(entity));
        scratchLocal.set(transform.position, transform.rotation, transform.scale);

        TransformComponent parent = transform.parent == EntityId.NULL
                ? null
                : world.getComponent(transform.parent, TransformComponent.class);
        if (parent == null) {
            transform.worldMatrix.set(scratchLocal);
        } else if (depth >= MAX_DEPTH) {
            // The chain is deeper than any assembly should be, which means a cycle. Treating this
            // entity as a root keeps it somewhere finite instead of overflowing the stack.
            if (!warnedAboutDepth) {
                warnedAboutDepth = true;
                LOG.error(
                        "transform chain at {} is deeper than {}; treating it as a root. A parent cycle is "
                                + "the only way to reach this (D04-S4.3.1)",
                        EntityId.toString(entity),
                        MAX_DEPTH);
            }
            transform.worldMatrix.set(scratchLocal);
        } else {
            compose(world, transform.parent, depth + 1);
            transform.worldMatrix.set(parent.worldMatrix).mul(scratchLocal);
        }
        transform.dirty = false;
    }

    /**
     * Replaces each ray-cast wheel's composed matrix with the one Bullet is actually drawing it at.
     *
     * <p>Runs after {@link #composeTree}, so the slot-graph answer is what a wheel's own children
     * would inherit — nothing hangs off a wheel today, and when something does it will want the
     * spinning frame rather than the static one.
     */
    private void takeWheelTransformsFromBullet(World world) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            VehicleChassisComponent chassis = world.getComponent(entityIds[i], VehicleChassisComponent.class);
            if (chassis == null || chassis.vehicleController == null) {
                continue;
            }
            for (int w = 0; w < chassis.wheelCount; w++) {
                int wheelEntity = chassis.wheelEntities[w];
                WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
                TransformComponent transform = world.getComponent(wheelEntity, TransformComponent.class);
                if (wheel == null
                        || transform == null
                        || wheel.wheelIndex >= chassis.vehicleController.getNumWheels()) {
                    continue;
                }
                transform.worldMatrix.set(chassis.vehicleController.getWheelTransformWS(wheel.wheelIndex));
            }
        }
    }
}
