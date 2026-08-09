/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.core.vehicle.SpawnRequest;
import dev.syndicate.core.vehicle.VehicleFactory;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 5: creates the vehicles other systems asked for
 * (docs/04_entity_component_model.md#D04-S4.4, docs/05_vehicle_part_system.md#D05-S5.2).
 *
 * <p>Authority only (D04-S4.4 row 5). A client never invents a vehicle: it is told about one in a
 * spawn message and creates it at the index the authority dictates, so the two agree on every
 * {@code EntityId} (D04-R24). That path belongs to {@code NetworkReceiveSystem} (19), not here.
 *
 * <p><b>Why spawning is a system at all.</b> Everything that wants a vehicle wants it from a
 * different phase — {@code MatchFlowSystem} (4) on a respawn, {@code BotDecisionSystem} (3) when it
 * fills a seat, the match bootstrap before any system has run. If each created entities where it
 * stood, the order in which ids were allocated would depend on which systems happened to want a
 * vehicle in a given tick. Draining one queue at one point in the tick makes that order a property
 * of the requests instead (G3).
 *
 * <p>It runs in PRE_SIM, ahead of everything that reads a vehicle, so a vehicle spawned this tick is
 * stepped this tick rather than sitting inert for one — including
 * {@code MassPropertySystem} (15), which finds the mass properties {@link VehicleFactory} already
 * established and leaves them alone (DEC-021).
 */
public final class SpawnSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(SpawnSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 5;

    private final SpawnQueue queue;
    private final AssetIndex assets;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;

    private int spawnedCount;

    public SpawnSystem(SpawnQueue queue, AssetIndex assets, PhysicsWorld physics, ShapeCache shapes) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.physics = Objects.requireNonNull(physics, "physics");
        this.shapes = Objects.requireNonNull(shapes, "shapes");
    }

    @Override
    public Phase phase() {
        return Phase.PRE_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        if (queue.isEmpty()) {
            return;
        }
        List<SpawnRequest> requests = queue.drain();
        for (int i = 0; i < requests.size(); i++) {
            spawn(world, requests.get(i), tick);
        }
    }

    private void spawn(World world, SpawnRequest request, long tick) {
        AssemblyDef assembly = assets.assembly(request.assemblyId());
        if (assembly == null) {
            // Refused, not thrown. An assembly id can reach here from a client's loadout choice, and
            // a bad one must not be able to abort a tick for everybody else (D10-S4.6, G16).
            LOG.error(
                    "spawn request {} names assembly {}, which is not loaded; no vehicle was created",
                    request.sequence(),
                    request.assemblyId().value());
            return;
        }
        int vehicleEntity = VehicleFactory.spawnVehicle(
                world,
                physics,
                shapes,
                assets,
                assembly,
                request.spawnTransform(),
                request.ownerEntity(),
                request.teamId());
        if (vehicleEntity == EntityId.NULL) {
            LOG.error(
                    "spawn request {} for assembly {} produced no vehicle at tick {}",
                    request.sequence(),
                    request.assemblyId().value(),
                    tick);
            return;
        }
        spawnedCount++;
    }

    /** How many vehicles this system has created, for diagnostics and tests. */
    public int spawnedCount() {
        return spawnedCount;
    }
}
