/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.effect;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.client.component.ParticleRefComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.DamageType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Slot 24 turns events into bursts (docs/04_entity_component_model.md#D04-S4.4 row 24).
 *
 * <p>Headless, with no renderer: the system's job is to create and integrate {@code EFFECT}
 * entities, and whether anything draws them is slot 26's business. That separation is what makes
 * this testable at all in a sandbox with no display.
 */
@Tag("unit")
class EffectSystemTest {

    private World world;
    private EffectSystem system;

    @BeforeEach
    void setUp() {
        world = new World(1337L, true);
        system = new EffectSystem();
        world.registerSystems(List.of(system, new NoOpSimulation()));
    }

    /** A hit worth seeing becomes a burst, one tick later, where the hit landed. */
    @Test
    void aHitBecomesABurst() {
        emitDamage(50f, new Vector3(3f, 1f, -2f));

        // The listener runs at the end of the tick; the burst is created on the frame after it.
        world.present(0f, 1f / 60f);
        assertThat(system.bursts().size()).isOne();

        world.present(0f, 1f / 60f);
        int burst = system.bursts().snapshot()[0];
        ParticleRefComponent particles = world.getComponent(burst, ParticleRefComponent.class);
        TransformComponent transform = world.getComponent(burst, TransformComponent.class);

        assertThat(particles.kind).isEqualTo(ParticleRefComponent.Kind.SPARKS);
        assertThat(particles.count).isGreaterThan(0);
        assertThat(transform.position).isEqualTo(new Vector3(3f, 1f, -2f));
    }

    /** Attrition ticks and propagated damage do not each throw sparks, or the screen strobes. */
    @Test
    void smallAndPropagatedHitsAreIgnored() {
        emitDamage(EffectSystem.MIN_SPARK_DAMAGE - 1f, new Vector3());
        emitPropagated(500f);
        world.present(0f, 1f / 60f);

        assertThat(system.bursts().isEmpty()).isTrue();
    }

    /** A burst carries a lifetime, so slot 16 reaps it rather than this system growing a reaper. */
    @Test
    void aBurstExpiresThroughItsLifetime() {
        emitDamage(50f, new Vector3());
        world.present(0f, 1f / 60f);

        int burst = system.bursts().snapshot()[0];
        LifetimeComponent lifetime = world.getComponent(burst, LifetimeComponent.class);
        ParticleRefComponent particles = world.getComponent(burst, ParticleRefComponent.class);

        assertThat(lifetime).isNotNull();
        assertThat(lifetime.remainingS).isEqualTo(particles.lifespanSeconds);
        assertThat(lifetime.despawnPolicy).isEqualTo(LifetimeComponent.DespawnPolicy.DESTROY);
    }

    /** Particles move, and their brightness falls to nothing by the end of the burst's life. */
    @Test
    void particlesMoveAndFade() {
        emitDamage(50f, new Vector3());
        world.present(0f, 1f / 60f);
        int burst = system.bursts().snapshot()[0];
        ParticleRefComponent particles = world.getComponent(burst, ParticleRefComponent.class);

        // Not exactly 1: a burst is integrated on the frame it is created, so it is already one
        // frame old the first time anything can look at it.
        assertThat(EffectSystem.fade(particles)).isGreaterThan(0.99f);
        for (int frame = 0; frame < 8; frame++) {
            world.present(0f, 1f / 60f);
        }
        assertThat(particles.offsetY[0]).isNotEqualTo(0f);
        assertThat(EffectSystem.fade(particles)).isLessThan(1f).isGreaterThan(0f);

        for (int frame = 0; frame < 120; frame++) {
            world.present(0f, 1f / 60f);
        }
        assertThat(EffectSystem.fade(particles)).isZero();
    }

    /** The burst budget bounds entity churn: a firefight cannot exhaust the entity range (D04-R10). */
    @Test
    void theBurstBudgetIsBounded() {
        for (int i = 0; i < EffectSystem.MAX_LIVE_BURSTS * 3; i++) {
            emitDamage(50f, new Vector3(i, 0f, 0f));
            world.present(0f, 1f / 60f);
        }
        assertThat(system.bursts().size()).isLessThanOrEqualTo(EffectSystem.MAX_LIVE_BURSTS);
    }

    private void emitDamage(float amount, Vector3 at) {
        world.events()
                .emit(new DamageEvent(
                        1,
                        1,
                        1,
                        DamageType.KINETIC,
                        amount,
                        at,
                        new Vector3(0f, 1f, 0f),
                        0L,
                        DamageEvent.NO_WEAPON_GROUP,
                        false,
                        0));
        world.events().dispatchQueued();
    }

    private void emitPropagated(float amount) {
        world.events()
                .emit(new DamageEvent(
                        1,
                        1,
                        1,
                        DamageType.KINETIC,
                        amount,
                        new Vector3(),
                        new Vector3(0f, 1f, 0f),
                        0L,
                        DamageEvent.NO_WEAPON_GROUP,
                        true,
                        1));
        world.events().dispatchQueued();
    }

    /** A schedule needs one non-PRESENT system for the world to consider itself tickable. */
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
