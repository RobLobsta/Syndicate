/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The dedicated server's tick loop (docs/03_runtime_modes.md#D03-S5.4). */
@Tag("integration")
class HeadlessLoopTest {

    /** A tick limit runs exactly that many ticks, and every tick number is consecutive from zero. */
    @Test
    @Timeout(30)
    void aBoundedRunTicksExactlyItsLimit() {
        World world = new World(1L, true);
        TickRecorder recorder = new TickRecorder();
        world.registerSystems(List.<EntitySystem>of(recorder));

        HeadlessLoop loop = new HeadlessLoop(world, () -> {}, 10L);
        loop.start();

        assertThat(loop.tickCount()).isEqualTo(10L);
        assertThat(recorder.ticks).isEqualTo(10);
        assertThat(recorder.lastTick).isEqualTo(9L);
        assertThat(loop.isRunning()).isFalse();
    }

    /** G2: the loop calls {@code tick} with exactly {@code TICK_DT}, never with a frame time. */
    @Test
    @Timeout(30)
    void everyStepIsExactlyTickDt() {
        World world = new World(1L, true);
        TickRecorder recorder = new TickRecorder();
        world.registerSystems(List.<EntitySystem>of(recorder));

        new HeadlessLoop(world, () -> {}, 5L).start();

        assertThat(recorder.everyDtWasTickDt).isTrue();
    }

    /** D03-S5.4: the loop paces itself against the wall clock rather than spinning free. */
    @Test
    @Timeout(30)
    void theLoopRunsAtTheTickRate() {
        World world = new World(1L, true);
        world.registerSystems(List.<EntitySystem>of(new TickRecorder()));

        long startedNanos = System.nanoTime();
        new HeadlessLoop(world, () -> {}, 30L).start();
        long elapsedNanos = System.nanoTime() - startedNanos;

        long expectedNanos = 30L * SimulationConstants.TICK_DT_NANOS;
        // Half a second of slack: a shared CI runner can lose a scheduling slice, and this asserts
        // that the loop sleeps at all rather than that it is a real-time system.
        assertThat(elapsedNanos).isGreaterThan(expectedNanos / 2).isLessThan(expectedNanos + 500_000_000L);
    }

    /** The between-ticks hook — transports and the admin console, when they exist — runs per tick. */
    @Test
    @Timeout(30)
    void theBetweenTicksHookRunsOncePerTick() {
        World world = new World(1L, true);
        world.registerSystems(List.<EntitySystem>of(new TickRecorder()));
        AtomicLong calls = new AtomicLong();

        new HeadlessLoop(world, calls::incrementAndGet, 8L).start();

        assertThat(calls.get()).isEqualTo(8L);
    }

    /** A loop with no tick limit and a hook that stops it terminates on that hook. */
    @Test
    @Timeout(30)
    void anUnboundedLoopStopsWhenAsked() {
        World world = new World(1L, true);
        world.registerSystems(List.<EntitySystem>of(new TickRecorder()));

        HeadlessLoop[] holder = new HeadlessLoop[1];
        holder[0] = new HeadlessLoop(
                world,
                () -> {
                    if (holder[0].tickCount() >= 5L) {
                        holder[0].requestStop();
                    }
                },
                0L);
        holder[0].start();

        assertThat(holder[0].tickCount()).isEqualTo(5L);
    }

    /** A system that only records what the loop handed it. */
    private static final class TickRecorder implements EntitySystem {

        private int ticks;
        private long lastTick = -1L;
        private boolean everyDtWasTickDt = true;

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
            lastTick = tick;
            everyDtWasTickDt &= dtSeconds == SimulationConstants.TICK_DT;
        }
    }

    /** {@code null} hooks are refused at construction rather than at the first tick. */
    @Test
    void aNullHookIsRefused() {
        World world = new World(1L, true);
        assertThatThrownBy(() -> new HeadlessLoop(world, null, 1L)).isInstanceOf(NullPointerException.class);
    }
}
