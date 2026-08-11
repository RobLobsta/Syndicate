/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import dev.syndicate.model.SimulationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fixed-timestep client loop of docs/03_runtime_modes.md#D03-S5.3.
 *
 * <p>Frame time decides <b>how many</b> fixed steps happen and never <b>how long</b> one is
 * (D03-R10, G2). That single rule is what makes a 30 Hz laptop and a 144 Hz desktop produce the same
 * match, and what will make a client and a server agree once there is a wire between them. Every
 * other line here exists to protect it:
 *
 * <ul>
 *   <li>the frame delta is clamped to {@link #MAX_FRAME_DT_S} before it enters the accumulator, so a
 *       stall — a window drag, a garbage collection, a laptop lid — does not become a burst of
 *       hundreds of ticks;
 *   <li>catch-up is capped at {@link #MAX_CATCHUP_TICKS}, and past it the remaining debt is
 *       <em>dropped</em> rather than stretched into a longer step. Dropping time is visible and
 *       recoverable; lengthening a step silently changes physics results;
 *   <li>what is left in the accumulator becomes {@code alpha}, which only PRESENT systems see.
 * </ul>
 *
 * <p>This class holds no reference to a window or a GL context. It is driven by whatever calls
 * {@link #advance}, which is what lets a headless capture run the identical loop.
 */
public final class ClientLoop {

    private static final Logger LOG = LoggerFactory.getLogger(ClientLoop.class);

    /** Seconds. The largest frame delta accepted — 15 ticks (D03-S5.3). */
    public static final float MAX_FRAME_DT_S = 0.25f;

    /** The most simulation steps one frame may run (D03-S5.3). */
    public static final int MAX_CATCHUP_TICKS = 15;

    /**
     * Seconds of slack when deciding whether the accumulator holds a whole tick.
     *
     * <p>{@code TICK_DT} is a float and a frame delta is a float, so a display running at exactly the
     * tick rate delivers frames whose accumulated total lands a few nanoseconds either side of a
     * whole tick — and without the slack the loop alternates between running one tick and running
     * two, which is a visible 60 Hz judder produced entirely by rounding. Seventeen microseconds is
     * a thousandth of a tick: far larger than the error, far smaller than anything that could shift
     * a step to the wrong side of a real boundary.
     */
    public static final double TICK_EPSILON_S = SimulationConstants.TICK_DT * 1e-3;

    private double accumulator;
    private long tick;
    private long droppedTicks;

    /** What one call to {@link #advance} did, so a caller can log or assert on it. */
    public record Step(int ticksRun, float alpha, int ticksDropped) {}

    /**
     * Runs the fixed steps this frame has earned, then the PRESENT systems once.
     *
     * @param world the world to advance
     * @param frameDeltaSeconds real elapsed time since the last frame
     */
    public Step advance(dev.syndicate.core.ecs.World world, float frameDeltaSeconds) {
        double dt = Math.min(Math.max(frameDeltaSeconds, 0f), MAX_FRAME_DT_S);
        accumulator += dt;

        int steps = 0;
        while (accumulator + TICK_EPSILON_S >= SimulationConstants.TICK_DT && steps < MAX_CATCHUP_TICKS) {
            world.tick(tick);
            accumulator -= SimulationConstants.TICK_DT;
            tick++;
            steps++;
        }

        // Defensive rather than reachable as the constants stand: 0.25 s of clamped frame time is
        // exactly the 15 ticks the cap allows, so a frame cannot bank more debt than it may work
        // off. Moving either constant makes this branch live, which is precisely when it is needed.
        int dropped = 0;
        if (accumulator >= SimulationConstants.TICK_DT) {
            dropped = (int) (accumulator / SimulationConstants.TICK_DT);
            LOG.warn("dropping {} ticks of simulation debt", dropped);
            droppedTicks += dropped;
            // Modulo, not zero: the sub-tick remainder is the frame's honest position between two
            // ticks, and discarding it would make alpha jump on exactly the frames already stuttering.
            accumulator %= SimulationConstants.TICK_DT;
        }

        float alpha = (float) (accumulator / SimulationConstants.TICK_DT);
        world.present(alpha, (float) dt);
        return new Step(steps, alpha, dropped);
    }

    /** The next tick number the loop will run. */
    public long tick() {
        return tick;
    }

    /** How many ticks have been dropped to overload since the loop started. */
    public long droppedTicks() {
        return droppedTicks;
    }
}
