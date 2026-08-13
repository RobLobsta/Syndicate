/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.net.NetConstants;

/**
 * One peer's jitter buffer (docs/10_networking_multiplayer.md#D10-S5.3).
 *
 * <p>The authority holds a small window of a client's inputs so that jitter does not become missed
 * ticks. It deliberately runs the client a few ticks in the past: at a target delay of three ticks
 * a packet may arrive up to 50 ms late and still be applied on the tick it was meant for, which is
 * a far better trade than applying a stale input and correcting the client afterwards.
 *
 * <p>The delay adapts asymmetrically — <b>quick to grow, slow to shrink</b> (D10-S5.3). A player
 * whose connection deteriorates gets a longer buffer within two seconds; one whose connection has
 * been clean for ten seconds gets one tick back. The asymmetry is the point: growing late costs
 * dropped inputs, and shrinking early costs the same thing again.
 *
 * <p>Commands are stored in a ring indexed by {@code commandTick}, so a redundant copy of a command
 * that already arrived overwrites its own slot rather than appearing twice (D10-R4).
 */
public final class InputBuffer {

    /** Ticks of commands held. Two seconds is far more than any delay the adaptation reaches. */
    private static final int CAPACITY = 128;

    private final InputCommand[] commands = new InputCommand[CAPACITY];
    private final long[] commandTicks = new long[CAPACITY];

    /** Per-tick miss history, over the longer of the two adaptation windows. */
    private final boolean[] missHistory = new boolean[NetConstants.INPUT_CLEAN_WINDOW_TICKS];

    private int missesInCleanWindow;
    private int missesInMissWindow;
    private long historyTick = -1L;

    private int targetDelayTicks = NetConstants.INPUT_TARGET_DELAY_TICKS;
    private long newestCommandTick = -1L;

    public InputBuffer() {
        for (int i = 0; i < CAPACITY; i++) {
            commands[i] = new InputCommand();
            commandTicks[i] = -1L;
        }
    }

    /** The current adaptive delay, in ticks. */
    public int targetDelayTicks() {
        return targetDelayTicks;
    }

    /** The newest command tick stored, or -1. */
    public long newestCommandTick() {
        return newestCommandTick;
    }

    /**
     * Stores a command, keyed by its own tick.
     *
     * <p>A command for a tick already held is dropped rather than overwritten: the first copy to
     * arrive is the one that arrived earliest, and the redundant copies that follow it carry
     * identical content (D10-R4). Keeping the first also makes the buffer's contents independent of
     * how many duplicates the network chose to deliver.
     */
    public void accept(InputCommand command) {
        int index = (int) Math.floorMod(command.commandTick, (long) CAPACITY);
        if (commandTicks[index] == command.commandTick) {
            return;
        }
        commands[index].set(command);
        commandTicks[index] = command.commandTick;
        if (command.commandTick > newestCommandTick) {
            newestCommandTick = command.commandTick;
        }
    }

    /**
     * The command to apply on {@code serverTick}, or null when nothing suitable arrived.
     *
     * <p>Null is not a failure: D10-R15 says the caller repeats the previous movement input with the
     * fire mask zeroed. Repeating movement keeps a vehicle behaving plausibly through a dropped
     * packet; repeating fire would let a lagging client shoot without asking.
     */
    public InputCommand selectFor(long serverTick) {
        long wanted = serverTick - targetDelayTicks;
        InputCommand found = closestTo(wanted);
        recordOutcome(serverTick, found == null);
        adaptDelay();
        return found;
    }

    private InputCommand closestTo(long wanted) {
        for (int distance = 0; distance <= NetConstants.INPUT_SELECT_MAX_DISTANCE; distance++) {
            InputCommand earlier = at(wanted - distance);
            if (earlier != null) {
                return earlier;
            }
            if (distance > 0) {
                // Reaching forward as well as back is what lets the buffer serve a client whose
                // clock estimate is a tick fast without stalling until it corrects itself.
                InputCommand later = at(wanted + distance);
                if (later != null) {
                    return later;
                }
            }
        }
        return null;
    }

    private InputCommand at(long tick) {
        if (tick < 0L) {
            return null;
        }
        int index = (int) Math.floorMod(tick, (long) CAPACITY);
        return commandTicks[index] == tick ? commands[index] : null;
    }

    private void recordOutcome(long serverTick, boolean missed) {
        if (serverTick == historyTick) {
            return;
        }
        historyTick = serverTick;
        int window = NetConstants.INPUT_CLEAN_WINDOW_TICKS;
        int index = (int) Math.floorMod(serverTick, (long) window);
        if (missHistory[index]) {
            missesInCleanWindow--;
        }
        missHistory[index] = missed;
        if (missed) {
            missesInCleanWindow++;
        }
        missesInMissWindow = countMisses(serverTick, NetConstants.INPUT_MISS_WINDOW_TICKS);
    }

    private int countMisses(long serverTick, int windowTicks) {
        int window = NetConstants.INPUT_CLEAN_WINDOW_TICKS;
        int misses = 0;
        for (int i = 0; i < windowTicks; i++) {
            long tick = serverTick - i;
            if (tick < 0L) {
                break;
            }
            if (missHistory[(int) Math.floorMod(tick, (long) window)]) {
                misses++;
            }
        }
        return misses;
    }

    private void adaptDelay() {
        float missRate = missesInMissWindow / (float) NetConstants.INPUT_MISS_WINDOW_TICKS;
        if (missRate > NetConstants.INPUT_MISS_RATE_GROW) {
            targetDelayTicks = Math.min(targetDelayTicks + 1, NetConstants.INPUT_MAX_DELAY_TICKS);
        } else if (missesInCleanWindow == 0) {
            targetDelayTicks = Math.max(targetDelayTicks - 1, NetConstants.INPUT_MIN_DELAY_TICKS);
        }
    }

    /** Misses over the short adaptation window. For metrics and tests. */
    public int recentMisses() {
        return missesInMissWindow;
    }

    /** Empties the buffer without resetting the learned delay, as a respawn does. */
    public void clearCommands() {
        for (int i = 0; i < CAPACITY; i++) {
            commandTicks[i] = -1L;
        }
        newestCommandTick = -1L;
    }
}
