/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btStaticPlaneShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.World;
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
import dev.syndicate.core.vehicle.ShippedContent;
import dev.syndicate.core.vehicle.VehicleFactory;
import dev.syndicate.core.vehicle.VehicleProfile;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.DamageState;
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

    /**
     * The road is an infinite plane at y=0 rather than a very long box.
     *
     * <p>Long enough for a 0-100 and a stop was the requirement, and a 800 m box met it while
     * quietly breaking the suspension: Bullet ray-tests a convex shape with an iterative cast whose
     * accuracy falls off with the shape's size, so each wheel's ground contact came back up to
     * 14 cm out, differently every tick (DISC-017). A plane is intersected analytically and the
     * same test is exact. {@code ArenaFactory} builds its floor the same way for the same reason.
     */
    private static final Vector3 ROAD_NORMAL = new Vector3(0f, 1f, 0f);

    /** Dry asphalt. Bullet multiplies body friction with the ray-cast wheel's own grip figure. */
    private static final float ROAD_FRICTION = 1.0f;

    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final InMemoryAssetIndex assets;

    private final List<btRigidBody> roadBodies = new ArrayList<>();
    private final List<btDefaultMotionState> roadMotionStates = new ArrayList<>();
    private final List<btStaticPlaneShape> roadShapes = new ArrayList<>();

    private final DebrisFactory debrisFactory;
    private final EntityDestroySystem entityDestroySystem;
    private final dev.syndicate.core.ecs.Family embodied;

    private long tick;

    public ShippedContentScene(long matchSeed) {
        world = new World(matchSeed, true);
        physics = PhysicsWorld.create();
        shapes = new ShapeCache();
        assets = ShippedContent.load();
        debrisFactory = new DebrisFactory(physics);
        entityDestroySystem = new EntityDestroySystem(physics, shapes);
        // The destruction half of the schedule is here so a shipped car can lose a part while it is
        // being driven. Damage and collision events are not: nothing in this scene shoots or crashes,
        // and a test that wants a part gone marks it destroyed through destroyPart.
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
        embodied = world.family(dev.syndicate.core.ecs.ComponentQuery.all(RigidBodyComponent.class));
        addRoad();
    }

    public World world() {
        return world;
    }

    public PhysicsWorld physics() {
        return physics;
    }

    /**
     * Marks a part destroyed, which is what {@code DamageSystem} does in slot 12.
     *
     * <p>Cutting in at the state machine rather than by shooting the part keeps a test about
     * detachment from also being a test about ballistics — and no shipped part is a weapon yet.
     */
    public void destroyPart(int partEntity) {
        DamageStateComponent state = world.getComponent(partEntity, DamageStateComponent.class);
        state.state = DamageState.DESTROYED;
        state.stateEnteredTick = tick;
        state.stateVersion++;
    }

    /** The shape cache the scene's bodies borrow their hulls from (G19). */
    public ShapeCache shapes() {
        return shapes;
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
        return spawn(profile.profileId(), position);
    }

    /**
     * Spawns a shipped assembly by id.
     *
     * <p>Beside the {@link VehicleProfile} overload rather than replacing it, because not every
     * shipped vehicle has a profile: {@link VehicleProfile} records a *car's* published figures —
     * a 0-100 time, tyre codes, a braking distance — and the Kestrel is a helicopter, whose
     * researched figures live in its own {@code profile.json} instead (DEC-090).
     */
    public int spawn(AssetId assemblyId, Vector3 position) {
        AssemblyDef assembly = assets.assembly(assemblyId);
        if (assembly == null) {
            throw new IllegalStateException("no shipped assembly " + assemblyId.value());
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

    /**
     * Spawns a shipped vehicle with its **armament stripped**, for the calibration tests.
     *
     * <p>A {@code VehicleProfile} describes a car: its kerb mass, its published 0-100, its ride
     * height. The shipped assembly is that car <em>plus what it is carrying</em> (D17-S1), and a
     * 389 kg pedestal cannon on a 1,969 kg Mustang takes its 0-100 from 3.4 s to 4.4 s and
     * compresses its suspension by 19 mm. Both of those are correct physics and neither is a fact
     * about the Mustang, so a test that asserts the published figures has to measure the car.
     *
     * <p>The armament is identified the same way it is everywhere else: a part from the shared
     * library rather than from the vehicle's own art (DEC-075).
     */
    public int spawnUnarmed(VehicleProfile profile, Vector3 position) {
        AssemblyDef assembly = assets.assembly(profile.profileId());
        if (assembly == null) {
            throw new IllegalStateException(
                    "no shipped assembly for profile " + profile.profileId().value());
        }
        java.util.List<AssemblyDef.PartPlacement> unarmed = assembly.parts().stream()
                .filter(placement -> !placement.partTypeId().value().startsWith("weapon_"))
                .toList();
        AssemblyDef stripped = new AssemblyDef(
                assembly.assemblyId(), assembly.vehicleClass(), assembly.chassisPartTypeId(), unarmed, null);
        return VehicleFactory.spawnVehicle(
                world,
                physics,
                shapes,
                assets,
                stripped,
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
        btStaticPlaneShape shape = new btStaticPlaneShape(ROAD_NORMAL, 0f);
        shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        roadShapes.add(shape);
        NativeResourceTracker.register("btStaticPlaneShape");

        btDefaultMotionState motionState = new btDefaultMotionState(new Matrix4());
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
        for (btStaticPlaneShape shape : roadShapes) {
            shape.dispose();
            NativeResourceTracker.release("btStaticPlaneShape");
        }
        shapes.dispose();
        physics.dispose();
    }
}
