/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.damage.DamageApplication;
import dev.syndicate.core.damage.HitResolution;
import dev.syndicate.core.damage.ProjectileImpact;
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
 * once at startup. That is deliberately not a failure: the systems that do exist are a simulation
 * that runs, and refusing to start until all 27 are written would mean the schedule could not be
 * exercised until the last of them landed.
 */
public final class CoreSystemProvider implements SystemProvider {

    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final AssetIndex assets;
    private final SpawnQueue spawnQueue;
    private final DebrisFactory debris;

    /**
     * The damage pipeline's shared collaborators, built once here.
     *
     * <p>Slots 8, 9, 11 and 12 all resolve hits and all apply damage, and doing so through one
     * {@link HitResolution} and one {@link DamageApplication} is what makes a shotgun pellet, a
     * rammed kerb and a rocket's blast reach the same armour formula. They also carry per-call
     * scratch, so sharing them keeps four systems from allocating four sets of it.
     */
    private final HitResolution hits;

    private final DamageApplication damage;
    private final ProjectileImpact impacts;

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
        this.hits = new HitResolution(shapes);
        this.damage = new DamageApplication(assets, hits);
        this.impacts = new ProjectileImpact(physics, assets, hits);
    }

    @Override
    public EntitySystem create(SystemSlot slot, RuntimeMode mode) {
        return switch (slot) {
            case SPAWN -> new SpawnSystem(spawnQueue, assets, physics, shapes);
            case VEHICLE_STATS -> new VehicleStatsSystem(assets);
            case VEHICLE_CONTROL -> new VehicleControlSystem();
            case WEAPON -> new WeaponSystem(assets, impacts, mode.isAuthority());
            case PROJECTILE -> new ProjectileSystem(impacts, mode.isAuthority());
            case PHYSICS -> new PhysicsSystem(physics);
            case COLLISION_EVENT -> new CollisionEventSystem(physics, assets, hits);
            case DAMAGE -> new DamageSystem(assets, damage);
            case FRACTURE -> new FractureSystem(assets, shapes, debris);
            case DETACH -> new DetachSystem(assets, shapes, debris, physics);
            case MASS_PROPERTY -> new MassPropertySystem(shapes);
            case LIFETIME -> new LifetimeSystem();
            case SCORE -> new ScoreSystem();
            case TRANSFORM -> new TransformSystem();
            case ENTITY_DESTROY -> new EntityDestroySystem(physics, shapes);
                // The other 12 slots of D04-S4.4 are unwritten. Null rather than a throw: see the class
                // note — an unimplemented slot leaves a gap in the schedule, not a process that refuses
                // to boot.
            default -> null;
        };
    }
}
