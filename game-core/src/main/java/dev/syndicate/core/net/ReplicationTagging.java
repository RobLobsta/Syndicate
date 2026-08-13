/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.core.component.NetworkReplicatedComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.SlotNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gives a vehicle and its parts their wire identities, the same way on every peer
 * (docs/04_entity_component_model.md#D04-S6.2, docs/10_networking_multiplayer.md#D10-S4.2).
 *
 * <p>The rule is one line and everything else follows from it: <b>a vehicle's ids are a contiguous
 * block, assigned in the order {@link #replicatedEntities} returns</b> — the vehicle, then its
 * chassis part, then every other part in ascending slot-path order. Both peers build the same
 * assembly through the same factory, so both walk the same list and assign the same ids without a
 * single id travelling on the wire beyond the block's base (DEC-059).
 *
 * <p>Slot-path order rather than entity-id order, because entity ids depend on what else the world
 * has spawned and recycled, and two peers do not agree on that. Slot paths come from the assembly
 * file and are identical everywhere (D05-R11).
 *
 * <p>A shared operation, not a system, for the reason DEC-016 gives for {@code PartDetachment}:
 * both slot 18 (on the authority, at spawn) and slot 19 (on a client, on {@code SpawnEntity}) need
 * it, and a system may not call another system (D04-R13).
 */
public final class ReplicationTagging {

    private ReplicationTagging() {
        throw new AssertionError("no instances");
    }

    /**
     * The vehicle and its parts, in the order network ids are assigned.
     *
     * <p>The chassis part comes first because it is the one part that cannot detach (D05-R26), so
     * its position in the list is the only one that can never move.
     */
    public static List<Integer> replicatedEntities(World world, int vehicleEntity) {
        List<Integer> entities = new ArrayList<>();
        entities.add(vehicleEntity);

        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null) {
            return entities;
        }
        if (chassis.chassisPartEntity != EntityId.NULL) {
            entities.add(chassis.chassisPartEntity);
        }

        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (graph == null) {
            return entities;
        }
        List<SlotNode> nodes = new ArrayList<>(graph.nodes);
        nodes.sort(Comparator.comparing(node -> node.slotPath));
        for (SlotNode node : nodes) {
            if (node.childEntity != EntityId.NULL && node.childEntity != chassis.chassisPartEntity) {
                entities.add(node.childEntity);
            }
        }
        return entities;
    }

    /**
     * Attaches a {@link NetworkReplicatedComponent} to the vehicle and each of its parts, numbering
     * them from {@code baseNetworkId}, and binds them in {@code registry}.
     *
     * <p>An entity that already carries the component is renumbered rather than skipped: the only
     * way to reach that state is a peer being told to spawn something it already has, and agreeing
     * with the authority is always the right answer (G1).
     *
     * @return how many ids the block consumed
     */
    public static int tag(
            World world, int vehicleEntity, int baseNetworkId, int ownerPeerId, NetworkRegistry registry) {
        List<Integer> entities = replicatedEntities(world, vehicleEntity);
        for (int i = 0; i < entities.size(); i++) {
            int entityId = entities.get(i);
            int networkId = baseNetworkId + i;
            NetworkReplicatedComponent replicated = world.getComponent(entityId, NetworkReplicatedComponent.class);
            if (replicated == null) {
                replicated = new NetworkReplicatedComponent();
                world.addComponent(entityId, replicated);
            }
            replicated.networkId = networkId;
            replicated.ownerPeerId = ownerPeerId;
            // The vehicle moves every tick and its parts change only when they are hit, which is
            // exactly the HIGH_FREQ / LOW_FREQ split of D10-R14.
            replicated.replicationClass =
                    entityId == vehicleEntity ? ReplicationClass.HIGH_FREQ : ReplicationClass.LOW_FREQ;
            registry.bind(networkId, entityId);
        }
        return entities.size();
    }

    /** Removes a vehicle's block from the registry, parts included. */
    public static void untag(World world, int vehicleEntity, NetworkRegistry registry) {
        for (int entityId : replicatedEntities(world, vehicleEntity)) {
            registry.unbindEntity(entityId);
        }
    }

    /** The slot path a part reports, or an empty string for a vehicle or an untracked entity. */
    public static String slotPathOf(World world, int entityId) {
        PartRefComponent part = world.getComponent(entityId, PartRefComponent.class);
        return part == null ? "" : part.slotPath;
    }
}
