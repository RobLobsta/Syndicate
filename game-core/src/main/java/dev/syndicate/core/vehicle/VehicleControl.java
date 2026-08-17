/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRaycastVehicle;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.arena.Surface;
import dev.syndicate.core.arena.TerrainField;
import dev.syndicate.core.asset.HandlingBlock;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.model.DamageState;

/**
 * Turning one vehicle's intent into forces, as a shared operation
 * (docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>The body of {@code VehicleControlSystem} (slot 7), lifted out for the same reason
 * {@code PartDetachment} (DEC-016) and {@code DamageApplication} (DEC-038) were: a second caller
 * needs it and a system may not call a system (D04-R13). That caller is
 * {@code ReconciliationSystem} (slot 20), which replays a client's unacknowledged inputs after a
 * correction and must drive the vehicle through <em>exactly</em> the same arithmetic the authority
 * did — a replay that differed by one term would make prediction error a permanent offset instead
 * of a transient one (D10-S5.5).
 *
 * <p>Stateless apart from two scratch vectors, so one instance can serve both callers.
 */
public final class VehicleControl {

    /**
     * Metres per second. The hard speed clamp of D06-S5.5.
     *
     * <p>A second net behind CCD (D06-R5): the ray-cast wheels cannot tunnel because a ray has no
     * thickness, but the chassis body can, and a vehicle that tunnels through arena geometry ends
     * the match for its driver.
     */
    public static final float MAX_VEHICLE_SPEED_MPS = 40f;

    /**
     * Newtons per (m/s)². Downforce, applied at the centre of mass so it cannot induce a torque
     * (D06-S4.5, D01-S5.2's mild driving assist).
     */
    public static final float DOWNFORCE_COEFFICIENT = HandlingBlock.REFERENCE_DOWNFORCE_COEFFICIENT;

    /**
     * Metres per second. The speed floor the power limit divides by.
     *
     * <p>{@code P/v} is unbounded as {@code v} approaches zero, and a real engine at rest is limited
     * by torque and traction rather than by power. One metre per second is below anything a player
     * can perceive and keeps a standing start finite.
     */
    public static final float MIN_POWER_LIMIT_SPEED_MPS = 1.0f;

    private final Vector3 scratchForce = new Vector3();
    private final Vector3 scratchVelocity = new Vector3();

    /**
     * Where the surface under each wheel is read from, or null on an arena with no height field.
     *
     * <p>The world rather than the field, because a match tears its physics world down and builds a
     * new one when it restarts, and a control operation holding the old field would grip the last
     * arena's sand. Nullable throughout: the flat box arena is still legal (D16-R4).
     */
    private final PhysicsWorld physics;

    /** A control operation with no terrain: every wheel keeps the grip its part authored. */
    public VehicleControl() {
        this(null);
    }

    /**
     * @param physics the world whose terrain the wheels read their surface from (D16-R54, DEC-070)
     */
    public VehicleControl(PhysicsWorld physics) {
        this.physics = physics;
    }

    /**
     * Advances one vehicle's steering and applies its wheel commands and body forces.
     *
     * <p>Steering is advanced whether or not there is a controller to apply it to: a vehicle that
     * has lost every wheel still has a driver turning the wheel, and holding the angle frozen would
     * make it snap to wherever the input was pointing the moment a wheel came back.
     */
    public void drive(World world, int vehicleEntity, float dtSeconds) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        VehicleStatsComponent stats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        if (chassis == null || input == null || stats == null) {
            return;
        }

        float targetSteerRad = clamp(input.steer, -1f, 1f) * stats.maxSteerRad;
        float maxDeltaRad = stats.steerRateRadPerSec * dtSeconds;
        chassis.currentSteerRad = moveToward(chassis.currentSteerRad, targetSteerRad, maxDeltaRad);

        btRaycastVehicle controller = chassis.vehicleController;
        if (controller == null) {
            return;
        }
        applyWheelCommands(world, chassis, input, stats, controller, speedOf(world, vehicleEntity), dtSeconds);
        applyBodyForces(world, vehicleEntity, stats);
    }

    /** Engine force, brake, steering and grip, one wheel at a time (D06-S5.5). */
    private void applyWheelCommands(
            World world,
            VehicleChassisComponent chassis,
            PlayerInputComponent input,
            VehicleStatsComponent stats,
            btRaycastVehicle controller,
            float speedMps,
            float dtSeconds) {

        int drivenWheels = countDrivenWheels(world, chassis);
        // D05-E1: every wheel destroyed leaves the vehicle immobile and alive. max(.., 1) is what
        // keeps that an immobile vehicle rather than a division by zero.
        float tractiveForceN = availableTractiveForceN(stats, speedMps);
        float engineForcePerWheelN = clamp(input.throttle, -1f, 1f) * tractiveForceN / Math.max(drivenWheels, 1);
        // Bullet reads m_brake as a maximum IMPULSE and m_engineForce as a force, multiplying only
        // the latter by the step (btRaycastVehicle::updateFriction). Handing it a force here brakes
        // at 1/TICK_DT — sixty times — too hard, which reads as a car that stops dead from any speed
        // and is exactly the confusion D06-R22 names as the most likely bug in this area (DISC-012).
        float brakeImpulseNs = clamp(input.brake, 0f, 1f) * stats.brakeForceN * dtSeconds;
        float suspensionTotalN = liveSuspensionLoadN(world, chassis, controller);

        for (int i = 0; i < chassis.wheelCount; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
            if (wheel == null || wheel.wheelIndex < 0 || wheel.wheelIndex >= controller.getNumWheels()) {
                continue;
            }
            boolean alive = !isDestroyed(world, wheelEntity);

            if (wheel.isSteering) {
                controller.setSteeringValue(alive ? chassis.currentSteerRad : 0f, wheel.wheelIndex);
            }
            if (wheel.isDriven) {
                controller.applyEngineForce(alive ? engineForcePerWheelN : 0f, wheel.wheelIndex);
            }
            controller.setBrake(
                    alive
                            ? brakeShareOf(
                                    controller, wheel.wheelIndex, brakeImpulseNs, suspensionTotalN, chassis.wheelCount)
                            : 0f,
                    wheel.wheelIndex);
            // Per-wheel grip reflects that wheel's own degradation, so a vehicle with one dead
            // corner pulls to that side instead of losing grip evenly (D05-S5.4) — and then the
            // surface it is standing on (D16-R54), so the same car is quicker on tarmac than on sand.
            float grip = alive ? wheel.effectiveFrictionSlip * surfaceGripUnder(controller, wheel) : 0f;
            controller.getWheelInfo(wheel.wheelIndex).setFrictionSlip(grip);

            mirrorContactState(controller, wheel);
        }
    }

    /**
     * The grip multiplier of the ground under a wheel, and the surface it is standing on.
     *
     * <p>Read at the suspension ray's own contact point rather than at the chassis, because a
     * vehicle straddling a road edge has two wheels on tarmac and two on sand — which is the whole
     * reason per-surface grip is interesting rather than a global modifier (D16-R54).
     *
     * <p>Deliberately not Bullet's custom material callback (D16-R55, DEC-070). That callback fires
     * per contact point on the collision path and a ray-cast wheel generates none, so it would be
     * correct-looking code that never runs for a tyre. It is also a native callback into Java on the
     * physics thread, which G17 and G2 would then both have to reason about.
     *
     * <p>Records the surface on the wheel as it goes, so slot 25 selects its tyre loop from the same
     * lookup the physics used — D16-R56 requires the two to agree, and deriving them independently
     * is how the audio comes to say gravel while the car is on tarmac.
     */
    private float surfaceGripUnder(btRaycastVehicle controller, WheelControllerComponent wheel) {
        TerrainField terrain = physics == null ? null : physics.terrain();
        if (terrain == null) {
            wheel.surface = null;
            return 1f;
        }
        var info = controller.getWheelInfo(wheel.wheelIndex);
        if (info.getWheelsSuspensionForce() <= 0f) {
            // Airborne. Keep the last surface rather than clearing it: a wheel over a jump has not
            // changed what it will land on, and blanking it makes the tyre audio stutter per bump.
            return 1f;
        }
        // btVector3, not Vector3: gdx-bullet exposes the raycast info's native vectors directly.
        com.badlogic.gdx.physics.bullet.linearmath.btVector3 contact =
                info.getRaycastInfo().getContactPointWS();
        Surface surface = terrain.surfaceAt(contact.getX(), contact.getZ());
        wheel.surface = surface;
        return surface == null ? 1f : surface.gripMultiplier();
    }

    /**
     * Copies a wheel's contact state out of Bullet and onto its component.
     *
     * <p>D15-R36 keys the tyre sounds on slip and surface, and observes that the ray-cast wheel
     * "already computes what this needs". It does — and it computed it entirely inside
     * {@code btWheelInfo}, where no system outside the physics step could reach it. That is why both
     * tyre families sat in the bank with correct sounds and no trigger: the data existed and had no
     * door out. Three fields is the whole of that door.
     *
     * <p>Read here rather than in a PRESENT system because {@code btWheelInfo} is native state owned
     * by the physics world (G19), and a presentation system reaching into it would be reading the
     * simulation's natives a phase after the simulation was entitled to free them.
     */
    private static void mirrorContactState(btRaycastVehicle controller, WheelControllerComponent wheel) {
        var info = controller.getWheelInfo(wheel.wheelIndex);
        wheel.suspensionLoadN = info.getWheelsSuspensionForce();
        wheel.isInContact = wheel.suspensionLoadN > 0f;
        // Bullet's skidInfo is 1.0 for full grip and falls toward 0 as the tyre slides; every
        // consumer wants the opposite sense (DISC-012 is the neighbouring trap in this same API).
        wheel.skid = wheel.isInContact ? Math.min(1f, Math.max(0f, 1f - info.getSkidInfo())) : 0f;
    }

    /**
     * The force the engine can actually put down at this speed (DEC-032).
     *
     * <p>{@code min(engineForceN, enginePowerW / v)}. Below the crossover the vehicle is
     * traction-limited and pushes its full launch force; above it the engine is power-limited and the
     * force falls as {@code 1/v}, which is what gives a vehicle a top speed rather than an ever-rising
     * one. A vehicle whose chassis declares no power is unlimited, which is the pre-DEC-032 behaviour
     * and what a part authored before the stat existed still gets.
     */
    public static float availableTractiveForceN(VehicleStatsComponent stats, float speedMps) {
        if (stats.enginePowerW <= 0f) {
            return stats.engineForceN;
        }
        // Below a walking pace the power limit is a division by almost zero, and physically an engine
        // at rest is torque-limited anyway; the floor is what keeps a standing start finite.
        float speed = Math.max(speedMps, MIN_POWER_LIMIT_SPEED_MPS);
        return Math.min(stats.engineForceN, stats.enginePowerW / speed);
    }

    /** The vehicle's current speed, or zero when it has no body to read one from. */
    private float speedOf(World world, int vehicleEntity) {
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (rigidBody == null || rigidBody.body == null) {
            return 0f;
        }
        scratchVelocity.set(rigidBody.body.getLinearVelocity());
        return scratchVelocity.len();
    }

    /** Downforce and the anti-tunnelling speed clamp (D06-S5.5). */
    private void applyBodyForces(World world, int vehicleEntity, VehicleStatsComponent stats) {
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (rigidBody == null || rigidBody.body == null) {
            return;
        }
        btRigidBody body = rigidBody.body;
        scratchVelocity.set(body.getLinearVelocity());
        float speedMps = scratchVelocity.len();

        // Per vehicle, from its chassis part (DEC-031): a GT racer with a wing generates several
        // times what a road car with a flat floor does, and that difference is most of why one of
        // them can carry speed through a corner.
        float downforce = stats.downforceCoefficient > 0f ? stats.downforceCoefficient : DOWNFORCE_COEFFICIENT;
        // Applied centrally: off-centre it would be a pitching torque that grows with speed, which
        // is a handling change nobody authored (D06-S4.5).
        scratchForce.set(0f, -downforce * speedMps * speedMps, 0f);
        body.applyCentralForce(scratchForce);

        if (speedMps > MAX_VEHICLE_SPEED_MPS) {
            body.setLinearVelocity(scratchVelocity.scl(MAX_VEHICLE_SPEED_MPS / speedMps));
        }
    }

    /**
     * One wheel's share of the brake, in proportion to the load it is carrying (DEC-034).
     *
     * <p>D06-S5.5 divides the brake equally across the wheels. That under-brakes badly: weight
     * transfers forward under braking, the rear wheels unload, and Bullet clips each wheel's
     * impulse to its own friction circle — so the rears throw away their equal share while the
     * fronts, which could take more, are not asked to. A shipped vehicle stopped 20 to 60 per cent
     * longer than the car it was calibrated from, and the race car barely out-braked the road car.
     *
     * <p>Splitting by live suspension load is what a real electronic brake-force distribution does,
     * and it needs no authored front/rear bias: the bias falls out of where the weight actually is,
     * tick by tick. It also handles damage for free — a vehicle down to three wheels brakes on the
     * three it has rather than sending a quarter of its braking to a corner that is gone.
     */
    private static float brakeShareOf(
            btRaycastVehicle controller, int wheelIndex, float brakeImpulseNs, float suspensionTotalN, int wheelCount) {
        if (suspensionTotalN <= 0f) {
            // Airborne, or the very first tick before the suspension has been solved once. An equal
            // split is the only information available, and it is about to be replaced.
            return brakeImpulseNs / Math.max(wheelCount, 1);
        }
        return brakeImpulseNs * controller.getWheelInfo(wheelIndex).getWheelsSuspensionForce() / suspensionTotalN;
    }

    /** Total suspension load across the wheels that are still alive, newtons. */
    private static float liveSuspensionLoadN(
            World world, VehicleChassisComponent chassis, btRaycastVehicle controller) {
        float total = 0f;
        for (int i = 0; i < chassis.wheelCount; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
            if (wheel == null || wheel.wheelIndex < 0 || wheel.wheelIndex >= controller.getNumWheels()) {
                continue;
            }
            if (!isDestroyed(world, wheelEntity)) {
                total += controller.getWheelInfo(wheel.wheelIndex).getWheelsSuspensionForce();
            }
        }
        return total;
    }

    private static int countDrivenWheels(World world, VehicleChassisComponent chassis) {
        int driven = 0;
        for (int i = 0; i < chassis.wheelCount; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
            if (wheel != null && wheel.isDriven && !isDestroyed(world, wheelEntity)) {
                driven++;
            }
        }
        return driven;
    }

    private static boolean isDestroyed(World world, int partEntity) {
        DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
        if (damageState == null) {
            return false;
        }
        return damageState.state == DamageState.DESTROYED || damageState.state == DamageState.DETACHED;
    }

    /** Moves {@code current} at most {@code maxDelta} toward {@code target}, without overshoot. */
    public static float moveToward(float current, float target, float maxDelta) {
        float delta = target - current;
        if (Math.abs(delta) <= maxDelta) {
            return target;
        }
        return current + Math.signum(delta) * maxDelta;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
