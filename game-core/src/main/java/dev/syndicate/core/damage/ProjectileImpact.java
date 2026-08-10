/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.ClosestRayResultCallback;
import com.badlogic.gdx.physics.bullet.collision.btCollisionObject;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.vehicle.PartPlacement;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.DamageType;
import java.util.Objects;

/**
 * Where a shot lands and what that costs
 * (docs/06_physics_simulation.md#D06-S5.9, docs/07_damage_destruction_model.md#D07-S5.1).
 *
 * <p>Shared by {@code WeaponSystem} (8), whose hitscan families resolve in the tick they fire, and
 * {@code ProjectileSystem} (9), whose ballistic ones resolve some ticks later. Both end in the same
 * two operations — cast a segment, and turn a landing into damage events — so both are here rather
 * than duplicated with a subtly different filter mask in each.
 *
 * <p><b>The ray filter is the load-bearing detail.</b> The cast runs on the
 * {@code PROJECTILE} group with {@code PROJECTILE}'s mask, which is what makes a shot pass through
 * debris (D06-R11): the debris layer's mask does not name {@code PROJECTILE}, so a shard cloud can
 * never act as spaced armour and no outcome depends on cosmetic clutter. DISC-011 is the reason this
 * is spelled out rather than left to a default — Bullet's own ray callbacks default to a group that
 * hits everything, and a projectile that stopped on the first shard would look like a hit
 * registration bug for a very long time.
 *
 * <p><b>Explosives hit every part in radius, once each</b> (D07-E3). Each part gets its own event
 * with its own {@code (1 − d/R)²} falloff, so a blast between two vehicles damages both without any
 * part being counted twice, and a part right at the edge takes almost nothing rather than a share.
 */
public final class ProjectileImpact {

    /** Metres beyond which a blast is not searched for parts; the radius bounds it anyway. */
    private static final float BLAST_SEARCH_MARGIN_M = 1.0f;

    private final PhysicsWorld physics;
    private final AssetIndex assets;
    private final HitResolution hits;

    private Family vehicles;

    private final CoverageMap coverage = new CoverageMap();
    private final Matrix4 scratchChassisToWorld = new Matrix4();
    private final Matrix4 scratchPartToWorld = new Matrix4();
    private final Vector3 scratchComWorld = new Vector3();
    private final Vector3 scratchPartWorld = new Vector3();
    private final Vector3 scratchFrom = new Vector3();
    private final Vector3 scratchTo = new Vector3();
    private final Vector3 scratchHitPoint = new Vector3();
    private final Vector3 scratchHitNormal = new Vector3();
    private final Vector3 scratchBlastNormal = new Vector3();

    public ProjectileImpact(PhysicsWorld physics, AssetIndex assets, HitResolution hits) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.hits = Objects.requireNonNull(hits, "hits");
    }

    /** Binds the vehicle family a blast searches. Call once, from the owning system's initialize. */
    public void initialize(World world) {
        vehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class));
    }

    /**
     * What a swept segment found.
     *
     * @param hitEntity the entity that was struck, or {@link EntityId#NULL} for a clean miss
     * @param fraction how far along the segment the hit was, {@code [0,1]}
     */
    public record Sweep(int hitEntity, float fraction, Vector3 point, Vector3 normal) {

        /** Nothing in the way. */
        public static final Sweep MISS = new Sweep(EntityId.NULL, 1f, new Vector3(), new Vector3());

        public boolean hasHit() {
            return hitEntity != EntityId.NULL;
        }
    }

    /**
     * Casts a segment and reports the first thing on the {@code PROJECTILE} mask it meets.
     *
     * <p>The shooter's own body is skipped rather than filtered out of the mask, because a vehicle
     * has to remain shootable by everybody else in the same cast. gdx-bullet's
     * {@code ClosestNotMeRayResultCallback} would do that natively and is not used: it takes a
     * {@code btCollisionObject} rather than an entity, and this class only ever knows entities.
     */
    public Sweep sweep(World world, Vector3 from, Vector3 to, int shooterVehicleEntity) {
        scratchFrom.set(from);
        scratchTo.set(to);
        ClosestRayResultCallback callback = new ClosestRayResultCallback(scratchFrom, scratchTo);
        try {
            callback.setCollisionFilterGroup(CollisionLayer.PROJECTILE.bit());
            callback.setCollisionFilterMask(CollisionLayer.PROJECTILE.mask());
            physics.dynamicsWorld().rayTest(scratchFrom, scratchTo, callback);
            if (!callback.hasHit()) {
                return Sweep.MISS;
            }
            btCollisionObject object = callback.getCollisionObject();
            int hitEntity = entityOf(world, object);
            if (hitEntity == shooterVehicleEntity && shooterVehicleEntity != EntityId.NULL) {
                // The muzzle sits inside the firing vehicle's own hull; a shot that stopped there
                // would mean no weapon ever fired.
                return Sweep.MISS;
            }
            callback.getHitPointWorld(scratchHitPoint);
            callback.getHitNormalWorld(scratchHitNormal);
            return new Sweep(
                    hitEntity,
                    callback.getClosestHitFraction(),
                    new Vector3(scratchHitPoint),
                    new Vector3(scratchHitNormal));
        } finally {
            callback.dispose();
        }
    }

    /**
     * Fires a hitscan shot and applies whatever it hits (D06-S5.9 {@code fireHitscan}).
     *
     * @param direction unit aim direction
     * @param maxRangeM metres the beam or pellet reaches
     */
    public void resolveHitscan(
            World world,
            Vector3 origin,
            Vector3 direction,
            float maxRangeM,
            DamageType damageType,
            float amount,
            float blastRadiusM,
            int shooterVehicleEntity,
            int attackerPlayerEntity,
            int weaponGroup,
            long tick) {

        scratchTo.set(direction).nor().scl(maxRangeM).add(origin);
        Sweep sweep = sweep(world, origin, scratchTo, shooterVehicleEntity);
        if (!sweep.hasHit()) {
            return;
        }
        deliver(
                world,
                sweep.hitEntity(),
                sweep.point(),
                sweep.normal(),
                damageType,
                amount,
                blastRadiusM,
                shooterVehicleEntity,
                attackerPlayerEntity,
                weaponGroup,
                tick);
    }

    /**
     * Turns a landing into damage events: one for a point hit, one per part in radius for a blast.
     *
     * <p>Emitted rather than applied. {@code DamageSystem} (12) applies everything in one sorted pass
     * (G3), and a projectile that applied its own damage in the SIM phase would change a vehicle's
     * mass in the middle of the physics step reading it.
     */
    public void deliver(
            World world,
            int hitEntity,
            Vector3 hitPointWorld,
            Vector3 hitNormalWorld,
            DamageType damageType,
            float amount,
            float blastRadiusM,
            int shooterVehicleEntity,
            int attackerPlayerEntity,
            int weaponGroup,
            long tick) {

        if (blastRadiusM > 0f) {
            deliverBlast(
                    world,
                    hitPointWorld,
                    damageType,
                    amount,
                    blastRadiusM,
                    shooterVehicleEntity,
                    attackerPlayerEntity,
                    weaponGroup,
                    tick);
            return;
        }
        if (hitEntity == EntityId.NULL || !isVehicle(world, hitEntity)) {
            // Arena geometry, a prop, or debris. A shot that hits the world does no damage; it is
            // spent, which is what stops a stray round travelling for its whole range through a wall.
            return;
        }
        coverage.rebuild(world, assets, hitEntity);
        HitResolution.Resolved resolved = hits.resolve(world, hitEntity, -1, hitPointWorld, coverage);
        if (!resolved.hasPart()) {
            return;
        }
        world.events()
                .emitSameTick(DamageEvent.direct(
                        resolved.partEntity(),
                        hitEntity,
                        attackerPlayerEntity,
                        damageType,
                        amount,
                        hitPointWorld,
                        hitNormalWorld,
                        tick,
                        weaponGroup));
    }

    /**
     * One event per live part inside the blast, each with its own falloff (D07-E3).
     *
     * <p>The normal handed to each part points from the blast centre outward, so a part is treated as
     * struck from the direction the explosion was — which is what makes a mortar landing on a roof a
     * top hit (D01-S4.4) without any special case for mortars.
     */
    private void deliverBlast(
            World world,
            Vector3 centreWorld,
            DamageType damageType,
            float amount,
            float radiusM,
            int shooterVehicleEntity,
            int attackerPlayerEntity,
            int weaponGroup,
            long tick) {

        if (vehicles == null) {
            return;
        }
        float searchRadius = radiusM + BLAST_SEARCH_MARGIN_M;
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            int vehicleEntity = entityIds[i];
            if (!world.isAlive(vehicleEntity)) {
                continue;
            }
            VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
            if (chassis == null
                    || !PartPlacement.chassisToWorld(world, vehicleEntity, scratchChassisToWorld, scratchComWorld)) {
                continue;
            }
            // A cheap rejection on the body's own centre before walking its parts. A vehicle is a few
            // metres across, so the margin keeps a blast that clips a wing mirror from being missed.
            if (scratchComWorld.dst2(centreWorld) > (searchRadius + 8f) * (searchRadius + 8f)) {
                continue;
            }
            SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
            if (graph == null) {
                continue;
            }
            coverage.rebuild(world, assets, vehicleEntity);
            // Ascending slot path order, so a blast produces its events in an order that is a
            // function of the assembly rather than of entity allocation (G3).
            SlotChain chain = SlotChain.of(graph, chassis);
            for (var entry : chain.partEntities()) {
                int partEntity = entry.getValue();
                if (!world.isAlive(partEntity) || isDeadOrGone(world, partEntity)) {
                    continue;
                }
                Matrix4 chainTransform = chain.transformOf(entry.getKey());
                if (chainTransform == null) {
                    continue;
                }
                scratchPartToWorld.set(scratchChassisToWorld).mul(chainTransform);
                scratchPartToWorld.getTranslation(scratchPartWorld);
                float distance = scratchPartWorld.dst(centreWorld);
                if (distance > radiusM) {
                    continue;
                }
                float falloff = 1f - distance / radiusM;
                falloff *= falloff;
                float partAmount = amount * falloff;
                if (partAmount <= 0f) {
                    continue;
                }
                scratchBlastNormal.set(scratchPartWorld).sub(centreWorld);
                if (scratchBlastNormal.len2() <= 0f) {
                    scratchBlastNormal.set(Vector3.Y);
                } else {
                    scratchBlastNormal.nor();
                }
                world.events()
                        .emitSameTick(DamageEvent.direct(
                                partEntity,
                                shooterVehicleEntity,
                                attackerPlayerEntity,
                                damageType,
                                partAmount,
                                centreWorld,
                                scratchBlastNormal,
                                tick,
                                weaponGroup));
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------

    /** The entity behind a Bullet object, found by walking the bodies the world knows about. */
    private static int entityOf(World world, btCollisionObject object) {
        if (object == null) {
            return EntityId.NULL;
        }
        long pointer = object.getCPointer();
        Family bodies = world.family(ComponentQuery.all(RigidBodyComponent.class));
        int count = bodies.size();
        int[] entityIds = bodies.snapshot();
        for (int i = 0; i < count; i++) {
            RigidBodyComponent rigidBody = world.getComponent(entityIds[i], RigidBodyComponent.class);
            if (rigidBody != null && rigidBody.body != null && rigidBody.body.getCPointer() == pointer) {
                return entityIds[i];
            }
        }
        return EntityId.NULL;
    }

    private static boolean isVehicle(World world, int entityId) {
        return world.hasComponent(entityId, VehicleChassisComponent.class)
                && !world.hasComponent(entityId, DebrisTagComponent.class);
    }

    private static boolean isDeadOrGone(World world, int partEntity) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        return damageState == null
                || damageState.state == DamageState.DESTROYED
                || damageState.state == DamageState.DETACHED;
    }
}
