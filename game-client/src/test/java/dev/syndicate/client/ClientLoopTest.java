/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The client loop's fixed timestep (docs/03_runtime_modes.md#D03-S5.3, D03-R10, G2).
 *
 * <p>These are the tests that decide whether frame rate can affect a simulation result. Each one
 * corresponds to a line of D03-S5.3's pseudocode, because every one of those lines exists to stop a
 * specific way of accidentally making the step variable.
 */
@Tag("unit")
class ClientLoopTest {

    /** A frame shorter than a tick runs nothing and simply banks the time. */
    @Test
    void aShortFrameRunsNoTicks() {
        Counter counter = new Counter();
        World world = worldWith(counter);
        ClientLoop loop = new ClientLoop();

        ClientLoop.Step step = loop.advance(world, SimulationConstants.TICK_DT / 4f);

        assertThat(step.ticksRun()).isZero();
        assertThat(counter.ticks).isZero();
        assertThat(counter.frames).isOne();
        assertThat(step.alpha()).isEqualTo(0.25f, offset(1e-5f));
    }

    /** Four frames of a quarter tick each add up to exactly one tick, never to none and never two. */
    @Test
    void bankedTimeAccumulatesIntoWholeTicks() {
        Counter counter = new Counter();
        World world = worldWith(counter);
        ClientLoop loop = new ClientLoop();

        for (int i = 0; i < 4; i++) {
            loop.advance(world, SimulationConstants.TICK_DT / 4f);
        }

        assertThat(counter.ticks).isOne();
        assertThat(counter.frames).isEqualTo(4);
    }

    /** A long frame runs many fixed steps; it never runs one long step (D03-R10). */
    @Test
    void aLongFrameRunsManyFixedSteps() {
        Counter counter = new Counter();
        World world = worldWith(counter);
        ClientLoop loop = new ClientLoop();

        loop.advance(world, SimulationConstants.TICK_DT * 5f);

        assertThat(counter.ticks).isEqualTo(5);
        assertThat(counter.tickDeltas).allMatch(dt -> dt == SimulationConstants.TICK_DT);
    }

    /**
     * A stall is clamped, then capped, and the remaining debt is dropped rather than stretched.
     *
     * <p>Ten seconds of frame time is 600 ticks. {@link ClientLoop#MAX_FRAME_DT_S} clamps it to 15,
     * and {@link ClientLoop#MAX_CATCHUP_TICKS} runs exactly those — so a lid-close does not become a
     * ten-second burst of simulation the player never sees.
     */
    @Test
    void aStallIsClampedAndCapped() {
        Counter counter = new Counter();
        World world = worldWith(counter);
        ClientLoop loop = new ClientLoop();

        ClientLoop.Step step = loop.advance(world, 10f);

        assertThat(counter.ticks).isEqualTo(ClientLoop.MAX_CATCHUP_TICKS);
        assertThat(step.ticksDropped()).isZero();
        assertThat(loop.tick()).isEqualTo(ClientLoop.MAX_CATCHUP_TICKS);
    }

    /**
     * However long the stall, no step is ever longer than {@code TICK_DT} and none is dropped.
     *
     * <p>D03-S5.3's two limits coincide: {@link ClientLoop#MAX_FRAME_DT_S} is 0.25 s and
     * {@link ClientLoop#MAX_CATCHUP_TICKS} is 15, which at 60 Hz is the same 0.25 s. A frame can
     * therefore never bank more debt than the same frame is allowed to work off, and the
     * debt-dropping branch is defensive rather than reachable — it becomes live the moment either
     * constant moves, which is exactly when someone needs it. What this asserts is the property that
     * has to hold either way: after the worst stall the machine can produce, every step that ran was
     * a fixed one (G2).
     */
    @Test
    void noStallProducesAVariableStep() {
        Counter counter = new Counter();
        World world = worldWith(counter);
        ClientLoop loop = new ClientLoop();

        for (int frame = 0; frame < 5; frame++) {
            ClientLoop.Step step = loop.advance(world, 30f);
            assertThat(step.ticksRun()).isEqualTo(ClientLoop.MAX_CATCHUP_TICKS);
            assertThat(step.ticksDropped()).isZero();
        }

        assertThat(counter.ticks).isEqualTo(ClientLoop.MAX_CATCHUP_TICKS * 5);
        assertThat(loop.droppedTicks()).isZero();
        assertThat(counter.tickDeltas).allMatch(dt -> dt == SimulationConstants.TICK_DT);
    }

    /** PRESENT systems see real frame time, not the fixed step (D07-S5.5's ease depends on it). */
    @Test
    void presentSystemsSeeFrameTimeAndAlpha() {
        Counter counter = new Counter();
        World world = worldWith(counter);
        ClientLoop loop = new ClientLoop();

        loop.advance(world, SimulationConstants.TICK_DT * 1.5f);

        assertThat(counter.frameDeltas).hasSize(1);
        assertThat(counter.frameDeltas.get(0)).isEqualTo(SimulationConstants.TICK_DT * 1.5f, offset(1e-6f));
        assertThat(counter.alphas.get(0)).isEqualTo(0.5f, offset(1e-3f));
    }

    private static World worldWith(Counter counter) {
        World world = new World(1337L, true);
        world.registerSystems(List.of(counter.simulation(), counter.presentation()));
        return world;
    }

    /** Records what each half of the schedule was handed. */
    private static final class Counter {

        private int ticks;
        private int frames;
        private final List<Float> tickDeltas = new ArrayList<>();
        private final List<Float> frameDeltas = new ArrayList<>();
        private final List<Float> alphas = new ArrayList<>();

        EntitySystem simulation() {
            return new EntitySystem() {
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
                    ticks++;
                    tickDeltas.add(dtSeconds);
                }
            };
        }

        EntitySystem presentation() {
            return new EntitySystem() {
                @Override
                public Phase phase() {
                    return Phase.PRESENT;
                }

                @Override
                public int order() {
                    return 26;
                }

                @Override
                public void update(World world, float dtSeconds, long tick) {
                    frames++;
                    frameDeltas.add(dtSeconds);
                    alphas.add(world.renderAlpha());
                }
            };
        }
    }
}
