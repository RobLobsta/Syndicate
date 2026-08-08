/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.PhysicsTestScene;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.SimulationConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 10 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/06_physics_simulation.md#D06-S5.4).
 */
@Tag("integration")
class PhysicsSystemTest {

    private static final Vector3 UNIT_BOX = new Vector3(0.5f, 0.5f, 0.5f);

    private PhysicsTestScene scene;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new PhysicsTestScene(1337L);
    }

    @AfterEach
    void tearDown() {
        scene.close();
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void system_occupiesSlot10OfTheSimPhase() {
        // AC-D04-3: the order is a compile-time constant from the D04-S4.4 catalogue.
        assertThat(scene.physicsSystem().order()).isEqualTo(10);
        assertThat(scene.physicsSystem().phase()).isEqualTo(Phase.SIM);
        assertThat(scene.world().schedule()).containsExactly(scene.physicsSystem());
    }

    @Test
    void variableTimestep_isRejected() {
        // T-D06-1 / AC-D06-1 (docs/06_physics_simulation.md#D06-S8). A caller passing anything but
        // TICK_DT has a broken accumulator; accepting the step would hide it and let frame rate
        // leak into simulation results (G2).
        assertThatThrownBy(() -> scene.physicsSystem().update(scene.world(), 0.02f, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TICK_DT");
    }

    @Test
    void freeFall_advancesExactlyOneTickPerCall() {
        // AC-D06-2: maxSubSteps = 0 and fixedTimeStep = TICK_DT means one step of exactly TICK_DT
        // per call. If Bullet ran its own accumulator the velocity after N calls would depend on
        // how the substeps happened to land, and this equality would not hold.
        int entityId = scene.spawnBox(UNIT_BOX, 10f, new Vector3(0f, 50f, 0f));
        int ticks = 30;

        scene.step(ticks);

        VelocityComponent velocity = scene.world().getComponent(entityId, VelocityComponent.class);
        float expected = SimulationConstants.WORLD_GRAVITY_Y * SimulationConstants.TICK_DT * ticks;
        assertThat(velocity.linear.y).isEqualTo(expected, within(1e-4f));
        assertThat(velocity.linear.x).isZero();
    }

    @Test
    void transformAndVelocity_mirrorTheBodyAfterEveryStep() {
        // D06-S5.4 step 3: components are the only thing downstream systems read, so they must
        // agree with Bullet exactly, not approximately.
        int entityId = scene.spawnBox(UNIT_BOX, 10f, new Vector3(2f, 20f, -3f));
        scene.physics().queueTorqueImpulse(entityId, new Vector3(0f, 5f, 0f));

        scene.step(20);

        TransformComponent transform = scene.world().getComponent(entityId, TransformComponent.class);
        VelocityComponent velocity = scene.world().getComponent(entityId, VelocityComponent.class);
        Vector3 bodyPosition = scene.bodyOf(entityId).getWorldTransform().getTranslation(new Vector3());

        assertThat(transform.position.epsilonEquals(bodyPosition, 1e-6f))
                .as("transform %s vs body %s", transform.position, bodyPosition)
                .isTrue();
        assertThat(velocity.linear.epsilonEquals(scene.bodyOf(entityId).getLinearVelocity(), 1e-6f))
                .isTrue();
        assertThat(velocity.angular.y).isGreaterThan(0f);
        assertThat(transform.dirty).isTrue();
    }

    @Test
    void queuedImpulse_isAppliedBeforeTheStepItWasQueuedFor() {
        // T-D06-3 (docs/06_physics_simulation.md#D06-S8): 100 N·s on a 10 kg body gives 10 m/s.
        int entityId = scene.spawnBox(UNIT_BOX, 10f, new Vector3(0f, 20f, 0f));

        scene.physics().queueImpulse(entityId, new Vector3(100f, 0f, 0f));
        scene.step();

        VelocityComponent velocity = scene.world().getComponent(entityId, VelocityComponent.class);
        assertThat(velocity.linear.x).isEqualTo(10f, within(0.5f)); // 5%
    }

    @Test
    void offCentreImpulse_impartsSpin() {
        // The distinction the queue's AT_POINT kind exists for: an impulse away from the centre of
        // mass is what makes a hit on a wing spin a vehicle rather than shove it.
        int entityId = scene.spawnBox(UNIT_BOX, 10f, new Vector3(0f, 20f, 0f));

        scene.physics().queueImpulseAt(entityId, new Vector3(0f, 0f, 20f), new Vector3(0.5f, 0f, 0f));
        scene.step();

        VelocityComponent velocity = scene.world().getComponent(entityId, VelocityComponent.class);
        assertThat(velocity.linear.z).isGreaterThan(0f);
        assertThat(velocity.angular.len()).isGreaterThan(0.1f);
    }

    @Test
    void impulseQueuedForADestroyedEntity_isDropped() {
        // Systems queue impulses during the same tick that damage may destroy their target. The
        // queue drains after that, so a dropped impulse is the normal case, not an error case.
        int entityId = scene.spawnBox(UNIT_BOX, 10f, new Vector3(0f, 20f, 0f));
        scene.physics().queueImpulse(entityId, new Vector3(100f, 0f, 0f));
        scene.world().destroyEntity(entityId);

        scene.step();

        assertThat(scene.world().isAlive(entityId)).isFalse();
        assertThat(scene.physics().nanRemovalCount()).isZero();
    }

    @Test
    void nonFiniteBody_isEvictedAndItsEntityDestroyed() {
        // T-D06-14 / D06-E2: one NaN body corrupts every solver island it touches within a few
        // ticks, so it leaves the world immediately — and the NaN never reaches a component,
        // because D00-R13 forbids storing one at all.
        int poisoned = scene.spawnBox(UNIT_BOX, 10f, new Vector3(0f, 20f, 0f));
        int healthy = scene.spawnBox(UNIT_BOX, 10f, new Vector3(10f, 20f, 0f));
        var poisonedBody = scene.bodyOf(poisoned);
        scene.step();

        poisonedBody.setLinearVelocity(new Vector3(Float.NaN, 0f, 0f));
        scene.step();

        assertThat(scene.physics().nanRemovalCount()).isEqualTo(1);
        assertThat(scene.physics().contains(poisonedBody)).isFalse();
        assertThat(scene.world().isAlive(poisoned)).isFalse();

        // The rest of the world keeps simulating, which is the point of evicting rather than throwing.
        scene.step(600);
        TransformComponent survivor = scene.world().getComponent(healthy, TransformComponent.class);
        assertThat(Float.isFinite(survivor.position.y)).isTrue();
        assertThat(survivor.position.y).isLessThan(20f);
    }

    @Test
    void staticBodies_areSteppedWithoutMoving() {
        // D06-R3: a static body has mass exactly 0 and never integrates, but it is still in the
        // family and must survive the pull-back untouched.
        scene.addGround();
        int entityId = scene.spawnBox(UNIT_BOX, 10f, new Vector3(0f, 3f, 0f));

        scene.step(120);

        TransformComponent transform = scene.world().getComponent(entityId, TransformComponent.class);
        assertThat(transform.position.y).isGreaterThan(0f);
        assertThat(scene.physics().bodyCount()).isEqualTo(2);
    }
}
