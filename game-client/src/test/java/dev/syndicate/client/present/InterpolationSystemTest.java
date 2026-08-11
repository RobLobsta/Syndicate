/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.present;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.client.component.RenderTransformComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Slot 22 places a body between two ticks (docs/03_runtime_modes.md#D03-S5.3).
 *
 * <p>What these tests are really protecting is the sampling rule. The obvious implementation samples
 * the world transform every time it runs, and because this system runs per frame that makes the
 * "previous" sample one frame old rather than one tick old — at which point the interpolation is
 * between two positions a millisecond apart and does nothing at all. The bug is invisible in a
 * screenshot and obvious in motion, which is the worst combination to find late.
 */
@Tag("unit")
class InterpolationSystemTest {

    private static final org.assertj.core.data.Offset<Float> TOLERANCE = offset(1e-4f);

    private World world;
    private InterpolationSystem system;
    private int entity;
    private TransformComponent transform;

    @BeforeEach
    void setUp() {
        world = new World(1337L, true);
        system = new InterpolationSystem();
        world.registerSystems(List.of(system, new NoOpSimulation()));

        Entity created = world.createEntity();
        entity = created.id();
        transform = new TransformComponent();
        world.addComponent(entity, transform);
    }

    /** Slot 22 gives the render transform to anything the simulation created (D03-R14's side). */
    @Test
    void everyTransformGetsARenderTransform() {
        world.present(0f, 1f / 60f);
        assertThat(world.getComponent(entity, RenderTransformComponent.class)).isNotNull();
    }

    /** One sample is one position: an entity that has just appeared is drawn where it is. */
    @Test
    void theFirstFrameDrawsAtTheCurrentPosition() {
        place(0f, 1f, 0f);
        world.present(0f, 1f / 60f);
        world.present(0.5f, 1f / 60f);

        assertThat(renderPosition()).isEqualTo(new Vector3(0f, 1f, 0f));
    }

    /** Halfway through a tick, a body that moved a metre is drawn half a metre along. */
    @Test
    void aFrameHalfwayThroughATickIsDrawnHalfway() {
        place(0f, 0f, 0f);
        world.tick(0);
        world.present(0f, 1f / 60f);

        place(2f, 0f, 0f);
        world.tick(1);
        world.present(0.5f, 1f / 60f);

        assertThat(renderPosition().x).isEqualTo(1f, TOLERANCE);
    }

    /**
     * Several frames inside one tick keep interpolating between the same pair.
     *
     * <p>This is the sampling rule stated as a test. If the system re-sampled per frame, the second
     * frame's "previous" would be the first frame's interpolated position and the entity would creep
     * toward the current tick rather than tracking alpha.
     */
    @Test
    void severalFramesInOneTickShareOneSamplePair() {
        place(0f, 0f, 0f);
        world.tick(0);
        world.present(0f, 1f / 60f);

        place(4f, 0f, 0f);
        world.tick(1);
        world.present(0.25f, 1f / 240f);
        assertThat(renderPosition().x).isEqualTo(1f, TOLERANCE);

        world.present(0.5f, 1f / 240f);
        assertThat(renderPosition().x).isEqualTo(2f, TOLERANCE);

        world.present(0.75f, 1f / 240f);
        assertThat(renderPosition().x).isEqualTo(3f, TOLERANCE);
    }

    /** A respawn is a discontinuity, not motion: past the snap distance the entity jumps. */
    @Test
    void aTeleportSnapsRatherThanSmearing() {
        place(0f, 0f, 0f);
        world.tick(0);
        world.present(0f, 1f / 60f);

        place(InterpolationSystem.SNAP_DISTANCE_M * 4f, 0f, 0f);
        world.tick(1);
        world.present(0.5f, 1f / 60f);

        assertThat(renderPosition().x).isEqualTo(InterpolationSystem.SNAP_DISTANCE_M * 4f, TOLERANCE);
    }

    private void place(float x, float y, float z) {
        transform.position.set(x, y, z);
        transform.worldMatrix.setToTranslation(x, y, z);
    }

    private Vector3 renderPosition() {
        RenderTransformComponent render = world.getComponent(entity, RenderTransformComponent.class);
        return render.renderMatrix.getTranslation(new Vector3());
    }

    /** A tick has to run for the tick number to advance; this system is what makes it non-empty. */
    private static final class NoOpSimulation implements EntitySystem {

        @Override
        public Phase phase() {
            return Phase.SIM;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public void update(World world, float dtSeconds, long tick) {
            // Deliberately nothing: the transform is placed by the test, not by a system.
        }
    }
}
