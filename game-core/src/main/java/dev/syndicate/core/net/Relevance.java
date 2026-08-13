/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.ProjectileComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.net.NetConstants;

/**
 * Which entities a given peer is told about (docs/10_networking_multiplayer.md#D10-S5.10).
 *
 * <p>Relevance is distance-based only in v1, which D10-R30 records as a deliberate limitation:
 * line-of-sight culling would be a stronger defence against wallhacks but pops when a vehicle
 * rounds a corner and needs occlusion queries the authority does not otherwise run.
 *
 * <p>An entity's archetype is read from the components it carries rather than from a stored tag.
 * The component set <em>is</em> the archetype (D04-R4), so a second field naming it would be a
 * second thing to keep true.
 *
 * <p><b>A part is as relevant as its vehicle</b> (DEC-060). D10-S5.10's function names vehicles,
 * projectiles, the match and debris but not parts, which are their own entities here and carry the
 * health and damage state R5 replicates. Sending a part whose vehicle was culled would be sending
 * health for a car the peer cannot see, and culling a part whose vehicle was sent would leave that
 * car permanently undamaged on one screen — so the part inherits the answer.
 */
public final class Relevance {

    private Relevance() {
        throw new AssertionError("no instances");
    }

    /**
     * True when {@code entityId}'s state should be sent to a peer driving {@code peerVehicleEntity}.
     *
     * @param peerVehicleEntity the peer's own vehicle, or {@link EntityId#NULL} when it has none —
     *     a spectating or respawning peer, which receives everything, because distance from nothing
     *     is not a number and a peer with no vehicle is exactly the one who needs the whole arena
     */
    public static boolean isRelevantTo(World world, int peerVehicleEntity, int entityId) {
        if (entityId == peerVehicleEntity) {
            return true;
        }
        if (world.hasComponent(entityId, MatchStateComponent.class)) {
            return true;
        }
        if (world.hasComponent(entityId, DebrisTagComponent.class)) {
            return false;
        }

        PartRefComponent part = world.getComponent(entityId, PartRefComponent.class);
        if (part != null && part.vehicleEntity != EntityId.NULL) {
            return isRelevantTo(world, peerVehicleEntity, part.vehicleEntity);
        }

        if (peerVehicleEntity == EntityId.NULL) {
            return true;
        }

        if (world.hasComponent(entityId, ProjectileComponent.class)) {
            return withinRange(world, peerVehicleEntity, entityId, NetConstants.PROJECTILE_RELEVANCE_M);
        }
        if (world.hasComponent(entityId, VehicleChassisComponent.class)) {
            return withinRange(world, peerVehicleEntity, entityId, NetConstants.VEHICLE_RELEVANCE_M);
        }
        return false;
    }

    private static boolean withinRange(World world, int fromEntity, int toEntity, float rangeM) {
        TransformComponent from = world.getComponent(fromEntity, TransformComponent.class);
        TransformComponent to = world.getComponent(toEntity, TransformComponent.class);
        if (from == null || to == null) {
            // Nothing to measure. Sending it is the safe failure: a peer that receives an entity it
            // did not need wastes bandwidth, while one that misses a vehicle sees an empty arena.
            return true;
        }
        return from.position.dst2(to.position) <= rangeM * rangeM;
    }
}
