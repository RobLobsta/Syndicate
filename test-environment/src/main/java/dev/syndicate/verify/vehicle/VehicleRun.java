/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.vehicle;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btStaticPlaneShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.system.DetachSystem;
import dev.syndicate.core.system.EntityDestroySystem;
import dev.syndicate.core.system.FractureSystem;
import dev.syndicate.core.system.LifetimeSystem;
import dev.syndicate.core.system.MassPropertySystem;
import dev.syndicate.core.system.PhysicsSystem;
import dev.syndicate.core.system.TransformSystem;
import dev.syndicate.core.system.VehicleControlSystem;
import dev.syndicate.core.system.VehicleStatsSystem;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.VehicleFactory;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One shipped vehicle, on a road, being driven — the simulation half of {@code --vehicle} mode
 * (docs/14_test_environment.md#D14-S5.11, docs/03_runtime_modes.md#D03-S5.4).
 *
 * <p>Headless by construction and rendered by {@link VehicleScene}, which owns the GL context and
 * nothing else. That split is the same one the rest of the harness keeps (D14-S5.13, G17): this
 * class would run identically on a machine with no display, and every number the capture is evidence
 * for is measured here rather than read off a picture.
 *
 * <p>The schedule is the driving and destruction half of D04-S4.4 — stats, control, physics,
 * fracture, detach, mass properties, lifetimes, transforms, teardown. Damage and collision events
 * are absent because nothing here shoots or crashes: the script destroys a part outright, which is
 * what {@code DamageSystem} would have done in slot 12, and leaves ballistics to the tests that are
 * about ballistics.
 */
public final class VehicleRun implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleRun.class);

    /**
     * The road: a plane, for the reason {@code ArenaFactory}'s floor is one.
     *
     * <p>Bullet's convex ray test loses accuracy with the size of the shape, and a ray-cast wheel is
     * one ray per corner per tick; a road built as a long box makes the wheels jitter through 14 cm
     * with the body perfectly still (DISC-017).
     */
    private static final Vector3 ROAD_NORMAL = new Vector3(0f, 1f, 0f);

    private static final float ROAD_FRICTION = 1.0f;

    /** The slot the script destroys. Front left, so a front-three-quarter view is looking at it. */
    public static final String DETACHED_SLOT_PATH = "root/wheel_fl";

    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final InMemoryAssetIndex assets;
    private final Path assetRoot;

    private final btStaticPlaneShape roadShape;
    private final btRigidBody roadBody;
    private final btDefaultMotionState roadMotionState;

    private final Family debris;
    private final Family embodied;
    private final EntityDestroySystem entityDestroySystem;

    private int vehicleEntity = EntityId.NULL;
    private long tick;

    public VehicleRun(Path assetRoot, long seed) {
        Bullet.init(false);
        this.assetRoot = assetRoot;
        world = new World(seed, true);
        physics = PhysicsWorld.create();
        shapes = new ShapeCache();
        assets = new AssetLoader(new GltfCollisionMeshSource()).loadFrom(assetRoot);
        DebrisFactory debrisFactory = new DebrisFactory(physics);
        entityDestroySystem = new EntityDestroySystem(physics, shapes);
        world.registerSystems(List.<EntitySystem>of(
                new VehicleStatsSystem(assets),
                new VehicleControlSystem(),
                new PhysicsSystem(physics),
                new FractureSystem(assets, shapes, debrisFactory),
                new DetachSystem(assets, shapes, debrisFactory, physics),
                new MassPropertySystem(shapes),
                new LifetimeSystem(),
                new TransformSystem(),
                entityDestroySystem));
        debris = world.family(ComponentQuery.all(DebrisTagComponent.class, TransformComponent.class));
        embodied = world.family(ComponentQuery.all(RigidBodyComponent.class));

        roadShape = new btStaticPlaneShape(ROAD_NORMAL, 0f);
        roadShape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        NativeResourceTracker.register("btStaticPlaneShape");
        roadMotionState = new btDefaultMotionState(new Matrix4());
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(0f, roadMotionState, roadShape, Vector3.Zero);
        roadBody = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();
        roadBody.setFriction(ROAD_FRICTION);
        roadBody.setRestitution(0f);
        physics.addBody(roadBody, CollisionLayer.STATIC);
    }

    /** Spawns the named vehicle type at the origin. */
    public int spawn(AssetId vehicleTypeId) {
        AssemblyDef assembly = assets.assembly(vehicleTypeId);
        if (assembly == null) {
            throw new IllegalArgumentException("no shipped assembly for " + vehicleTypeId.value());
        }
        vehicleEntity = VehicleFactory.spawnVehicle(
                world,
                physics,
                shapes,
                assets,
                assembly,
                new Matrix4().setToTranslation(0f, 0.05f, 0f),
                EntityId.NULL,
                TeamComponent.FREE_FOR_ALL);
        return vehicleEntity;
    }

    public World world() {
        return world;
    }

    public InMemoryAssetIndex assets() {
        return assets;
    }

    public Path assetRoot() {
        return assetRoot;
    }

    public int vehicleEntity() {
        return vehicleEntity;
    }

    public long tick() {
        return tick;
    }

    /** Advances one {@code TICK_DT} (G2). */
    public void step() {
        world.tick(tick++);
    }

    public void step(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    /** Holds the throttle at {@code amount} until told otherwise. */
    public void throttle(float amount) {
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        if (input != null) {
            input.throttle = amount;
        }
    }

    /** Where the wheel the script destroys currently is, for a camera that wants a close-up. */
    public Vector3 wheelPosition(String slotPath) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null) {
            return vehiclePosition();
        }
        for (int i = 0; i < chassis.wheelCount; i++) {
            PartRefComponent ref = world.getComponent(chassis.wheelEntities[i], PartRefComponent.class);
            TransformComponent transform = world.getComponent(chassis.wheelEntities[i], TransformComponent.class);
            if (ref != null && transform != null && slotPath.equals(ref.slotPath)) {
                return transform.worldMatrix.getTranslation(new Vector3());
            }
        }
        return vehiclePosition();
    }

    /** Where the vehicle body is, for a camera that follows it. */
    public Vector3 vehiclePosition() {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (body == null || body.body == null) {
            return new Vector3();
        }
        Matrix4 transform = new Matrix4();
        body.body.getWorldTransform(transform);
        return transform.getTranslation(new Vector3());
    }

    /** Metres per second, from Bullet rather than from last tick's mirror. */
    public float speedMps() {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        return body == null || body.body == null ? 0f : new Vector3(body.body.getLinearVelocity()).len();
    }

    /** How many of the vehicle's wheels are on the road. */
    public int wheelsInContact() {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null || chassis.vehicleController == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < chassis.wheelCount; i++) {
            WheelControllerComponent wheel =
                    world.getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
            if (wheel != null
                    && wheel.wheelIndex < chassis.vehicleController.getNumWheels()
                    && chassis.vehicleController
                            .getWheelInfo(wheel.wheelIndex)
                            .getRaycastInfo()
                            .getIsInContact()) {
                count++;
            }
        }
        return count;
    }

    public int wheelCount() {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        return chassis == null ? 0 : chassis.wheelCount;
    }

    /** Every live part of the vehicle, with the part type to draw and the matrix to draw it at. */
    public List<Drawable> drawables() {
        List<Drawable> out = new ArrayList<>();
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null) {
            return out;
        }
        addDrawable(out, chassis.chassisPartEntity);
        for (int i = 0; i < chassis.wheelCount; i++) {
            addDrawable(out, chassis.wheelEntities[i]);
        }
        // Debris carries no PartRefComponent — the part entity it came from is already retired — but
        // its shape key names the part type, which is exactly what has to be drawn (D07-S5.7).
        int[] entityIds = debris.snapshot();
        for (int i = 0; i < debris.size(); i++) {
            RigidBodyComponent body = world.getComponent(entityIds[i], RigidBodyComponent.class);
            TransformComponent transform = world.getComponent(entityIds[i], TransformComponent.class);
            if (body != null && body.shapeKey != null && transform != null) {
                out.add(new Drawable(body.shapeKey.assetId(), new Matrix4(transform.worldMatrix)));
            }
        }
        return out;
    }

    private void addDrawable(List<Drawable> out, int partEntity) {
        PartRefComponent ref = world.getComponent(partEntity, PartRefComponent.class);
        TransformComponent transform = world.getComponent(partEntity, TransformComponent.class);
        if (ref != null && transform != null) {
            out.add(new Drawable(ref.partTypeId, new Matrix4(transform.worldMatrix)));
        }
    }

    /** What to draw and where: a part type id and the world matrix {@code TransformSystem} produced. */
    public record Drawable(AssetId partTypeId, Matrix4 worldMatrix) {}

    /**
     * Marks the front-left wheel destroyed, which is what {@code DamageSystem} does in slot 12.
     *
     * @return the part entity that was destroyed, or {@link EntityId#NULL} if it was already gone
     */
    public int destroyFrontLeftWheel() {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null) {
            return EntityId.NULL;
        }
        for (int i = 0; i < chassis.wheelCount; i++) {
            int partEntity = chassis.wheelEntities[i];
            PartRefComponent ref = world.getComponent(partEntity, PartRefComponent.class);
            DamageStateComponent state = world.getComponent(partEntity, DamageStateComponent.class);
            if (ref == null || state == null || !DETACHED_SLOT_PATH.equals(ref.slotPath)) {
                continue;
            }
            state.state = dev.syndicate.model.DamageState.DESTROYED;
            state.stateEnteredTick = tick;
            state.stateVersion++;
            LOG.info("destroyed {} at tick {}, {} m/s", ref.slotPath, tick, speedMps());
            return partEntity;
        }
        return EntityId.NULL;
    }

    /** Seconds of simulated time in {@code ticks} ticks. */
    public static float seconds(int ticks) {
        return ticks * SimulationConstants.TICK_DT;
    }

    /** Ticks in {@code seconds} of simulated time. */
    public static int ticks(float seconds) {
        return Math.round(seconds / SimulationConstants.TICK_DT);
    }

    @Override
    public void close() {
        // Bodies first, then the world, then the shapes they referenced, then the physics world
        // itself — the disposal order D02-S5.7 fixes, and the only order in which none of these is a
        // use-after-free (G19).
        for (int pass = 0; pass < 8 && embodied.size() > 0; pass++) {
            int[] ids = embodied.snapshot();
            int count = embodied.size();
            for (int i = 0; i < count; i++) {
                world.destroyEntity(ids[i]);
            }
            entityDestroySystem.update(world, SimulationConstants.TICK_DT, tick);
        }
        world.dispose();
        physics.removeBody(roadBody);
        roadBody.dispose();
        NativeResourceTracker.release("btRigidBody");
        roadMotionState.dispose();
        NativeResourceTracker.release("btDefaultMotionState");
        roadShape.dispose();
        NativeResourceTracker.release("btStaticPlaneShape");
        shapes.dispose();
        physics.dispose();
    }
}
