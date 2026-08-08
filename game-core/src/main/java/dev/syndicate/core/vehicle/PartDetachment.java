/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.damage.DetachReason;
import dev.syndicate.core.damage.PartDetachedEvent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.VehicleCompound;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removing a part, and everything below it, from a vehicle
 * (docs/05_vehicle_part_system.md#D05-S5.5).
 *
 * <p>D07-R23 puts this operation in exactly one place: D05-S5.5 says <em>what</em> detachment does
 * to a vehicle, and D07-S5.7 says <em>when</em> it happens. Both {@code FractureSystem} (slot 13)
 * and {@code DetachSystem} (slot 14) need the same structural change, so it lives here as a shared
 * operation rather than in whichever system was written first. This is not a system calling another
 * system (D04-R13 prohibits that): it is a function over components that both systems apply.
 *
 * <p><b>What this does not do.</b> It does not spawn the world objects the subtree becomes — a
 * fractured part becomes shards and a merely detached one becomes a single debris body, and only the
 * caller knows which (D05-S5.5 step 4). It does not recompute mass, COM or inertia either:
 * {@code MassPropertySystem} in slot 15 does that from the structural state this leaves behind,
 * which is what satisfies G10 within the same tick (D04-E11).
 *
 * <p><b>It does record where each part was and how fast it was going.</b> An attached part's
 * placement is derived from the slot chain it is about to leave, and its velocity from the vehicle
 * body it is about to stop belonging to — so both are unrecoverable a line after the removal.
 * Writing them onto the part's own {@code Transform} and {@code Velocity} is what lets the caller
 * spawn the world object at all, and lets a caller that is <em>not</em> the one that detached the
 * part do it too: {@code FractureSystem} detaches a whole subtree and only turns its root into
 * shards, and {@code DetachSystem} finds the rest of that subtree by these components alone.
 *
 * <p><b>Detachment is one-way</b> (G9, D07-R21). A part that has left never rejoins the graph.
 */
public final class PartDetachment {

    private static final Logger LOG = LoggerFactory.getLogger(PartDetachment.class);

    private PartDetachment() {
        throw new AssertionError("no instances");
    }

    /**
     * Detaches {@code partEntity} and its whole subtree from {@code vehicleEntity}.
     *
     * <p>The subtree goes in reverse topological order — deepest slot paths first — so a part is
     * always removed after the parts it carries. Removing a parent first would leave its children
     * addressing a slot path that no longer resolves, and their compound children would survive as
     * geometry belonging to nothing.
     *
     * @param reason why the named part left; its descendants are reported as
     *     {@link DetachReason#PARENT_DETACHED}, because "a part carries its children with it" is a
     *     different fact from what happened to the part itself
     * @return the detached entities, deepest first — the order a caller should turn them into world
     *     objects in
     * @throws IllegalArgumentException if asked to detach the chassis, which is impossible by
     *     construction (D05-R26): the chassis reaching 0 HP wrecks the vehicle, which detaches
     *     everything else
     */
    public static List<Integer> detach(
            World world, ShapeCache shapes, int vehicleEntity, int partEntity, DetachReason reason, long tick) {

        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
        if (graph == null || partRef == null) {
            return List.of();
        }
        if (chassis != null && chassis.chassisPartEntity == partEntity) {
            throw new IllegalArgumentException(
                    "the chassis cannot be detached (D05-R26); wrecking the vehicle is " + "what removes it");
        }

        String rootPath = partRef.slotPath;
        List<SlotNode> subtree = new ArrayList<>();
        for (int i = 0; i < graph.nodes.size(); i++) {
            SlotNode node = graph.nodes.get(i);
            if (SlotChain.isAtOrBeneath(node.slotPath, rootPath)) {
                subtree.add(node);
            }
        }
        if (subtree.isEmpty()) {
            // Already detached, or never attached. Detachment is one-way (G9), so this is a no-op
            // rather than an error: two triggers firing on the same part in one tick is normal.
            return List.of();
        }
        // Reverse topological order: a slot path sorts after every path it extends, so descending
        // lexicographic order visits children before parents (G3).
        subtree.sort(Comparator.comparing((SlotNode node) -> node.slotPath).reversed());

        // Captured before the first removal: a slot chain built from a half-emptied graph would
        // place the parts still in it correctly and the ones already gone not at all.
        SlotChain chain = SlotChain.of(graph, chassis);
        Motion motion = Motion.of(world, vehicleEntity);

        VehicleCompound compound = shapes == null ? null : shapes.vehicleCompound(vehicleEntity);
        List<Integer> detached = new ArrayList<>(subtree.size());

        for (int i = 0; i < subtree.size(); i++) {
            SlotNode node = subtree.get(i);
            int childEntity = node.childEntity;

            // 1. Leave the graph (D05-S5.5 step 1).
            graph.nodes.remove(node);
            graph.parentOf.remove(childEntity);

            // Recorded before the vehicle reference goes, because that reference is how the
            // placement is derived (D05-S5.5 step 4).
            motion.recordPlacement(world, childEntity, chain.transformOf(node.slotPath));

            PartRefComponent childRef = world.getComponent(childEntity, PartRefComponent.class);
            if (childRef != null) {
                childRef.vehicleEntity = EntityId.NULL;
            }
            SlotAttachmentComponent attachment = world.getComponent(childEntity, SlotAttachmentComponent.class);
            if (attachment != null) {
                attachment.parentEntity = EntityId.NULL;
            }
            markDetached(world, childEntity, tick);

            // 2. Leave the physics profile (D05-S5.5 step 2).
            PartStatsComponent stats = world.getComponent(childEntity, PartStatsComponent.class);
            PartCategory category = stats == null ? PartCategory.DECORATIVE : stats.category;
            if (category == PartCategory.WHEEL) {
                removeWheel(world, chassis, childEntity);
            } else if (compound != null && !compound.removeChild(node.slotPath)) {
                LOG.warn(
                        "part {} at {} was in the slot graph but not in the vehicle's compound shape; "
                                + "the compound and the graph have drifted apart (D06-S5.3)",
                        EntityId.toString(childEntity),
                        node.slotPath);
            }

            detached.add(childEntity);
            world.events()
                    .emit(new PartDetachedEvent(
                            vehicleEntity,
                            childEntity,
                            node.slotPath,
                            node.slotPath.equals(rootPath) ? reason : DetachReason.PARENT_DETACHED,
                            tick));
        }

        // 3. Structural bookkeeping (D05-S5.5 step 5, D05-R12). Bumping the version is what tells
        //    MassPropertySystem, VehicleStatsSystem and replication that every cache derived from
        //    this graph is stale — G10 depends on it happening here and not being forgotten.
        graph.structuralVersion++;
        VehicleStatsComponent vehicleStats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        if (vehicleStats != null) {
            vehicleStats.dirty = true;
        }
        return detached;
    }

    /** The mass a subtree would take with it, for a caller that needs it before committing. */
    public static float subtreeMassKg(World world, List<Integer> partEntities) {
        float total = 0f;
        for (int i = 0; i < partEntities.size(); i++) {
            RigidBodyComponent body = world.getComponent(partEntities.get(i), RigidBodyComponent.class);
            if (body != null) {
                total += body.massKg;
            }
        }
        return total;
    }

    /**
     * The vehicle's motion at the moment of a detach, and the placement it gives each leaving part.
     *
     * <p>One instance per {@code detach} call rather than static scratch: a structural change is a
     * rare event, not a per-tick allocation, and shared mutable scratch on a static utility would be
     * unusable the moment two vehicles lose a part in one tick.
     */
    private static final class Motion {

        private final Matrix4 chassisToWorld = new Matrix4();
        private final Matrix4 partWorld = new Matrix4();
        private final Vector3 comWorld = new Vector3();
        private final Vector3 linear = new Vector3();
        private final Vector3 angular = new Vector3();
        private final Vector3 position = new Vector3();
        private final boolean placed;

        private Motion(World world, int vehicleEntity) {
            placed = PartPlacement.chassisToWorld(world, vehicleEntity, chassisToWorld, comWorld);
            VelocityComponent velocity = world.getComponent(vehicleEntity, VelocityComponent.class);
            if (velocity != null) {
                linear.set(velocity.linear);
                angular.set(velocity.angular);
            }
        }

        static Motion of(World world, int vehicleEntity) {
            return new Motion(world, vehicleEntity);
        }

        /**
         * Writes a leaving part's world transform and the velocity it leaves with onto the part.
         *
         * <p>The components are added when the part has none: an attached part needs neither, since
         * its placement is the slot chain's and its motion is the vehicle's, and a spawn path is free
         * not to give it either. A detached part needs both, so this is where they start existing.
         *
         * @param chassisLocal the part's accumulated slot-chain transform, or null when the graph
         *     could not place it — in which case whatever transform the part already has is left
         *     alone rather than replaced with a wrong one
         */
        void recordPlacement(World world, int partEntity, Matrix4 chassisLocal) {
            if (!placed || chassisLocal == null) {
                return;
            }
            partWorld.set(chassisToWorld).mul(chassisLocal);
            partWorld.getTranslation(position);

            TransformComponent transform = world.getComponent(partEntity, TransformComponent.class);
            if (transform == null) {
                transform = new TransformComponent();
                world.addComponent(partEntity, transform);
            }
            // World space from here on: the part has no parent to be relative to (D04-S4.3.1).
            transform.parent = EntityId.NULL;
            transform.position.set(position);
            partWorld.getRotation(transform.rotation, true);
            transform.dirty = true;

            VelocityComponent velocity = world.getComponent(partEntity, VelocityComponent.class);
            if (velocity == null) {
                velocity = new VelocityComponent();
                world.addComponent(partEntity, velocity);
            }
            // v + ω × r at the part's own position (D05-R23). The vehicle's own velocity is left
            // untouched: the departing mass carries its momentum away, and "correcting" the
            // vehicle for it would create momentum rather than conserve it.
            PartPlacement.velocityAt(linear, angular, comWorld, position, velocity.linear);
            velocity.angular.set(angular);
        }
    }

    private static void markDetached(World world, int partEntity, long tick) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        if (damageState == null || damageState.state == DamageState.DETACHED) {
            return;
        }
        damageState.state = DamageState.DETACHED;
        damageState.stateEnteredTick = tick;
        damageState.stateVersion++;
    }

    /**
     * Removes a wheel and re-densifies the remaining wheel indices (D05-R24).
     *
     * <p>{@code btRaycastVehicle} indexes wheels positionally and densely, so removing wheel 1 of 4
     * shifts wheels 2 and 3 down to 1 and 2. Every {@code WheelControllerComponent.wheelIndex} must
     * move with them in the same operation or the vehicle drives and steers the wrong wheels — a
     * failure that is silent, intermittent, and looks like a suspension bug.
     *
     * <p>The native side of this is not done here. gdx-bullet 1.14.2's {@code btRaycastVehicle}
     * exposes {@code addWheel} and no removal, so a wheel can only leave by rebuilding the
     * controller — which belongs to {@code VehicleControlSystem} (slot 7), the class that builds it
     * (DEV-008). This keeps the ECS bookkeeping correct so that rebuild has something correct to
     * read.
     */
    private static void removeWheel(World world, VehicleChassisComponent chassis, int wheelEntity) {
        if (chassis == null) {
            return;
        }
        int removedIndex = -1;
        for (int i = 0; i < chassis.wheelCount; i++) {
            if (chassis.wheelEntities[i] == wheelEntity) {
                removedIndex = i;
                break;
            }
        }
        if (removedIndex < 0) {
            return;
        }
        for (int i = removedIndex; i < chassis.wheelCount - 1; i++) {
            chassis.wheelEntities[i] = chassis.wheelEntities[i + 1];
        }
        chassis.wheelEntities[chassis.wheelCount - 1] = EntityId.NULL;
        chassis.wheelCount--;

        for (int i = 0; i < chassis.wheelCount; i++) {
            WheelControllerComponent wheel =
                    world.getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
            if (wheel != null) {
                wheel.wheelIndex = i;
            }
        }
    }
}
