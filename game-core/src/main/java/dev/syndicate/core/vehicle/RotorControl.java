/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.asset.RotorBlock;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.RotorControllerComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.model.DamageState;

/**
 * Turning a rotorcraft's intent into forces, as a shared operation (D06-S5.4, DEC-090).
 *
 * <p>The counterpart of {@link VehicleControl}'s wheel path, and a sibling rather than a special
 * case of it: a wheel converts engine force into traction against a surface, and a rotor converts
 * it into thrust against nothing. There is no ray cast, no suspension, no contact patch and no
 * surface grip in here, and no amount of parameterising the wheel loop would have produced one.
 *
 * <p>Shared and stateless for the same reason {@code VehicleControl} is (DEC-061): slot 7 and
 * {@code ReconciliationSystem} (20) must drive a vehicle through <em>exactly</em> the same
 * arithmetic, or prediction error becomes a permanent offset rather than a transient one.
 *
 * <h2>The flight model</h2>
 *
 * Five forces, applied every tick in a fixed order so the result is bit-reproducible (G2, G3):
 *
 * <ol>
 *   <li><b>Main rotor thrust</b> along the vehicle's <em>own</em> up axis, at the hub. Along the
 *       vehicle's up rather than the world's is the whole flight model in one line: it is why
 *       tilting the nose down converts lift into forward flight, and why a helicopter that rolls
 *       starts sliding sideways, without either being written down as a rule.
 *   <li><b>Collective trim.</b> Neutral stick hovers. See {@link #hoverFraction}.
 *   <li><b>Cyclic</b> — pitch and roll torques toward the attitude the stick is asking for, with
 *       the attitude error damped so the aircraft settles instead of oscillating.
 *   <li><b>Main rotor torque</b> about the vehicle's up axis, proportional to thrust. This is the
 *       reaction the fuselage feels from dragging the disc round, and it is always applied.
 *   <li><b>Tail rotor</b> — a sideways thrust at the tail, which both cancels (4) and steers. Lose
 *       the tail rotor and nothing cancels the torque any more, so the aircraft spins. That
 *       behaviour is not coded anywhere: it is what remains when a term is removed.
 * </ol>
 */
public final class RotorControl {

    /**
     * Metres per second squared. The gravity the hover trim is computed against.
     *
     * <p>Read from a constant rather than from the Bullet world because the trim has to agree with
     * what the solver will do <em>this</em> tick, and querying the world's gravity vector per
     * vehicle per tick to learn a number that D06-S4.1 fixes is a native call for nothing.
     */
    public static final float GRAVITY_MPS2 = 9.81f;

    /**
     * The share of a rotor's thrust that survives when the collective is fully down.
     *
     * <p>Not zero: a real rotor at flat pitch still turns and still moves air, and — more to the
     * point — a collective that reaches zero thrust makes "stick fully down" and "main rotor
     * destroyed" the same experience, which wastes the one consequence the rotor being a part
     * buys. At 0.25 a descending helicopter falls at a few metres per second rather than dropping
     * like the wreck it becomes when the disc actually leaves.
     */
    public static final float MIN_COLLECTIVE_FRACTION = 0.25f;

    /**
     * The largest hover trim the model will compute, as a fraction of maximum thrust.
     *
     * <p>Caps the case where a rotorcraft is loaded past what its rotor can lift. Without it the
     * trim exceeds 1, is clamped by the thrust cap anyway, and the aircraft sinks at full
     * collective with the stick doing nothing — which reads as a broken control rather than as an
     * overloaded aircraft. At 0.9 there is always some authority left to feel.
     */
    public static final float MAX_HOVER_FRACTION = 0.9f;

    /** Radians. How far the cyclic tilts the aircraft at full deflection — about 25°. */
    public static final float MAX_CYCLIC_TILT_RAD = 0.44f;

    /**
     * Newton-metres per newton of thrust. The fuselage's share of the torque that turns the disc.
     *
     * <p>A real main rotor's reaction torque is {@code power / angular speed}, which for a light
     * helicopter at hover is around a twentieth of its thrust taken as a moment. 0.05 puts an
     * un-cancelled Kestrel into a spin that takes a couple of seconds to become unrecoverable,
     * which is the pace this wants to read at: alarming, briefly survivable, and clearly the
     * consequence of losing the tail.
     */
    public static final float TORQUE_PER_THRUST_M = 0.05f;

    /** Newton-metres per radian of attitude error. How hard the cyclic pulls toward its target. */
    public static final float ATTITUDE_STIFFNESS_NM_PER_RAD = 900f;

    /** Newton-metre-seconds per radian. Damping on pitch and roll, so attitude settles. */
    public static final float ATTITUDE_DAMPING_NMS_PER_RAD = 420f;

    /** Newton-metre-seconds per radian. Damping on yaw, which the tail rotor works against. */
    public static final float YAW_DAMPING_NMS_PER_RAD = 260f;

    /**
     * Newtons per (m/s)². Airframe drag, which is what gives a helicopter a forward top speed.
     *
     * <p>A helicopter accelerates until the forward component of its tilted rotor thrust equals its
     * drag. At 9.0 the Kestrel settles around 38 m/s at full forward cyclic, just under the arena's
     * own 40 m/s clamp (D06-S5.5) — so the aircraft is limited by its own aerodynamics and reaches
     * the clamp only in a dive, which is what the clamp is for.
     */
    public static final float AIRFRAME_DRAG = 9.0f;

    /**
     * Fraction of governed speed a rotor gains or sheds per second.
     *
     * <p>A quarter, so a destroyed disc takes four seconds to stop and a fresh one is at speed by
     * the time the spawn has settled. It is what makes losing the main rotor read as an aircraft
     * running down rather than as lift being switched off — thrust goes as speed squared, so the
     * first second of the spin-down is already most of the lift gone.
     */
    public static final float SPOOL_FRACTION_PER_SEC = 0.25f;

    /**
     * Fraction of maximum thrust given up per metre per second of climb.
     *
     * <p>What turns "cancel the weight" into "hold the height", and what limits the climb rate.
     * 0.03 puts the Kestrel's terminal climb at about 10 m/s, near the 8.5 m/s of the machine it
     * is derived from, and settles a released collective within a couple of seconds.
     */
    public static final float VERTICAL_DAMPING_PER_MPS = 0.03f;

    private final Vector3 scratchUp = new Vector3();
    private final Vector3 scratchForce = new Vector3();
    private final Vector3 scratchTorque = new Vector3();
    private final Vector3 scratchOffset = new Vector3();
    private final Vector3 scratchVelocity = new Vector3();
    private final Vector3 scratchAngular = new Vector3();
    private final Matrix4 scratchTransform = new Matrix4();

    private final PhysicsWorld physics;

    public RotorControl() {
        this(null);
    }

    /**
     * @param physics asked whether the aircraft is standing on the world, which is what decides
     *     whether the hover trim engages ({@link #isFlying}). Null is treated as airborne, which is
     *     what a pure-logic test with no Bullet world wants.
     */
    public RotorControl(PhysicsWorld physics) {
        this.physics = physics;
    }

    /**
     * Set by {@link VehicleControl} so the two halves share one clamp.
     *
     * <p>Injected rather than constructed here because {@code VehicleControl} owns this instance
     * and constructing one the other way round is a cycle.
     */
    private VehicleControl clamp;

    void useClamp(VehicleControl owner) {
        this.clamp = owner;
    }

    /**
     * Advances one rotorcraft, applying thrust, cyclic, torque and the tail rotor's answer to it.
     *
     * <p>Does nothing at all when no live rotor remains. That is deliberate and is the falling
     * case: gravity is already acting, the airframe drag term goes with the rotors that authored
     * it, and the wreck arrives under the same integrator as any other debris.
     */
    public void fly(World world, int vehicleEntity, float dtSeconds) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (chassis == null || input == null || rigidBody == null || rigidBody.body == null) {
            return;
        }
        btRigidBody body = rigidBody.body;

        float mainThrustMax = 0f;
        float tailThrustMax = 0f;
        float mainRadiusM = 0f;
        // Ascending slot-path order, because float addition is not associative (G3).
        for (int i = 0; i < chassis.rotorCount; i++) {
            int rotorEntity = chassis.rotorEntities[i];
            RotorControllerComponent rotor = world.getComponent(rotorEntity, RotorControllerComponent.class);
            if (rotor == null) {
                continue;
            }
            // A destroyed rotor is spun down rather than skipped, for the reason a destroyed wheel
            // is commanded to zero rather than skipped (DEC-028): the client reads currentRpm to
            // draw the blades, and a disc that simply stopped being summed would keep spinning on
            // screen at full speed while contributing nothing.
            boolean alive = !isDestroyed(world, rotorEntity);
            rotor.currentRpm = alive
                    ? approach(rotor.currentRpm, rotor.maxRpm, rotor.maxRpm * SPOOL_FRACTION_PER_SEC * dtSeconds)
                    : approach(rotor.currentRpm, 0f, rotor.maxRpm * SPOOL_FRACTION_PER_SEC * dtSeconds);
            if (!alive) {
                continue;
            }
            float thrust = thrustOf(world, rotorEntity, rotor);
            if (rotor.isMain) {
                mainThrustMax += thrust;
                mainRadiusM = Math.max(mainRadiusM, rotor.radiusM);
            } else {
                tailThrustMax += thrust;
            }
        }
        if (mainThrustMax <= 0f && tailThrustMax <= 0f) {
            return;
        }

        scratchTransform.set(body.getCenterOfMassTransform());
        // The vehicle's own up axis, which is what the disc thrusts along.
        scratchUp.set(0f, 1f, 0f).rot(scratchTransform).nor();
        scratchVelocity.set(body.getLinearVelocity());
        scratchAngular.set(body.getAngularVelocity());

        float massKg = chassis.totalMassKg > 0f ? chassis.totalMassKg : 1f;
        float trim = isFlying(body) ? 1f : 0f;
        float thrustN =
                mainThrustMax * collectiveFraction(input.collective, massKg, mainThrustMax, scratchVelocity.y, trim);

        // 1 + 2 — lift along the vehicle's up axis, at the hub rather than the centre of mass, so a
        // tilted aircraft gets the pendulum restoring moment a real one has for free.
        scratchForce.set(scratchUp).scl(thrustN);
        scratchOffset
                .set(0f, mainRadiusM > 0f ? hubHeightM(mainRadiusM) : 0f, 0f)
                .rot(scratchTransform);
        body.applyForce(scratchForce, scratchOffset);

        // 3 — cyclic. Pitch from the throttle axis, roll auto-levelled and banked into the turn.
        applyCyclic(body, input, dtSeconds);

        // 4 + 5 — the disc's reaction torque, and the tail rotor's answer to it.
        applyYaw(body, input, thrustN, tailThrustMax, mainRadiusM);

        // Airframe drag, which is what stops it accelerating forever.
        float speedMps = scratchVelocity.len();
        if (speedMps > 0.01f) {
            scratchForce.set(scratchVelocity).scl(-AIRFRAME_DRAG * speedMps);
            body.applyCentralForce(scratchForce);
        }

        // The same clamp every other vehicle gets (D06-S5.5). Drag alone is not a bound: it is a
        // force, so a dive adds speed faster than it removes it, and the clamp is what stops a
        // vehicle tunnelling through the arena (D06-R5).
        if (clamp != null) {
            clamp.clampSpeed(world, vehicleEntity, body);
        }
    }

    /**
     * How much of maximum thrust the stick is asking for, trimmed so that neutral hovers.
     *
     * <p>The arcade choice, and the one that makes the aircraft flyable with a keyboard: a released
     * stick holds height. Above neutral the axis spends what is left up to full thrust; below it,
     * down to {@link #MIN_COLLECTIVE_FRACTION}. Both halves are linear in the input, so the stick
     * feels the same in either direction even though the two ranges are different sizes.
     */
    private float collectiveFraction(float collective, float massKg, float maxThrustN, float verticalMps, float trim) {
        float hover = hoverFraction(massKg, maxThrustN);
        float demand = clamp(collective, -1f, 1f);
        float trimmed =
                demand >= 0f ? hover + demand * (1f - hover) : hover + demand * (hover - MIN_COLLECTIVE_FRACTION);
        // On the ground the stick is a raw thrust command that rests at the bottom of its range,
        // which is where a real machine's collective sits on a pad (DISC-071). A released stick
        // therefore leaves the aircraft parked instead of holding it at flying thrust — and it has
        // to be the bottom of the range rather than the middle of it, because the middle is 62.5%
        // of maximum, which for a light helicopter is 95% of its own weight and still enough to
        // skate it down a slope.
        float parked = MIN_COLLECTIVE_FRACTION + Math.max(demand, 0f) * (1f - MIN_COLLECTIVE_FRACTION);
        float fraction = parked + (trimmed - parked) * trim;
        // Height hold and a climb-rate limit, from one term. Cancelling weight is not the same as
        // holding height, and the first build did only the first: a Kestrel that had climbed to
        // 80 m on full collective coasted another 72 m after the stick was released, which reads
        // as a balloon rather than as an aircraft. Damping the vertical rate makes neutral mean
        // "stay here" — which is what a pilot's small corrections do, and what this model's own
        // docstring already claimed neutral meant — and it gives the climb a terminal rate for
        // free at about (1 - hover) / VERTICAL_DAMPING_PER_MPS, which is 10 m/s for the Kestrel
        // against the 8.5 m/s an H125 actually manages.
        // Scaled by the same engagement: height hold is part of the trim, and a machine sitting on
        // its skids being shoved downward should not answer by making more thrust.
        fraction -= verticalMps * VERTICAL_DAMPING_PER_MPS * trim;
        return clamp(fraction, MIN_COLLECTIVE_FRACTION, 1f);
    }

    /**
     * Whether the hover trim applies: true in the air, false while standing on the world.
     *
     * <p>"Neutral collective hovers" is right in the air and wrong on the ground (DISC-071). A
     * parked machine given 100% of the thrust it needs to fly is a puck: on a slope the trim tilts
     * with the airframe and gains a horizontal component of {@code weight × sin(θ)} that no wheel,
     * no suspension and no rolling resistance opposes, so it slides, rocks on the offset hub moment
     * and puts its own disc into the hillside. Measured on a 12° gradient with no input at all, the
     * aircraft covered 43.9 m in five seconds.
     *
     * <p>A switch rather than a blend. The two mappings meet at full up-collective, which is the
     * only deflection a lift-off can happen at — the aircraft cannot leave the ground until the
     * parked mapping already exceeds hover — so what the switch adds at the moment of lift-off is a
     * step of at most a fifth of maximum thrust, in the direction of climbing, which the vertical
     * damping term absorbs within a second or two. Lifting off feels like unsticking, which is what
     * it is; nothing else in the envelope crosses this boundary.
     *
     * <p>It runs inside the shared control operation and is therefore replayed by
     * {@code ReconciliationSystem} (DEC-061), exactly as D16's per-surface grip read already is
     * (DEC-070).
     */
    private boolean isFlying(btRigidBody body) {
        return physics == null || !physics.isTouchingStatic(body);
    }

    /** The share of maximum thrust that exactly cancels weight, capped by {@link #MAX_HOVER_FRACTION}. */
    private float hoverFraction(float massKg, float maxThrustN) {
        if (maxThrustN <= 0f) {
            return 0f;
        }
        return Math.min(massKg * GRAVITY_MPS2 / maxThrustN, MAX_HOVER_FRACTION);
    }

    /**
     * Where the rotor hub sits above the centre of mass, in metres.
     *
     * <p>Derived from the disc radius rather than read off the part's placement because the moment
     * arm this produces is what the pendulum stability is tuned against, and a hub position taken
     * from art varies with how the model was drawn. A third of the radius is about right for every
     * conventional helicopter, and the term's job is stability rather than fidelity.
     */
    private float hubHeightM(float radiusM) {
        return radiusM / 3f;
    }

    /**
     * Pitch and roll torques toward the attitude the cyclic is asking for.
     *
     * <p>A proportional-derivative controller on attitude rather than a direct torque, because a
     * direct torque makes the stick a rate command: the aircraft keeps rotating as long as it is
     * held, ends up inverted, and never returns to level when released. Commanding an <em>angle</em>
     * is what makes it settle.
     */
    private void applyCyclic(btRigidBody body, PlayerInputComponent input, float dtSeconds) {
        // Current pitch and roll, read off the up axis rather than from Euler angles — no gimbal
        // lock, and no dependence on a rotation order nobody authored (D00-R17).
        scratchOffset.set(0f, 0f, 1f).rot(scratchTransform).nor();
        float pitchRad = (float) Math.asin(clamp(-scratchUp.z, -1f, 1f));
        float rollRad = (float) Math.asin(clamp(scratchUp.x, -1f, 1f));

        float targetPitchRad = clamp(input.throttle, -1f, 1f) * MAX_CYCLIC_TILT_RAD;
        // Bank into the turn: a coordinated helicopter rolls the way it yaws, and level flight with
        // the stick centred is the resting state.
        float targetRollRad = clamp(input.steer, -1f, 1f) * MAX_CYCLIC_TILT_RAD * 0.5f;

        // Body-local angular rates, so damping opposes the axis it is damping.
        float pitchRate = scratchAngular.x;
        float rollRate = scratchAngular.z;

        float pitchTorque =
                (targetPitchRad - pitchRad) * ATTITUDE_STIFFNESS_NM_PER_RAD - pitchRate * ATTITUDE_DAMPING_NMS_PER_RAD;
        float rollTorque =
                (targetRollRad - rollRad) * ATTITUDE_STIFFNESS_NM_PER_RAD - rollRate * ATTITUDE_DAMPING_NMS_PER_RAD;

        scratchTorque.set(pitchTorque, 0f, rollTorque);
        body.applyTorque(scratchTorque);
    }

    /**
     * The main rotor's reaction torque, and the tail rotor's answer to it.
     *
     * <p>Both act about the vehicle's up axis. The main term is always applied and the tail term
     * only when a tail rotor is alive, which is the entire implementation of "lose the tail rotor
     * and you spin" — there is no branch anywhere that says so.
     */
    private void applyYaw(
            btRigidBody body, PlayerInputComponent input, float thrustN, float tailThrustMax, float mainRadiusM) {
        float torqueNm = -thrustN * TORQUE_PER_THRUST_M;

        if (tailThrustMax > 0f) {
            // The tail rotor sits about a rotor radius behind the centre of mass, so its thrust is
            // a moment of that arm. It trims out the disc's torque first and steers with whatever
            // authority is left, which is exactly how a real pedal works.
            float armM = Math.max(mainRadiusM, 1f);
            float availableNm = tailThrustMax * armM;
            float trimNm = clamp(-torqueNm, -availableNm, availableNm);
            float steerNm = clamp(input.steer, -1f, 1f) * Math.max(availableNm - Math.abs(trimNm), 0f);
            torqueNm += trimNm - steerNm;
        }

        float yawRate = scratchAngular.y;
        torqueNm -= yawRate * YAW_DAMPING_NMS_PER_RAD;

        scratchTorque.set(scratchUp).scl(torqueNm);
        body.applyTorque(scratchTorque);
    }

    /**
     * The thrust this rotor currently makes, in newtons, after degradation (D05-S5.4).
     *
     * <p>Read from {@code effectiveStats}, which slot 6 rebuilt this tick, so a rotor shot to half
     * health lifts less without anything here knowing that damage exists. Scaled by how fast the
     * disc is actually turning, squared: thrust goes as the square of rotor speed, and it is what
     * makes a rotor spinning down lose lift smoothly rather than at a step.
     */
    private float thrustOf(World world, int rotorEntity, RotorControllerComponent rotor) {
        PartStatsComponent stats = world.getComponent(rotorEntity, PartStatsComponent.class);
        float rated = stats == null
                ? RotorBlock.DEFAULT_THRUST_N
                : Math.max(stats.effectiveStats.resolve(StatBlock.Stat.ROTOR_THRUST_N, 0f), 0f);
        if (rotor.maxRpm <= 0f) {
            return rated;
        }
        float speedFraction = clamp(rotor.currentRpm / rotor.maxRpm, 0f, 1f);
        return rated * speedFraction * speedFraction;
    }

    private boolean isDestroyed(World world, int entity) {
        DamageStateComponent state = world.getComponent(entity, DamageStateComponent.class);
        return state != null && state.state == DamageState.DESTROYED;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Moves {@code from} toward {@code to} by at most {@code maxDelta}. */
    private static float approach(float from, float to, float maxDelta) {
        float delta = to - from;
        if (Math.abs(delta) <= maxDelta) {
            return to;
        }
        return from + Math.copySign(maxDelta, delta);
    }
}
