/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRaycastVehicle;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.asset.HandlingBlock;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.DamageState;

/**
 * Schedule slot 7: turns a driver's intent into forces on the ray-cast vehicle
 * (docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>This is the system that makes a vehicle <em>move</em>. Everything before it describes a vehicle;
 * this one drives it. It reads {@code PlayerInputComponent} — written by a human's client, by the
 * authority's input receiver, or by a bot, and indistinguishable downstream, which is what makes G17
 * hold for AI — and the aggregated {@code VehicleStatsComponent} that slot 6 produced earlier in the
 * same tick.
 *
 * <p>It runs in SIM, before {@code PhysicsSystem} (10). Bullet clears accumulated forces at the end
 * of each {@code stepSimulation}, so the engine force, brake and downforce applied here are consumed
 * by exactly one step and never carried into a second (G2).
 *
 * <p><b>Steering is rate-limited, not snapped</b> (D06-S5.5). A keyboard produces steering input
 * that jumps between −1, 0 and 1, and a vehicle whose wheels followed it exactly would put its lock
 * on in one 16 ms tick, which flips a heavy vehicle. Moving the angle toward its target at
 * {@code steerRateRadPerSec} smooths that identically on client and server, which is also what
 * client prediction needs to reproduce the authority's answer (D10-S5.5).
 *
 * <p><b>A destroyed wheel is zeroed, not skipped.</b> D06-S5.5's loop reads {@code continue} for a
 * destroyed wheel. Bullet's {@code applyEngineForce}, {@code setBrake} and {@code setSteeringValue}
 * set persistent per-wheel values rather than per-step ones, so skipping a wheel leaves it driving
 * and steering on the last command it received, for as long as it stays attached. A destroyed wheel
 * is therefore commanded to zero engine force, zero steering and zero grip — the state {@code
 * continue} was written to describe (DEC-028).
 */
public final class VehicleControlSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 7;

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

    private Family vehicles;

    private final Vector3 scratchForce = new Vector3();
    private final Vector3 scratchVelocity = new Vector3();

    @Override
    public Phase phase() {
        return Phase.SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        vehicles = world.family(ComponentQuery.all(
                VehicleChassisComponent.class, PlayerInputComponent.class, VehicleStatsComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            drive(world, entityIds[i], dtSeconds);
        }
    }

    private void drive(World world, int vehicleEntity, float dtSeconds) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        VehicleStatsComponent stats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        if (chassis == null || input == null || stats == null) {
            return;
        }

        // Steering is advanced whether or not there is a controller to apply it to: a vehicle that
        // has lost every wheel still has a driver turning the wheel, and holding the angle frozen
        // would make it snap to wherever the input was pointing the moment a wheel came back.
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
            // corner pulls to that side instead of losing grip evenly (D05-S5.4).
            controller.getWheelInfo(wheel.wheelIndex).setFrictionSlip(alive ? wheel.effectiveFrictionSlip : 0f);
        }
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
    static float moveToward(float current, float target, float maxDelta) {
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
