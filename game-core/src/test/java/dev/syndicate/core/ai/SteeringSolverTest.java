/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The driving model every behaviour shares (docs/11_ai_bots_and_match_simulation.md#D11-S5.3). */
@Tag("unit")
class SteeringSolverTest {

    private static final float MAX_STEER_RAD = 0.5236f;
    private static final float MAX_SPEED_MPS = 80f;

    private final SteeringSolver solver = new SteeringSolver();
    private final SensorSnapshot snapshot = new SensorSnapshot();

    private static final Vector3 FORWARD_Z = new Vector3(0f, 0f, 1f);

    @Test
    void straightAhead_needsNoSteering() {
        SteeringSolver.Controls controls = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(0f, 0f, 50f), snapshot, MAX_STEER_RAD, 20f, MAX_SPEED_MPS);

        assertThat(controls.steer()).isCloseTo(0f, within(0.01f));
        assertThat(controls.throttle()).isGreaterThan(0.9f);
        assertThat(controls.brake()).isZero();
    }

    /** A target to the right steers right; the sign is the whole point of a *signed* angle. */
    @Test
    void targetToTheRight_steersRight() {
        SteeringSolver.Controls right = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(30f, 0f, 30f), snapshot, MAX_STEER_RAD, 20f, MAX_SPEED_MPS);
        SteeringSolver.Controls left = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(-30f, 0f, 30f), snapshot, MAX_STEER_RAD, 20f, MAX_SPEED_MPS);

        assertThat(right.steer()).isPositive();
        assertThat(left.steer()).isNegative();
    }

    /** Behind the bow at speed: brake and turn, rather than arc into whatever is there. */
    @Test
    void targetBehind_atSpeed_brakes() {
        SteeringSolver.Controls controls = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(0f, 0f, -50f), snapshot, MAX_STEER_RAD, 30f, MAX_SPEED_MPS);

        assertThat(controls.brake()).isEqualTo(1f);
        assertThat(controls.throttle()).isZero();
    }

    /**
     * The creep floor: a stationary bot with somewhere to be applies real throttle.
     *
     * <p>Without it, the turn slowdown gives a bot pointing away from its destination about 0.2
     * throttle, difficulty scaling takes that to 0.15, the car does not move, and the stuck detector
     * never fires because it watches for throttle above 0.5. That combination parked a bot on its
     * spawn point for an entire match.
     */
    @Test
    void stationary_withSomewhereToBe_appliesRealThrottle() {
        SteeringSolver.Controls controls = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(0f, 0f, -8f), snapshot, MAX_STEER_RAD, 0f, MAX_SPEED_MPS);

        assertThat(controls.throttle()).isGreaterThanOrEqualTo(SteeringSolver.CREEP_THROTTLE);
    }

    /** Inside the station-keeping radius, standing still is the right answer. */
    @Test
    void stationary_atItsDestination_doesNotCreep() {
        SteeringSolver.Controls controls = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(0f, 0f, 1f), snapshot, MAX_STEER_RAD, 0f, MAX_SPEED_MPS);

        assertThat(controls.throttle()).isLessThan(SteeringSolver.CREEP_THROTTLE);
    }

    /** At the speed limit the bot lifts off, whatever the steering says. */
    @Test
    void atTopSpeed_liftsOff() {
        SteeringSolver.Controls controls = solver.solve(
                new Vector3(),
                FORWARD_Z,
                new Vector3(0f, 0f, 200f),
                snapshot,
                MAX_STEER_RAD,
                MAX_SPEED_MPS,
                MAX_SPEED_MPS);

        assertThat(controls.throttle()).isZero();
    }

    /** An obstacle dead ahead bends the desired direction away from it. */
    @Test
    void obstacleAhead_steersAround() {
        snapshot.nearbyObstacles.add(new Vector3(0f, 0f, 8f));

        SteeringSolver.Controls controls = solver.solve(
                new Vector3(), FORWARD_Z, new Vector3(0f, 0f, 60f), snapshot, MAX_STEER_RAD, 20f, MAX_SPEED_MPS);

        // The avoidance term is directly opposed to the goal here, so what it must not do is leave
        // the bot driving straight into the obstacle at full throttle.
        assertThat(controls.throttle()).isLessThan(0.9f);
    }

    /** The signed angle is the primitive the whole solver rests on. */
    @Test
    void signedAngle_isPositiveToTheRight() {
        assertThat(SteeringSolver.signedAngleY(FORWARD_Z, new Vector3(1f, 0f, 0f)))
                .isCloseTo((float) (Math.PI / 2.0), within(1e-4f));
        assertThat(SteeringSolver.signedAngleY(FORWARD_Z, new Vector3(-1f, 0f, 0f)))
                .isCloseTo((float) (-Math.PI / 2.0), within(1e-4f));
    }
}
