/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.present;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DamageVisualComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Slot 23 in the world, with no renderer attached
 * (docs/07_damage_destruction_model.md#D07-S5.5).
 *
 * <p>A part with no {@code RenderModelComponent} is the normal case for the first frame of its life
 * and the permanent case for a part whose mesh would not load, and slot 23 has to keep its weights
 * correct through both — otherwise a car that starts drawing on frame two starts deforming from
 * whatever it had, rather than from where its health says it should be.
 */
@Tag("unit")
class DamageVisualSystemTest {

    private World world;
    private int part;
    private HealthComponent health;

    @BeforeEach
    void setUp() {
        world = new World(1337L, true);
        world.registerSystems(List.of(new DamageVisualSystem(), new NoOpSimulation()));

        Entity entity = world.createEntity();
        part = entity.id();
        health = new HealthComponent();
        health.maxHp = 100f;
        health.setCurrentHp(100f);
        world.addComponent(part, health);
        world.addComponent(part, new DamageStateComponent());
    }

    /** The client adds the cosmetic half of the {@code PART} archetype; the factory does not. */
    @Test
    void everyDamageablePartGetsADamageVisual() {
        world.present(0f, 1f / 60f);
        assertThat(world.getComponent(part, DamageVisualComponent.class)).isNotNull();
    }

    /** The target follows health immediately; the displayed weights ease toward it. */
    @Test
    void theTargetSnapsAndTheDisplayEases() {
        world.present(0f, 1f / 60f);
        health.setCurrentHp(50f);
        world.present(0f, 1f / 60f);

        DamageVisualComponent visual = world.getComponent(part, DamageVisualComponent.class);
        assertThat(visual.targetMorphWeights).containsExactly(new float[] {0f, 1f, 0f, 0f}, offset(1e-6f));
        assertThat(visual.morphWeights[1]).isGreaterThan(0f).isLessThan(1f);

        for (int frame = 0; frame < 120; frame++) {
            world.present(0f, 1f / 60f);
        }
        assertThat(visual.morphWeights).containsExactly(new float[] {0f, 1f, 0f, 0f}, offset(1e-6f));
    }

    /** Slot 23 runs per frame, so it must not need a renderer to have run first. */
    @Test
    void aPartWithNoModelStillTracksItsHealth() {
        health.setCurrentHp(0f);
        for (int frame = 0; frame < 120; frame++) {
            world.present(0f, 1f / 60f);
        }
        DamageVisualComponent visual = world.getComponent(part, DamageVisualComponent.class);
        assertThat(visual.morphWeights[DamageVisualComponent.MORPH_COUNT - 1]).isEqualTo(1f, offset(1e-6f));
    }

    /** A schedule needs one non-PRESENT system for a tick to be a tick. */
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
            // Deliberately nothing.
        }
    }
}
