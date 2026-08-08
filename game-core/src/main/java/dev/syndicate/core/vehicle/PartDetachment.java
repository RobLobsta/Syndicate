/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
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

        VehicleCompound compound = shapes == null ? null : shapes.vehicleCompound(vehicleEntity);
        List<Integer> detached = new ArrayList<>(subtree.size());

        for (int i = 0; i < subtree.size(); i++) {
            SlotNode node = subtree.get(i);
            int childEntity = node.childEntity;

            // 1. Leave the graph (D05-S5.5 step 1).
            graph.nodes.remove(node);
            graph.parentOf.remove(childEntity);

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
