/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.verify.asset.MeshData;
import dev.syndicate.verify.check.Tolerances;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The harness's world is the game's world (docs/14_test_environment.md#D14-S5.5,
 * docs/06_physics_simulation.md#D06-S4.1).
 *
 * <p>These assertions exist because their absence cost something. `TestWorld` built its own Bullet
 * world at a 0.005 m margin while the game used 0.010 m, and no check could see it: PHYS-008 derives
 * its expected resting height from `TestWorld.COLLISION_MARGIN_M`, so the harness measured itself
 * against its own wrong number and agreed (DEV-007). The two configurations now come from one place,
 * and this file is what keeps them there.
 */
@Tag("integration")
class TestWorldTest {

    /** The D14-S6.4 defaults, so this test and PHYS-008 judge by the same numbers. */
    private static final Tolerances TOLERANCES = new Tolerances();

    /** Half-extent of the 1 m test cube, metres. */
    private static final float HALF_EXTENT_M = 0.5f;

    @Test
    void harnessMargin_isTheGamesMargin() {
        // AC-D06-4 / D06-R13. Not "is close to" — the harness must use the same constant, because a
        // resting height is exactly one margin above the ground and the fixtures measure that.
        assertThat(TestWorld.COLLISION_MARGIN_M).isEqualTo(PhysicsWorld.COLLISION_MARGIN_M);
        assertThat(TestWorld.COLLISION_MARGIN_M).isEqualTo(0.01f);
    }

    @Test
    void world_isBuiltByPhysicsWorldCreate() {
        // D14-R10 / AC-D14-10: the harness constructs its world through game-core's
        // PhysicsWorld.create(). A harness verifying against a bespoke physics setup would prove
        // nothing about the game — so the solver settings are read back from the live world rather
        // than trusted.
        NativeResourceTracker.install();
        try {
            try (TestWorld world = new TestWorld(false)) {
                assertThat(world.physics().isDisposed()).isFalse();
                assertThat(world.world().getGravity())
                        .isEqualTo(new Vector3(
                                SimulationConstants.WORLD_GRAVITY_X,
                                SimulationConstants.WORLD_GRAVITY_Y,
                                SimulationConstants.WORLD_GRAVITY_Z));
                assertThat(world.world().getSolverInfo().getNumIterations()).isEqualTo(PhysicsWorld.SOLVER_ITERATIONS);
                assertThat(world.world().getSolverInfo().getSplitImpulse()).isNotZero();
                assertThat(world.world().getSolverInfo().getErp2()).isEqualTo(PhysicsWorld.ERP2);
            }
            // TestWorld disposes the bodies and shapes it owns and PhysicsWorld disposes the five it
            // owns; between them the account settles (AC-D06-20, D12-R7).
            assertThat(NativeResourceTracker.outstanding())
                    .as(NativeResourceTracker.describeOutstanding())
                    .isZero();
        } finally {
            NativeResourceTracker.uninstall();
        }
    }

    @Test
    void oneMetreCube_restsOneMarginAboveTheGround() {
        // The PHYS-008 formula (expected = -min.y + margin) run without a Blender-processed fixture,
        // so CI can catch a margin regression on a machine that has no Blender. At the game's 0.01 m
        // margin the cube settles at 0.510 m; the 0.005 m the harness used to build put it at
        // 0.505 m, which is exactly the RESTING_POSITION_M tolerance away — close enough to look
        // like drift, far enough to be wrong.
        try (TestWorld world = new TestWorld(true)) {
            btConvexHullShape hull = unitCubeHull();
            world.ownShape(hull);
            btRigidBody body = world.addBody(hull, 7850f, new Matrix4().setToTranslation(0f, 2f, 0f), 0.7f, 0.05f);

            world.step(SimulationConstants.TICK_RATE_HZ * 4);

            float restY = body.getWorldTransform().getTranslation(new Vector3()).y;
            float expected = HALF_EXTENT_M + TestWorld.COLLISION_MARGIN_M;
            assertThat(restY)
                    .as("resting height = half extent + one collision margin")
                    .isEqualTo(expected, within((float) TOLERANCES.get(Tolerances.RESTING_POSITION_M)));
            assertThat(body.getLinearVelocity().len()).as("post-rest jitter").isLessThan((float)
                    TOLERANCES.get(Tolerances.RESTING_JITTER_MPS));
        }
    }

    @Test
    void simplifiedHull_sitsExactlyOneMarginOutsideItsMesh() {
        // DISC-008. btShapeHull samples support points from the shape it is handed, margin included,
        // and ignores its own `buildHull(margin)` argument — so a source shape carrying a margin
        // yields hull points already pushed one margin out, and the simplified shape adds another.
        // A simplified hull then rests two margins above the ground while an unsimplified one rests
        // one, which is what failed the sphere and cylinder fixtures at 0.01 m.
        //
        // Asserted on the hull's own geometry rather than on a resting height: this is a property of
        // shape construction, and reading it directly says which of the two numbers is wrong.
        MeshData sphere = icosphereAboveOrigin();
        try (TestWorld world = new TestWorld(false)) {
            btConvexHullShape simplified = world.buildHull(sphere, 32);
            // Simplification happened, but note it does not honour the budget it was given:
            // btShapeHull samples a fixed set of directions and returns 42 points regardless of
            // `maxVertices`, which is only a threshold for whether to simplify at all. The game's
            // shard hulls come from the Blender tool (D09-S5.5) and do respect D06-R6's 32; the
            // harness's rebuilt ones do not, and ASSET-011 compares against them.
            assertThat(simplified.getNumPoints()).isLessThan(sphere.vertexCount());

            float hullBottom = simplified.localGetSupportingVertexWithoutMargin(new Vector3(0f, -1f, 0f)).y;

            assertThat(hullBottom)
                    .as("simplified hull's own geometry must not carry a baked-in margin")
                    .isEqualTo(0f, within(1e-6f));
            assertThat(simplified.getMargin()).isEqualTo(TestWorld.COLLISION_MARGIN_M);
        }
    }

    /**
     * A sphere of radius 0.5 sitting on the origin plane, with enough vertices to force the
     * simplification path. Generated rather than loaded, so the test needs no processed fixture.
     */
    private static MeshData icosphereAboveOrigin() {
        int rings = 24;
        int segments = 48;
        float[] positions = new float[(rings + 1) * (segments + 1) * 3];
        int p = 0;
        for (int ring = 0; ring <= rings; ring++) {
            double phi = Math.PI * ring / rings;
            for (int segment = 0; segment <= segments; segment++) {
                double theta = 2 * Math.PI * segment / segments;
                positions[p++] = (float) (HALF_EXTENT_M * Math.sin(phi) * Math.cos(theta));
                // Shifted up by the radius so the lowest vertex is exactly y = 0, matching how the
                // fixtures are authored and making the expected hull bottom a round number.
                positions[p++] = (float) (HALF_EXTENT_M * Math.cos(phi) + HALF_EXTENT_M);
                positions[p++] = (float) (HALF_EXTENT_M * Math.sin(phi) * Math.sin(theta));
            }
        }
        return new MeshData("probe_sphere", positions, new int[0]);
    }

    /** A 1 m cube as eight hull points, at the harness's margin. */
    private static btConvexHullShape unitCubeHull() {
        btConvexHullShape hull = new btConvexHullShape();
        float[] coordinates = {-HALF_EXTENT_M, HALF_EXTENT_M};
        for (float x : coordinates) {
            for (float y : coordinates) {
                for (float z : coordinates) {
                    hull.addPoint(new Vector3(x, y, z), false);
                }
            }
        }
        hull.recalcLocalAabb();
        hull.setMargin(TestWorld.COLLISION_MARGIN_M);
        return hull;
    }
}
