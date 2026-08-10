/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;

/**
 * Turns a desired direction into throttle, steer and brake
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.3 {@code solveVehicleControls}).
 *
 * <p>One driving model for every behaviour (D11-R7). Retreating, hunting and patrolling all arrive
 * here with a direction and leave with controls, so a bot drives the same way whatever it is
 * thinking — which is what stops "the retreat behaviour corners badly" from being a separate bug
 * from "the patrol behaviour corners badly".
 *
 * <p><b>The two slow-downs are what stop a bot oscillating.</b> A steering-only controller aimed at
 * a waypoint overshoots at speed, turns back, overshoots again, and circles it forever. Backing off
 * the throttle in proportion to how far off the nose the target is, and again as the target gets
 * close, converts that orbit into an arrival.
 */
public final class SteeringSolver {

    /** Radius inside which the bot starts slowing for arrival (D11-S5.3 {@code ARRIVE_RADIUS_M}). */
    public static final float ARRIVE_RADIUS_M = 12.0f;

    /** The floor on the arrival throttle factor, so a bot still creeps the last few metres. */
    public static final float ARRIVE_MIN_FACTOR = 0.2f;

    /** Angle off the nose at which throttle has fallen to its floor. */
    public static final float TURN_SLOWDOWN_ANGLE_DEG = 90.0f;

    /** The most throttle a hard turn removes. */
    public static final float TURN_SLOWDOWN_MAX = 0.8f;

    /** Beyond this angle the bot stops and turns rather than trying to steer round. */
    public static final float STOP_AND_TURN_ANGLE_DEG = 120.0f;

    /** Below this speed a bot will turn in place rather than brake first. */
    public static final float STOP_AND_TURN_MIN_SPEED_MPS = 6.0f;

    /** Fraction of top speed at which the bot lifts off. */
    public static final float SPEED_LIMIT_FRACTION = 0.95f;

    /** How close two allies get before the separation term pushes them apart (D11-S5.3). */
    public static final float SEPARATION_MIN_DIST_M = 6.0f;

    /** How strongly an obstacle in the fan turns the desired direction away from it. */
    public static final float AVOIDANCE_STRENGTH = 2.0f;

    /** The controls a bot wants this tick. */
    public record Controls(float throttle, float steer, float brake) {

        /** Everything off — what a bot with nowhere to go asks for. */
        public static final Controls IDLE = new Controls(0f, 0f, 0f);
    }

    private final Vector3 desired = new Vector3();
    private final Vector3 away = new Vector3();

    /**
     * Steers toward a point, avoiding what the sensor fan found and keeping clear of allies.
     *
     * @param position the bot's position
     * @param forward the bot's forward axis, unit length
     * @param destination where it wants to be
     * @param maxSteerRad the vehicle's steering lock, from its stats
     * @param speedMps current speed
     * @param maxSpeedMps the vehicle's top speed, or 0 when unknown
     */
    public Controls solve(
            Vector3 position,
            Vector3 forward,
            Vector3 destination,
            SensorSnapshot snapshot,
            float maxSteerRad,
            float speedMps,
            float maxSpeedMps) {

        desired.set(destination).sub(position);
        desired.y = 0f;
        float distanceToGoal = desired.len();
        if (distanceToGoal < 0.001f) {
            return Controls.IDLE;
        }
        desired.scl(1f / distanceToGoal);
        applyObstacleAvoidance(position, snapshot);
        applySeparation(position, snapshot);
        if (desired.len2() < 1e-6f) {
            return Controls.IDLE;
        }
        desired.nor();

        float angleOffRad = signedAngleY(forward, desired);
        float steer = clamp(angleOffRad / Math.max(0.01f, maxSteerRad), -1f, 1f);
        float angleOffDeg = (float) Math.abs(Math.toDegrees(angleOffRad));

        float turnFactor = 1f - clamp(angleOffDeg / TURN_SLOWDOWN_ANGLE_DEG, 0f, TURN_SLOWDOWN_MAX);
        float arriveFactor = clamp(distanceToGoal / ARRIVE_RADIUS_M, ARRIVE_MIN_FACTOR, 1f);
        float throttle = turnFactor * arriveFactor;
        float brake = 0f;

        if (angleOffDeg > STOP_AND_TURN_ANGLE_DEG && speedMps > STOP_AND_TURN_MIN_SPEED_MPS) {
            // Too sharp to steer round at this speed: stop, then turn. Without this a bot asked to
            // reverse direction drives a wide arc into whatever it was running from.
            brake = 1f;
            throttle = 0f;
        }
        if (maxSpeedMps > 0f && speedMps > maxSpeedMps * SPEED_LIMIT_FRACTION) {
            throttle = 0f;
        }
        return new Controls(throttle, steer, brake);
    }

    /**
     * Bends the desired direction away from each obstacle the fan found.
     *
     * <p>Weighted by closeness, so a wall two metres ahead dominates and one at the edge of the
     * horizon barely registers. Vertical components are dropped: a car steers in the plane, and an
     * obstacle above or below it is either the ground or something it cannot avoid by turning.
     */
    private void applyObstacleAvoidance(Vector3 position, SensorSnapshot snapshot) {
        if (snapshot.nearbyObstacles.isEmpty()) {
            return;
        }
        for (Vector3 obstacle : snapshot.nearbyObstacles) {
            away.set(position).sub(obstacle);
            away.y = 0f;
            float distance = away.len();
            if (distance < 0.001f) {
                continue;
            }
            away.scl(1f / distance);
            desired.mulAdd(away, AVOIDANCE_STRENGTH / Math.max(1f, distance));
        }
    }

    /** Pushes away from allies inside {@link #SEPARATION_MIN_DIST_M} so bots do not convoy into one. */
    private void applySeparation(Vector3 position, SensorSnapshot snapshot) {
        for (PerceivedTarget target : snapshot.targets()) {
            away.set(position).sub(target.position);
            away.y = 0f;
            float distance = away.len();
            if (distance < 0.001f || distance > SEPARATION_MIN_DIST_M) {
                continue;
            }
            away.scl(1f / distance);
            desired.mulAdd(away, (SEPARATION_MIN_DIST_M - distance) / SEPARATION_MIN_DIST_M);
        }
    }

    /**
     * The signed angle from {@code forward} to {@code target} about world up, in radians.
     *
     * <p>Signed matters: an unsigned angle tells a bot how wrong it is and not which way to correct,
     * and a steering controller fed an unsigned error turns the same way regardless.
     */
    public static float signedAngleY(Vector3 forward, Vector3 target) {
        float dot = forward.x * target.x + forward.z * target.z;
        float cross = forward.z * target.x - forward.x * target.z;
        return (float) Math.atan2(cross, dot);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
