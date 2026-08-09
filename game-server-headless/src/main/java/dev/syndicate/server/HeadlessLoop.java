/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import dev.syndicate.core.ecs.World;
import dev.syndicate.model.SimulationConstants;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dedicated server's tick loop (docs/03_runtime_modes.md#D03-S5.4).
 *
 * <p>It advances the world in fixed {@code TICK_DT} steps and paces itself against the wall clock,
 * which is the one place in the process where wall-clock time is allowed to be read. G5 forbids a
 * <em>simulation system</em> from reading {@code nanoTime}, and D06-R27 names the loop's pacing and
 * profiling timers as the exception: the clock decides <em>when</em> a tick happens, never how long
 * one is. {@link World#tick} is called with exactly {@code TICK_DT} or not at all (G2), so a
 * server under load produces the same results as an idle one, just later.
 *
 * <p><b>Overload is handled by skipping, not by spiralling</b> (D03-S5.4). A server that falls more
 * than {@link #OVERLOAD_TICKS} behind resets its clock to now instead of trying to catch up: clients
 * see a lag spike, which is recoverable, whereas a catch-up loop that cannot keep up runs slower the
 * further behind it gets and never recovers.
 *
 * <p>Systems 22-26 are absent from a {@code DEDICATED_SERVER} schedule (D03-S5.2), so this loop
 * never renders and never creates a GL context — structurally, not by intention (D03-R12, G17). It
 * calls {@link World#tick} and never {@code World.present}.
 */
public final class HeadlessLoop {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlessLoop.class);

    /** Ticks behind the wall clock at which the loop resyncs rather than catching up (D03-S5.4). */
    public static final int OVERLOAD_TICKS = 30;

    /**
     * Nanoseconds. Below this the loop spins instead of sleeping.
     *
     * <p>{@code Thread.sleep} rounds up to the scheduler's granularity, which on a loaded host is
     * comfortably more than a 16.7 ms tick — sleeping the whole remainder therefore overshoots every
     * tick and the server runs slow. Sleeping the bulk and spinning the last two milliseconds costs
     * a little CPU and holds the tick rate.
     */
    public static final long SPIN_THRESHOLD_NANOS = 2_000_000L;

    private final World world;
    private final Runnable betweenTicks;
    private final long tickLimit;

    private final AtomicBoolean running = new AtomicBoolean();

    private long tick;
    private long overloadCount;
    private long longestTickNanos;
    private long totalTickNanos;

    /** A loop that runs until {@link #requestStop()}. */
    public HeadlessLoop(World world) {
        this(world, () -> {}, 0L);
    }

    /**
     * @param betweenTicks run once per tick after {@link World#tick}, where D03-S5.4 pumps transports
     *     and drains the admin console. Neither exists yet, so the default does nothing; passing it
     *     in rather than referencing them keeps this class free of subsystems it would only be able
     *     to call null on.
     * @param tickLimit stop after this many ticks, or {@code 0} to run until asked to stop. A bound
     *     is what lets a test assert the loop's pacing without a watchdog thread deciding when the
     *     server has run long enough.
     */
    public HeadlessLoop(World world, Runnable betweenTicks, long tickLimit) {
        this.world = Objects.requireNonNull(world, "world");
        this.betweenTicks = Objects.requireNonNull(betweenTicks, "betweenTicks");
        this.tickLimit = tickLimit;
    }

    /**
     * Runs until stopped or until the tick limit is reached.
     *
     * <p>Blocks the calling thread. The dedicated server calls it from {@code main}, so the process
     * lives exactly as long as the simulation does.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("loop is already running");
        }
        LOG.info(
                "headless loop starting at {} Hz{}",
                SimulationConstants.TICK_RATE_HZ,
                tickLimit > 0 ? " for " + tickLimit + " ticks" : "");

        long nextTickNanos = System.nanoTime();
        while (running.get() && (tickLimit <= 0 || tick < tickLimit)) {
            long now = System.nanoTime();
            if (now < nextTickNanos) {
                sleepUntil(nextTickNanos);
                continue;
            }

            long startedNanos = System.nanoTime();
            world.tick(tick);
            tick++;
            nextTickNanos += SimulationConstants.TICK_DT_NANOS;
            betweenTicks.run();
            recordTickDuration(System.nanoTime() - startedNanos);

            long behind = (System.nanoTime() - nextTickNanos) / SimulationConstants.TICK_DT_NANOS;
            if (behind > OVERLOAD_TICKS) {
                LOG.error("server overloaded: {} ticks behind, resyncing clock (D03-S5.4)", behind);
                overloadCount++;
                nextTickNanos = System.nanoTime() + SimulationConstants.TICK_DT_NANOS;
            }
        }
        running.set(false);
        LOG.info(
                "headless loop stopped after {} ticks; mean {} µs, longest {} µs, {} overloads",
                tick,
                tick == 0 ? 0 : totalTickNanos / tick / 1000,
                longestTickNanos / 1000,
                overloadCount);
    }

    /** Asks the loop to stop after the tick in progress. Safe from any thread — a shutdown hook's. */
    public void requestStop() {
        running.set(false);
    }

    /** True while {@link #start()} is executing. */
    public boolean isRunning() {
        return running.get();
    }

    /** How many ticks have been executed. */
    public long tickCount() {
        return tick;
    }

    /** How many times the loop resynced its clock after falling behind (D03-S5.4). */
    public long overloadCount() {
        return overloadCount;
    }

    /** The longest single tick, in nanoseconds. The figure D12-S5.5's budget is measured against. */
    public long longestTickNanos() {
        return longestTickNanos;
    }

    private void recordTickDuration(long durationNanos) {
        totalTickNanos += durationNanos;
        longestTickNanos = Math.max(longestTickNanos, durationNanos);
    }

    /** Coarse sleep for the bulk of the wait, then a short spin for accuracy (D03-S5.4). */
    private void sleepUntil(long targetNanos) {
        long remaining = targetNanos - System.nanoTime();
        if (remaining > SPIN_THRESHOLD_NANOS) {
            try {
                Thread.sleep((remaining - SPIN_THRESHOLD_NANOS) / 1_000_000L);
            } catch (InterruptedException e) {
                // A server is stopped by its shutdown hook, not by an interrupt, so this can only be
                // something else asking the thread to unwind. Restore the flag and let the loop's
                // own condition end it rather than swallowing the request.
                Thread.currentThread().interrupt();
                requestStop();
            }
            return;
        }
        Thread.onSpinWait();
    }
}
