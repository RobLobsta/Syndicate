/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.model.RuntimeMode;
import java.util.Objects;

/**
 * The {@link SystemProvider} for every {@code game-core} system that exists
 * (docs/03_runtime_modes.md#D03-S5.2, docs/04_entity_component_model.md#D04-S4.4).
 *
 * <p>One place where a slot number meets the constructor arguments its system needs. Each system's
 * dependencies — the physics world, the shape cache, the asset index, the spawn queue — are
 * constructor parameters rather than globals (DEC-012), so this class is where they are threaded
 * through, and a system that cannot be constructed without one cannot be scheduled without one.
 *
 * <p>Slots this module has not implemented yet return null, which {@code SystemSetFactory} reports
 * once at startup. That is deliberately not a failure: the eight systems that do exist are a
 * simulation that runs, and refusing to start until all 27 are written would mean the schedule could
 * not be exercised until the last of them landed.
 */
public final class CoreSystemProvider implements SystemProvider {

    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final AssetIndex assets;
    private final SpawnQueue spawnQueue;
    private final DebrisFactory debris;

    /**
     * @param debris the debris body factory shared by fracture and detachment, so both draw on one
     *     {@code MAX_DEBRIS_BODIES} budget rather than two (D07-S5.8)
     */
    public CoreSystemProvider(
            PhysicsWorld physics, ShapeCache shapes, AssetIndex assets, SpawnQueue spawnQueue, DebrisFactory debris) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.shapes = Objects.requireNonNull(shapes, "shapes");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.spawnQueue = Objects.requireNonNull(spawnQueue, "spawnQueue");
        this.debris = Objects.requireNonNull(debris, "debris");
    }

    @Override
    public EntitySystem create(SystemSlot slot, RuntimeMode mode) {
        return switch (slot) {
            case SPAWN -> new SpawnSystem(spawnQueue, assets, physics, shapes);
            case VEHICLE_STATS -> new VehicleStatsSystem(assets);
            case VEHICLE_CONTROL -> new VehicleControlSystem();
            case PHYSICS -> new PhysicsSystem(physics);
            case FRACTURE -> new FractureSystem(assets, shapes, debris);
            case DETACH -> new DetachSystem(assets, shapes, debris, physics);
            case MASS_PROPERTY -> new MassPropertySystem(shapes);
            case LIFETIME -> new LifetimeSystem();
            case ENTITY_DESTROY -> new EntityDestroySystem(physics, shapes);
                // The other 18 slots of D04-S4.4 are unwritten. Null rather than a throw: see the class
                // note — an unimplemented slot leaves a gap in the schedule, not a process that refuses
                // to boot.
            default -> null;
        };
    }
}
