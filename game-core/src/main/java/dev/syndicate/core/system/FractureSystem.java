/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.FractureManifest;
import dev.syndicate.core.asset.ShardDefinition;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.FractureDataComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.damage.DetachReason;
import dev.syndicate.core.damage.PartFracturedEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.ShapeCacheKey;
import dev.syndicate.core.util.Pcg32;
import dev.syndicate.core.util.RandomVectors;
import dev.syndicate.core.util.StreamId;
import dev.syndicate.core.vehicle.PartDetachment;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 13: turns a destroyed part into its pre-authored shards
 * (docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.6).
 *
 * <p>Authority only. The fact of a fracture is replicated as one {@link PartFracturedEvent} carrying
 * the parent's motion; individual shard transforms and velocities never are (DEC-005, D07-R5). Each
 * client spawns its own shard set from the same manifest with the same inherited momentum, so the
 * explosions agree closely enough to be indistinguishable and cost nothing on the wire.
 *
 * <p><b>Momentum is inherited at each shard's own position</b>, not at the vehicle's centre of mass:
 * {@code v = v_body + ω × (r_shard − r_com)}. Using the body's linear velocity alone would give every
 * shard the same velocity and throw away the rotational component, which is exactly the part that
 * makes a spinning vehicle's debris look right. The scatter added on top is deliberately small
 * (1.6 m/s at most per shard) so it separates the shards without dominating what they inherited —
 * the harness's PROG-004 check fails if it does.
 *
 * <p><b>Shard masses come from the manifest and are never recomputed</b> (D07-R19). The manifest is
 * validated at load and re-derived independently by the harness, so mass conservation (G7) is an
 * asset-time guarantee rather than a runtime computation that could disagree with it.
 *
 * <p><b>What this system does not do.</b> Parts hanging <em>below</em> the fractured one leave the
 * vehicle with it (D05-S5.5 step 1) but are not turned into debris bodies here: a non-fractured part
 * becomes a single body from its own collision hull, which is {@code DetachSystem}'s case in slot 14
 * and needs the part-hull half of the asset index. They are left detached, bodyless and inert until
 * that system exists.
 */
public final class FractureSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(FractureSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 13;

    /**
     * Metres per second of outward separation given to each shard (D07-S5.6).
     *
     * <p>Without it the shards resolve as one interpenetrating cluster and the solver pushes them
     * apart with far more energy than this. Small on purpose: it must not dominate the inherited
     * momentum.
     */
    public static final float SCATTER_SPEED_MPS = 1.2f;

    /** Metres per second of random jitter on each shard, from the {@code FRACTURE_SCATTER} stream. */
    public static final float SCATTER_JITTER_MPS = 0.4f;

    /** Radians per second of random spin added to each shard's inherited angular velocity. */
    public static final float SCATTER_SPIN_RADPS = 2.0f;

    private final AssetIndex assets;
    private final ShapeCache shapes;
    private final DebrisFactory debrisFactory;

    private Family fracturable;

    private final Matrix4 scratchPartWorld = new Matrix4();
    private final Matrix4 scratchShardWorld = new Matrix4();
    private final Matrix4 scratchLocal = new Matrix4();
    private final Matrix4 scratchRecentre = new Matrix4();
    private final Vector3 scratchComWorld = new Vector3();
    private final Vector3 scratchBodyLinear = new Vector3();
    private final Vector3 scratchBodyAngular = new Vector3();
    private final Vector3 scratchShardPosition = new Vector3();
    private final Vector3 scratchLever = new Vector3();
    private final Vector3 scratchVelocity = new Vector3();
    private final Vector3 scratchOutward = new Vector3();
    private final Vector3 scratchJitter = new Vector3();
    private final Vector3 scratchSpin = new Vector3();

    public FractureSystem(AssetIndex assets, ShapeCache shapes, DebrisFactory debrisFactory) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.shapes = Objects.requireNonNull(shapes, "shapes");
        this.debrisFactory = Objects.requireNonNull(debrisFactory, "debrisFactory");
    }

    @Override
    public Phase phase() {
        return Phase.POST_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        fracturable = world.family(ComponentQuery.all(DamageStateComponent.class, FractureDataComponent.class));
        debrisFactory.initialize(world);
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = fracturable.size();
        int[] entityIds = fracturable.snapshot();
        for (int i = 0; i < count; i++) {
            int partEntity = entityIds[i];
            DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
            FractureDataComponent fractureData = world.getComponent(partEntity, FractureDataComponent.class);
            if (damageState == null || fractureData == null) {
                continue;
            }
            if (damageState.state != DamageState.DESTROYED || fractureData.hasFractured) {
                // hasFractured is one-way (G9, AC-D07-11). A part whose health were somehow restored
                // after fracturing stays a pile of shards; this is the check that refuses the second
                // attempt.
                continue;
            }
            fracturePart(world, partEntity, fractureData, tick);
        }
    }

    private void fracturePart(World world, int partEntity, FractureDataComponent fractureData, long tick) {
        FractureManifest manifest = assets.fractureManifest(fractureData.manifestRef);
        if (manifest == null) {
            // A part with no loadable manifest vanishes rather than fracturing — the same outcome as
            // a part type that declares no manifest at all (D05-S4.4). Marking it fractured first
            // keeps that one-way, so a manifest arriving late cannot re-trigger it.
            LOG.error(
                    "part {} references fracture manifest {} which is not loaded; destroying it without shards",
                    EntityId.toString(partEntity),
                    fractureData.manifestRef);
            fractureData.hasFractured = true;
            world.destroyEntity(partEntity);
            return;
        }

        PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
        int vehicleEntity = partRef == null ? EntityId.NULL : partRef.vehicleEntity;
        String slotPath = partRef == null ? "" : partRef.slotPath;

        // 1. Capture the parent's motion at the part's position BEFORE anything changes. Detachment
        //    below alters the vehicle's structure, and slot 15 then moves its body origin onto the
        //    new centre of mass; either would make these numbers describe a vehicle that no longer
        //    exists.
        boolean placed = capturePartWorldTransform(world, partEntity, vehicleEntity, slotPath);
        captureParentMotion(world, partEntity, vehicleEntity);

        // 2. Remove the part's contribution from the vehicle. Mass, COM and inertia are reconciled by
        //    MassPropertySystem in slot 15 — the same tick, before the next step (G10, AC-D07-14).
        if (vehicleEntity != EntityId.NULL) {
            PartDetachment.detach(world, shapes, vehicleEntity, partEntity, DetachReason.FRACTURED, tick);
        }

        // 3. One debris body per shard.
        int spawned = placed ? spawnShards(world, partEntity, manifest, tick) : 0;
        if (!placed) {
            LOG.error(
                    "part {} could not be placed in the world, so its {} shards were not spawned; "
                            + "the part is destroyed regardless",
                    EntityId.toString(partEntity),
                    manifest.shardCount());
        }

        fractureData.hasFractured = true;
        fractureData.shardCount = manifest.shardCount();
        world.events()
                .emit(new PartFracturedEvent(
                        partEntity,
                        vehicleEntity,
                        slotPath,
                        spawned,
                        tick,
                        scratchBodyLinear.x,
                        scratchBodyLinear.y,
                        scratchBodyLinear.z,
                        scratchBodyAngular.x,
                        scratchBodyAngular.y,
                        scratchBodyAngular.z));
        world.destroyEntity(partEntity);
    }

    /**
     * Writes the part's world transform into {@code scratchPartWorld}.
     *
     * <p>An attached part has no body of its own — it is geometry inside the vehicle's compound
     * (DEC-004) — so its world placement is the vehicle body's transform, composed with the shift
     * that put the compound's origin on the centre of mass, composed with its slot chain. Skipping
     * the recentring term would spawn every shard offset by the vehicle's COM, which is subtle on an
     * intact vehicle and obvious on a badly damaged one.
     *
     * @return false when the part cannot be placed at all, which is the only case where shards are
     *     not spawned
     */
    private boolean capturePartWorldTransform(World world, int partEntity, int vehicleEntity, String slotPath) {
        if (vehicleEntity != EntityId.NULL) {
            RigidBodyComponent vehicleBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
            VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
            SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
            if (vehicleBody != null && vehicleBody.body != null && chassis != null && graph != null) {
                Matrix4 chainTransform = SlotChain.of(graph, chassis).transformOf(slotPath);
                if (chainTransform != null) {
                    vehicleBody.body.getWorldTransform(scratchPartWorld);
                    scratchRecentre.setToTranslation(-chassis.comLocal.x, -chassis.comLocal.y, -chassis.comLocal.z);
                    scratchPartWorld.mul(scratchRecentre).mul(chainTransform);
                    return true;
                }
            }
        }

        // A part that is already loose has its own body, and a part that has neither still has a
        // transform component — the last place the simulation agreed it was.
        RigidBodyComponent partBody = world.getComponent(partEntity, RigidBodyComponent.class);
        if (partBody != null && partBody.body != null) {
            partBody.body.getWorldTransform(scratchPartWorld);
            return true;
        }
        TransformComponent transform = world.getComponent(partEntity, TransformComponent.class);
        if (transform != null && transform.parent == EntityId.NULL) {
            scratchPartWorld.set(transform.position, transform.rotation);
            return true;
        }
        return false;
    }

    /**
     * Writes the parent's linear and angular velocity and its world centre of mass into scratch.
     *
     * <p>The vehicle body's origin <em>is</em> its centre of mass — that is what the recentring in
     * D06-S5.7 arranges — so the world COM is simply the body's world translation, with no second
     * derivation to fall out of step with the first.
     */
    private void captureParentMotion(World world, int partEntity, int vehicleEntity) {
        int source = vehicleEntity != EntityId.NULL ? vehicleEntity : partEntity;
        VelocityComponent velocity = world.getComponent(source, VelocityComponent.class);
        if (velocity != null) {
            scratchBodyLinear.set(velocity.linear);
            scratchBodyAngular.set(velocity.angular);
        } else {
            scratchBodyLinear.set(0f, 0f, 0f);
            scratchBodyAngular.set(0f, 0f, 0f);
        }

        RigidBodyComponent sourceBody = world.getComponent(source, RigidBodyComponent.class);
        if (sourceBody != null && sourceBody.body != null) {
            sourceBody.body.getWorldTransform(scratchShardWorld);
            scratchShardWorld.getTranslation(scratchComWorld);
        } else {
            scratchPartWorld.getTranslation(scratchComWorld);
        }
    }

    /**
     * Spawns one debris body per shard, in manifest order.
     *
     * <p>Every draw comes from the {@code FRACTURE_SCATTER} stream and in a fixed order — jitter then
     * spin, shard by shard, shards sorted by id. That is what makes the authority's fracture
     * reproducible: a determinism replay that consumed the stream in a different order would produce
     * a different, equally valid, explosion and fail for a reason unrelated to the change under test
     * (D06-R25, G4).
     *
     * @return how many shards actually became bodies
     */
    private int spawnShards(World world, int partEntity, FractureManifest manifest, long tick) {
        Pcg32 random = world.random().stream(StreamId.FRACTURE_SCATTER);
        List<ShardDefinition> shards = manifest.shards();
        int spawned = 0;

        for (int i = 0; i < shards.size(); i++) {
            ShardDefinition shard = shards.get(i);

            // Drawn before the mass check so that a manifest with an undersized shard still consumes
            // the stream identically on every peer — otherwise one peer's skipped shard would shift
            // every later shard's scatter.
            RandomVectors.nextUnitVector(random, scratchJitter).scl(SCATTER_JITTER_MPS);
            RandomVectors.nextVectorInCube(random, SCATTER_SPIN_RADPS, scratchSpin);

            if (shard.massKg() < SimulationConstants.MIN_BODY_MASS_KG) {
                // D06-E1: the tooling merges shards this small (D09-S6.2). At runtime the shard is
                // refused rather than clamped, because clamping mass silently violates G7.
                LOG.error(
                        "shard {} of manifest {} weighs {} kg, below MIN_BODY_MASS_KG; refusing to spawn it",
                        shard.shardId(),
                        manifest.manifestId(),
                        shard.massKg());
                continue;
            }

            shard.localTransform(scratchLocal);
            scratchShardWorld.set(scratchPartWorld).mul(scratchLocal);
            scratchShardWorld.getTranslation(scratchShardPosition);

            // v = v_body + ω × (r_shard − r_com): momentum inherited at the shard's own position.
            scratchLever.set(scratchShardPosition).sub(scratchComWorld);
            scratchVelocity.set(scratchBodyAngular).crs(scratchLever).add(scratchBodyLinear);

            // Outward from the part's centre, in world space. The manifest's centroid is part-local,
            // so it is rotated by the part's orientation — an unrotated direction would scatter the
            // shards of a tilted part along the wrong axes.
            shard.centroidLocal(scratchOutward);
            if (scratchOutward.len2() > 0f) {
                scratchOutward.rot(scratchPartWorld).nor().scl(SCATTER_SPEED_MPS);
                scratchVelocity.add(scratchOutward);
            }
            scratchVelocity.add(scratchJitter);
            scratchSpin.add(scratchBodyAngular);

            btConvexHullShape hull =
                    shapes.hullFor(ShapeCacheKey.shard(manifest.manifestId(), shard.index()), shard.hullMesh());
            debrisFactory.spawnDebris(
                    world,
                    ShapeCacheKey.shard(manifest.manifestId(), shard.index()),
                    hull,
                    shard.massKg(),
                    scratchShardWorld,
                    scratchVelocity,
                    scratchSpin,
                    SimulationConstants.DEBRIS_LIFETIME_S,
                    partEntity);
            spawned++;
        }

        LOG.debug(
                "part {} fractured into {} of {} shards at tick {}",
                EntityId.toString(partEntity),
                spawned,
                shards.size(),
                tick);
        return spawned;
    }
}
