/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.system.EntityDestroySystem;
import dev.syndicate.core.system.MassPropertySystem;
import dev.syndicate.core.system.PhysicsSystem;
import dev.syndicate.core.system.VehicleControlSystem;
import dev.syndicate.core.system.VehicleStatsSystem;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.ShippedContent;
import dev.syndicate.core.vehicle.VehicleFactory;
import dev.syndicate.core.vehicle.VehicleProfile;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * A long, flat road with the shipped vehicles on it, for the calibration tests
 * (docs/12_testing_validation_ci.md#D12-S4.1 level L3).
 *
 * <p>Distinct from {@code DestructionTestScene}, which builds its own content out of boxes: this one
 * loads the real {@code assets/} tree and spawns from it, because a calibration test that ran on
 * synthetic content would only prove that the arithmetic in {@code VehicleProfile} is
 * self-consistent. Its schedule is the driving half — spawn is done directly, then stats, control,
 * physics, mass properties and cleanup.
 */
public final class ShippedContentScene implements AutoCloseable {

    static {
        // D02-R3 puts Bullet.init() in an executable's bootstrap; a test process has no bootstrap,
        // and useRefCounting = false because ownership here is manual and explicit (G19).
        Bullet.init(false);
    }

    /** Half-extents of the road, metres. Long enough that a 0-100 run and a stop both fit on it. */
    private static final Vector3 ROAD_HALF_EXTENTS = new Vector3(60f, 1f, 400f);

    /** Dry asphalt. Bullet multiplies body friction with the ray-cast wheel's own grip figure. */
    private static final float ROAD_FRICTION = 1.0f;

    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final InMemoryAssetIndex assets;

    private final List<btRigidBody> roadBodies = new ArrayList<>();
    private final List<btDefaultMotionState> roadMotionStates = new ArrayList<>();
    private final List<btBoxShape> roadShapes = new ArrayList<>();

    private final EntityDestroySystem entityDestroySystem;
    private final dev.syndicate.core.ecs.Family embodied;

    private long tick;

    public ShippedContentScene(long matchSeed) {
        world = new World(matchSeed, true);
        physics = PhysicsWorld.create();
        shapes = new ShapeCache();
        assets = ShippedContent.load();
        entityDestroySystem = new EntityDestroySystem(physics, shapes);
        world.registerSystems(List.<EntitySystem>of(
                new VehicleStatsSystem(assets),
                new VehicleControlSystem(),
                new PhysicsSystem(physics),
                new MassPropertySystem(shapes),
                entityDestroySystem));
        embodied = world.family(dev.syndicate.core.ecs.ComponentQuery.all(RigidBodyComponent.class));
        addRoad();
    }

    public World world() {
        return world;
    }

    public InMemoryAssetIndex assets() {
        return assets;
    }

    /** Advances the schedule one tick, which is one {@code TICK_DT} of simulation (G2). */
    public void step() {
        world.tick(tick++);
    }

    public void step(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    /** Spawns a profile's vehicle from the shipped assembly, through the real spawn path. */
    public int spawn(VehicleProfile profile, Vector3 position) {
        AssemblyDef assembly = assets.assembly(profile.profileId());
        if (assembly == null) {
            throw new IllegalStateException(
                    "no shipped assembly for profile " + profile.profileId().value());
        }
        return VehicleFactory.spawnVehicle(
                world,
                physics,
                shapes,
                assets,
                assembly,
                new Matrix4().setToTranslation(position),
                dev.syndicate.core.ecs.EntityId.NULL,
                TeamComponent.FREE_FOR_ALL);
    }

    /** Destroys a vehicle and runs the cleanup that releases its natives, so a test can spawn again. */
    public void despawn(int vehicleEntity) {
        world.destroyEntity(vehicleEntity);
        for (int pass = 0; pass < 4; pass++) {
            entityDestroySystem.update(world, SimulationConstants.TICK_DT, tick);
        }
    }

    /** The vehicle's current speed in m/s, read from Bullet rather than from last tick's mirror. */
    public float speedOf(int vehicleEntity) {
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        return rigidBody == null || rigidBody.body == null ? 0f : new Vector3(rigidBody.body.getLinearVelocity()).len();
    }

    /** How many of the vehicle's wheels are touching the ground. */
    public int wheelsInContact(int vehicleEntity) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null || chassis.vehicleController == null) {
            return 0;
        }
        int inContact = 0;
        for (int i = 0; i < chassis.wheelCount; i++) {
            WheelControllerComponent wheel =
                    world.getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
            if (wheel != null
                    && wheel.wheelIndex < chassis.vehicleController.getNumWheels()
                    && chassis.vehicleController
                            .getWheelInfo(wheel.wheelIndex)
                            .getRaycastInfo()
                            .getIsInContact()) {
                inContact++;
            }
        }
        return inContact;
    }

    /**
     * Holds full throttle until the vehicle reaches {@code targetMps}, and returns how long it took.
     *
     * <p>Seconds are counted in whole ticks, because that is the only clock the simulation has (G2).
     *
     * @return the elapsed time, or {@link Float#NaN} if the vehicle never got there
     */
    public float timeToSpeed(int vehicleEntity, float targetMps, float timeoutSeconds) {
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        input.throttle = 1f;
        input.brake = 0f;

        int limit = (int) Math.ceil(timeoutSeconds / SimulationConstants.TICK_DT);
        for (int elapsed = 0; elapsed < limit; elapsed++) {
            step();
            if (speedOf(vehicleEntity) >= targetMps) {
                input.throttle = 0f;
                return (elapsed + 1) * SimulationConstants.TICK_DT;
            }
        }
        input.throttle = 0f;
        return Float.NaN;
    }

    /** Accelerates to a speed and holds it there, for a test that wants to measure what comes next. */
    public void accelerateTo(int vehicleEntity, float targetMps, float timeoutSeconds) {
        timeToSpeed(vehicleEntity, targetMps, timeoutSeconds);
    }

    /**
     * Applies full brake until the vehicle stops, and returns the distance it covered.
     *
     * <p>Distance is integrated from the body's own position rather than from speed times time, so
     * it measures where the vehicle actually went.
     */
    public float brakeToStop(int vehicleEntity, float timeoutSeconds) {
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        input.throttle = 0f;
        input.brake = 1f;

        Vector3 start = positionOf(vehicleEntity);
        int limit = (int) Math.ceil(timeoutSeconds / SimulationConstants.TICK_DT);
        for (int elapsed = 0; elapsed < limit; elapsed++) {
            step();
            if (speedOf(vehicleEntity) < 0.5f) {
                break;
            }
        }
        input.brake = 0f;
        return positionOf(vehicleEntity).sub(start).len();
    }

    private Vector3 positionOf(int vehicleEntity) {
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        Matrix4 transform = new Matrix4();
        rigidBody.body.getWorldTransform(transform);
        return transform.getTranslation(new Vector3());
    }

    /** A static road whose top face is at {@code y = 0}. */
    private void addRoad() {
        btBoxShape shape = new btBoxShape(ROAD_HALF_EXTENTS);
        shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        roadShapes.add(shape);
        NativeResourceTracker.register("btBoxShape");

        btDefaultMotionState motionState = new btDefaultMotionState(new Matrix4().setToTranslation(0f, -1f, 0f));
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(0f, motionState, shape, Vector3.Zero);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();
        body.setFriction(ROAD_FRICTION);
        body.setRestitution(0f);
        physics.addBody(body, CollisionLayer.STATIC);
        roadBodies.add(body);
        roadMotionStates.add(motionState);
    }

    /**
     * Tears down in the D02-S5.7 rule 5 order: entities, then shapes, then the world.
     *
     * <p>Entities go through {@code EntityDestroySystem} rather than {@code World.dispose()} alone,
     * because slot 27 is what releases natives — a scene that freed them itself would leave every
     * test asserting a disposal path the game does not use.
     */
    @Override
    public void close() {
        for (int pass = 0; pass < 8; pass++) {
            int[] ids = embodied.snapshot();
            int count = embodied.size();
            if (count == 0) {
                break;
            }
            for (int i = 0; i < count; i++) {
                world.destroyEntity(ids[i]);
            }
            entityDestroySystem.update(world, SimulationConstants.TICK_DT, tick);
        }
        world.dispose();
        for (btRigidBody body : roadBodies) {
            physics.removeBody(body);
            body.dispose();
            NativeResourceTracker.release("btRigidBody");
        }
        for (btDefaultMotionState motionState : roadMotionStates) {
            motionState.dispose();
            NativeResourceTracker.release("btDefaultMotionState");
        }
        for (btBoxShape shape : roadShapes) {
            shape.dispose();
            NativeResourceTracker.release("btBoxShape");
        }
        shapes.dispose();
        physics.dispose();
    }
}
