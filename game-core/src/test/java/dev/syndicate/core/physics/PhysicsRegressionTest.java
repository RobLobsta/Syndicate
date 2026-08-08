/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.util.NativeResourceTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Seed-locked physics scenarios (docs/12_testing_validation_ci.md#D12-S5.2,
 * docs/06_physics_simulation.md#D06-S8).
 *
 * <p>These are the L3 level of D12-S4.1: the arithmetic every other layer is built on, asserted
 * against numbers a person can check by hand. The self-consistency rerun is the most valuable
 * assertion in the file — a drifting expectation is a tuning problem, but a run-to-run difference is
 * always a correctness bug (D12-R9).
 */
@Tag("physics")
class PhysicsRegressionTest {

    /**
     * Metres. The tolerance for two runs of one scenario in one process, from T-D06-5. It is not a
     * physics tolerance: same build, same platform, same seed must agree, and a failure here means
     * unsorted iteration, unseeded randomness, or a wall-clock read — never a tuning problem.
     */
    private static final float SELF_CONSISTENCY_TOLERANCE_M = 0.001f;

    /** Steel, kg/m³ (D09-S6.3). A 1 m³ cube of it is the T-D06-2 test body. */
    private static final float STEEL_DENSITY_KG_PER_M3 = 7850f;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
    }

    @AfterEach
    void tearDown() {
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void steelCubeDroppedFromTwoMetres_restsOnItsHalfExtent() {
        // T-D06-2 (docs/06_physics_simulation.md#D06-S8): rests at y = 0.5 ± 0.005 m, jitter
        // ≤ 0.01 m/s. The margin of D06-R13 is what this really measures: at Bullet's default
        // 0.04 m the cube would settle 4 cm high, visibly floating.
        try (PhysicsTestScene scene = new PhysicsTestScene(1337L)) {
            scene.addGround();
            int cube = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), STEEL_DENSITY_KG_PER_M3, new Vector3(0f, 2f, 0f));

            scene.step(240);

            TransformComponent transform = scene.world().getComponent(cube, TransformComponent.class);
            VelocityComponent velocity = scene.world().getComponent(cube, VelocityComponent.class);
            assertThat(transform.position.y).isEqualTo(0.5f, within(0.005f));
            assertThat(velocity.linear.len()).isLessThan(0.01f);
        }
    }

    @Test
    void sameScenarioTwice_producesTheSameFinalState() {
        // T-D06-5 / D12-R9: same build, same seed, same input sequence — identical state.
        Vector3 first = runScatterScenario();
        Vector3 second = runScatterScenario();

        assertThat(first.dst(second))
                .as("non-determinism: same seed, same process, different result — always a bug")
                .isLessThan(SELF_CONSISTENCY_TOLERANCE_M);
    }

    /**
     * Six boxes dropped onto the ground and shoved apart, so the run exercises the contact solver,
     * multi-body islands and the impulse queue rather than free fall alone.
     *
     * @return the final position of the first box
     */
    private Vector3 runScatterScenario() {
        try (PhysicsTestScene scene = new PhysicsTestScene(1337L)) {
            scene.addGround();
            int[] boxes = new int[6];
            for (int i = 0; i < boxes.length; i++) {
                boxes[i] = scene.spawnBox(
                        new Vector3(0.4f, 0.4f, 0.4f), 120f, new Vector3(i * 0.35f, 1.5f + i * 0.9f, 0f));
            }

            scene.step(30);
            for (int i = 0; i < boxes.length; i++) {
                scene.physics().queueImpulse(boxes[i], new Vector3(40f - i * 9f, 15f, i * 6f));
            }
            scene.step(300);

            return new Vector3(scene.world().getComponent(boxes[0], TransformComponent.class).position);
        }
    }
}
