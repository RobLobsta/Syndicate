/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btSolverMode;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The per-match Bullet world (docs/06_physics_simulation.md#D06-S4.1, #D06-S5.1). */
@Tag("integration")
class PhysicsWorldTest {

    private PhysicsTestScene scene;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new PhysicsTestScene(1337L);
    }

    @AfterEach
    void tearDown() {
        scene.close();
        // AC-D02-10 / AC-D06-20: a native leak is invisible until a soak test finds it days later,
        // so every integration test settles the account.
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void world_isConfiguredAsD06S41Specifies() {
        var info = scene.physics().dynamicsWorld().getSolverInfo();

        assertThat(scene.physics().dynamicsWorld().getGravity())
                .isEqualTo(new Vector3(
                        SimulationConstants.WORLD_GRAVITY_X,
                        SimulationConstants.WORLD_GRAVITY_Y,
                        SimulationConstants.WORLD_GRAVITY_Z));
        assertThat(info.getNumIterations()).isEqualTo(PhysicsWorld.SOLVER_ITERATIONS);
        assertThat(info.getSplitImpulse()).isNotZero();
        assertThat(info.getSplitImpulsePenetrationThreshold())
                .isEqualTo(PhysicsWorld.SPLIT_IMPULSE_PENETRATION_THRESHOLD_M);
        assertThat(info.getErp()).isEqualTo(PhysicsWorld.ERP);
        assertThat(info.getErp2()).isEqualTo(PhysicsWorld.ERP2);
        assertThat(info.getSolverMode() & btSolverMode.SOLVER_USE_WARMSTARTING).isNotZero();
        assertThat(info.getSolverMode() & btSolverMode.SOLVER_SIMD).isNotZero();
    }

    @Test
    void nativeWorld_holdsExactlyTheBodiesThisWorldThinksItHas() {
        // The configuration above is what we *set*; this is what Bullet *has*. They are different
        // claims, and only the second one is evidence: a body added to the list but not to the
        // native world would simulate nowhere while every Java-side count looked right.
        assertThat(scene.physics().dynamicsWorld().getNumCollisionObjects()).isZero();

        int first = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(0f, 5f, 0f));
        scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(4f, 5f, 0f));
        assertThat(scene.physics().dynamicsWorld().getNumCollisionObjects())
                .isEqualTo(scene.physics().bodyCount())
                .isEqualTo(2);

        scene.physics().removeBody(scene.bodyOf(first));

        assertThat(scene.physics().dynamicsWorld().getNumCollisionObjects())
                .isEqualTo(scene.physics().bodyCount())
                .isEqualTo(1);
    }

    @Test
    void addingABodyTwice_isRejected() {
        // D06-E18: gdx-bullet does not guard this, and a double-added body is stepped twice per
        // tick — which reads as a body falling at 2g, a symptom nobody traces back to the add.
        int entityId = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(0f, 5f, 0f));

        assertThatThrownBy(() -> scene.physics().addBody(scene.bodyOf(entityId), CollisionLayer.DEBRIS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("D06-E18");
    }

    @Test
    void removingABody_leavesItAllocatedForItsOwnerToDispose() {
        // D02-S5.7 rule 3: bodies belong to the entity's RigidBodyComponent. The world evicts, the
        // component disposes — a world that disposed here would free a body its owner still holds.
        int entityId = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(0f, 5f, 0f));
        var body = scene.bodyOf(entityId);

        assertThat(scene.physics().contains(body)).isTrue();
        assertThat(scene.physics().removeBody(body)).isTrue();

        assertThat(scene.physics().contains(body)).isFalse();
        assertThat(scene.physics().removeBody(body)).isFalse();
        assertThat(body.getCPointer()).isNotZero();
    }

    @Test
    void queuedImpulses_drainInAscendingEntityIdThenQueueOrder() {
        // G3: the step must not depend on which system queued first, only on the fixed ordering.
        int first = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(0f, 5f, 0f));
        int second = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(4f, 5f, 0f));

        scene.physics().queueImpulse(second, new Vector3(1f, 0f, 0f));
        scene.physics().queueImpulse(first, new Vector3(2f, 0f, 0f));
        scene.physics().queueTorqueImpulse(second, new Vector3(0f, 3f, 0f));

        List<PendingImpulse> drained = scene.physics().drainQueuedImpulses();

        assertThat(drained).extracting(PendingImpulse::entityId).containsExactly(first, second, second);
        assertThat(drained.get(1).kind()).isEqualTo(PendingImpulse.Kind.CENTRAL);
        assertThat(drained.get(2).kind()).isEqualTo(PendingImpulse.Kind.TORQUE);
        assertThat(scene.physics().drainQueuedImpulses()).isEmpty();
    }

    @Test
    void queuedImpulse_copiesTheCallersVector() {
        // Callers hand over scratch vectors and go on mutating them; a queue that stored the
        // reference would apply whatever was left in it several systems later.
        int entityId = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(0f, 5f, 0f));
        Vector3 scratch = new Vector3(1f, 2f, 3f);

        scene.physics().queueImpulse(entityId, scratch);
        scratch.set(99f, 99f, 99f);

        assertThat(scene.physics().drainQueuedImpulses().get(0).impulse()).isEqualTo(new Vector3(1f, 2f, 3f));
    }

    @Test
    void nonFiniteImpulse_isRejectedAtTheQueue() {
        // D00-R13: NaN never enters the solver, where one bad value corrupts every island it
        // touches within a few ticks.
        int entityId = scene.spawnBox(new Vector3(0.5f, 0.5f, 0.5f), 10f, new Vector3(0f, 5f, 0f));

        assertThatThrownBy(() -> scene.physics().queueImpulse(entityId, new Vector3(Float.NaN, 0f, 0f)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> scene.physics().queueImpulse(entityId, new Vector3(0f, Float.POSITIVE_INFINITY, 0f)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispose_isIdempotent() {
        PhysicsWorld world = PhysicsWorld.create();
        world.dispose();
        world.dispose();

        assertThat(world.isDisposed()).isTrue();
    }
}
