/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

/**
 * One engine's life, from the starter to the last compression stroke
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37a3).
 *
 * <p><b>Ignition and shutdown used to be assets and are now phases.</b> Twelve files —
 * {@code engine_start_*} and {@code engine_stop_*} — existed because a loop cannot change speed,
 * so an engine picking itself up off the starter had to be recorded separately and pitched from a
 * nominal 800 rpm idle that no car in the game actually has. A synthesiser that is told the rpm
 * needs none of that: cranking is 260 rpm with the cylinders not catching, and shutting down is the
 * same engine running out of rotation. Both are this class, and both are the car's own idle speed
 * rather than a reference one.
 *
 * <p><b>Condition is derived here too.</b> A damaged engine misfires, and a badly damaged one loses
 * a cylinder and its exhaust — all three are parameters of {@link EngineSynth.State}, so the mapping
 * from health to how sick an engine sounds lives in one place instead of being spread across the
 * call sites that happen to know a vehicle's health.
 *
 * <p>Simulation-thread state. Advanced once a frame, and the {@link EngineSynth.State} it returns is
 * published to the mixer for the audio thread to read.
 */
final class EngineRunState {

    /** Where an engine is in its life. */
    enum Phase {
        CRANKING,
        CATCHING,
        RUNNING,
        STOPPING,
        OFF
    }

    /** How long the starter turns before anything catches. */
    private static final float CRANK_SECONDS = 0.62f;

    /** How long the engine takes to pick itself up once it does. */
    private static final float CATCH_SECONDS = 0.30f;

    /** Speed the starter turns a cold engine at, once it has got it moving. */
    private static final float CRANK_RPM = 260f;

    /** How long the starter takes to drag a cold engine up to cranking speed. */
    private static final float CRANK_SPIN_UP_SECONDS = 0.18f;

    /**
     * How far the crank speed swings either side of {@link #CRANK_RPM} on each compression.
     *
     * <p><b>This is the growl, and it was the thing that made a start sound like a fault.</b> A
     * starter does not turn an engine smoothly: every compression stroke loads it, the speed drops,
     * the piston goes over the top and the speed recovers. The first version modulated at a fixed
     * 6 Hz with a comment claiming it was the compression rate — it was not related to the engine at
     * all. The real rate is {@code rpm / 120 × cylinders}, which is 8.7 Hz for a four and 26 Hz for
     * a twelve, so a big engine grinds where a small one chugs.
     */
    private static final float CRANK_LABOUR_DEPTH = 0.26f;

    /** How far past idle a caught engine flares before settling. */
    private static final float FLARE_FACTOR = 1.45f;

    /** How long an engine takes to stop turning once the fuel is cut. */
    private static final float STOP_SECONDS = 1.15f;

    /** How fast displayed rpm chases demanded rpm. A crank has inertia; audio must not step. */
    private static final float RPM_SLEW_PER_SECOND = 9000f;

    // ---- Condition thresholds -------------------------------------------------------------

    /** Health above which an engine runs cleanly. */
    static final float HEALTHY_ABOVE = 0.70f;

    /** Health below which one cylinder has stopped firing altogether. */
    static final float DEAD_CYLINDER_BELOW = 0.35f;

    /** Health at which the exhaust starts to open up, and the health at which it is fully gone. */
    static final float BREACH_FROM = 0.80f;

    static final float BREACH_TO = 0.10f;

    /** The worst random misfire rate a nearly-dead engine reaches. */
    static final float MAX_MISFIRE = 0.18f;

    /** How far open a completely wrecked exhaust is. Not 1.0: some of it is always still attached. */
    static final float MAX_BREACH = 0.90f;

    private final int cylinders;
    private final float idleRpm;
    private final int deadCylinderIndex;

    private Phase phase = Phase.CRANKING;
    private float elapsed;
    private float rpm;

    /**
     * @param cylinders how many cylinders this engine has, for choosing which one dies
     * @param idleRpm the car's own idle speed
     * @param seed the vehicle's id, so two of the same car do not lose the same cylinder
     */
    EngineRunState(int cylinders, float idleRpm, long seed) {
        this.cylinders = Math.max(1, cylinders);
        this.idleRpm = Math.max(200f, idleRpm);
        this.deadCylinderIndex = (int) Math.floorMod(seed * 0x9E3779B97F4A7C15L >>> 32, this.cylinders);
    }

    Phase phase() {
        return phase;
    }

    /** True once a shutdown has finished and the voice can be released. */
    boolean isFinished() {
        return phase == Phase.OFF;
    }

    /** Cuts the fuel. The engine keeps turning until it runs out of rotation. */
    void beginShutdown() {
        if (phase != Phase.STOPPING && phase != Phase.OFF) {
            phase = Phase.STOPPING;
            elapsed = 0f;
        }
    }

    /**
     * Advances one frame and returns what the synthesiser should do.
     *
     * @param dtSeconds the real frame delta (DEC-049), not a wall clock read
     * @param demandRpm what the vehicle's speed and throttle say the engine should be doing
     * @param throttle driver demand in {@code [0,1]}
     * @param load how hard the engine is working in {@code [0,1]}
     * @param healthFraction the chassis's health in {@code [0,1]}
     */
    EngineSynth.State advance(float dtSeconds, float demandRpm, float throttle, float load, float healthFraction) {
        elapsed += dtSeconds;
        float dt = Math.max(0f, dtSeconds);

        switch (phase) {
            case CRANKING -> {
                // Inertia first: a cold engine does not arrive at cranking speed, it is dragged there.
                float spinUp = Math.min(1f, elapsed / CRANK_SPIN_UP_SECONDS);
                float mean = CRANK_RPM * spinUp;
                // Then the labour, once per compression. Computed from the mean speed rather than the
                // modulated one, which would otherwise chase its own tail.
                float compressionHz = mean / 120f * cylinders;
                float labour = 1f - CRANK_LABOUR_DEPTH * (float) Math.sin(2.0 * Math.PI * compressionHz * elapsed);
                rpm = Math.max(40f, mean * labour);
                if (elapsed >= CRANK_SECONDS) {
                    phase = Phase.CATCHING;
                    elapsed = 0f;
                }
                // A cranking engine is pumping air hard through an open exhaust on every stroke, and
                // that chuffing is most of what a start sounds like. It was near silent before,
                // leaving nothing but the starter's own whine — which is why it read as a machine
                // fault rather than as a car.
                float catching = Math.max(0f, (elapsed - CRANK_SECONDS * 0.72f) / (CRANK_SECONDS * 0.28f));
                return state(rpm, 0f, CRANK_PUMPING + 0.35f * Math.min(1f, catching), true, healthFraction, 0f);
            }
            case CATCHING -> {
                float u = Math.min(1f, elapsed / CATCH_SECONDS);
                rpm = CRANK_RPM + (idleRpm * FLARE_FACTOR - CRANK_RPM) * u;
                if (u >= 1f) {
                    phase = Phase.RUNNING;
                    elapsed = 0f;
                }
                // Ragged on purpose: cylinders catch one at a time, so the engine stumbles before it
                // picks itself up. Fed through the misfire parameter, which already means exactly
                // "this cylinder did not burn properly".
                return state(rpm, 0.3f, 0.45f + 0.55f * u, false, healthFraction, CATCH_MISFIRE * (1f - u));
            }
            case RUNNING -> {
                rpm = slew(rpm, Math.max(idleRpm, demandRpm), dt);
                return state(rpm, throttle, load, false, healthFraction);
            }
            case STOPPING -> {
                // An engine does not coast smoothly to zero: it slows, and the last compression it
                // cannot get over pushes it back. The exponent is that asymmetry.
                float u = Math.min(1f, elapsed / (STOP_SECONDS * 0.85f));
                rpm = idleRpm * (float) Math.pow(1.0 - u, 1.7);
                if (elapsed >= STOP_SECONDS) {
                    phase = Phase.OFF;
                    rpm = 0f;
                }
                return state(rpm, 0f, 0f, false, healthFraction);
            }
            default -> {
                return EngineSynth.State.STOPPED;
            }
        }
    }

    private static float slew(float current, float target, float dt) {
        float maximum = RPM_SLEW_PER_SECOND * dt;
        float delta = target - current;
        if (delta > maximum) {
            return current + maximum;
        }
        return delta < -maximum ? current - maximum : target;
    }

    /** How hard a cranking engine pumps, as a load fraction. */
    private static final float CRANK_PUMPING = 0.55f;

    /** Misfire rate at the instant an engine catches, decaying to nothing as it picks up. */
    private static final float CATCH_MISFIRE = 0.55f;

    private EngineSynth.State state(float atRpm, float throttle, float load, boolean starter, float health) {
        return state(atRpm, throttle, load, starter, health, 0f);
    }

    private EngineSynth.State state(
            float atRpm, float throttle, float load, boolean starter, float health, float extraMisfire) {
        float healthy = clamp01(health);
        float sick = HEALTHY_ABOVE <= 0f ? 0f : clamp01((HEALTHY_ABOVE - healthy) / HEALTHY_ABOVE);
        float misfire = clamp01(MAX_MISFIRE * sick * sick + extraMisfire);
        float breach = clamp01((BREACH_FROM - healthy) / (BREACH_FROM - BREACH_TO)) * MAX_BREACH;
        int dead = healthy < DEAD_CYLINDER_BELOW ? deadCylinderIndex : -1;
        return new EngineSynth.State(atRpm, throttle, load, starter, dead, misfire, breach);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
