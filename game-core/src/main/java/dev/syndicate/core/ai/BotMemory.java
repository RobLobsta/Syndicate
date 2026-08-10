/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.SimulationConstants;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a bot remembers about targets it can no longer see
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.3, D11-R6).
 *
 * <p>Memory is the difference between a bot that loses you the instant you break line of sight —
 * which reads as an exploit — and one that tracks you through a wall forever, which reads as a
 * cheat. D11-R6 puts the line at {@link #TARGET_MEMORY_S}: within it, the last known position is
 * extrapolated by the last known velocity; past it, the target is forgotten entirely.
 *
 * <p>A {@code TreeMap} keyed on entity id, so iteration is ascending and identical on every peer
 * (G3), and so the forget sweep visits entries in a fixed order.
 */
public final class BotMemory {

    /** How long a target lost from sight is remembered (D11-R6). */
    public static final float TARGET_MEMORY_S = 3.0f;

    /** {@link #TARGET_MEMORY_S} in ticks. */
    public static final int TARGET_MEMORY_TICKS = Math.round(TARGET_MEMORY_S * SimulationConstants.TICK_RATE_HZ);

    /** One remembered target. Mutable and reused, so remembering allocates nothing after the first. */
    public static final class Trace {
        /** Where it was when last seen. */
        public final Vector3 position = new Vector3();

        /** How fast it was going when last seen; dead reckoning extrapolates along this. */
        public final Vector3 velocity = new Vector3();

        /** Its integrity when last seen. */
        public float integrity;

        /** Its team when last seen. */
        public int teamId;

        /** The tick it was last seen on. */
        public long lastSeenTick;
    }

    private final Map<Integer, Trace> traces = new TreeMap<>();

    /** Records a sighting, replacing any earlier one for that entity. */
    public void remember(int entity, Vector3 position, Vector3 velocity, float integrity, int teamId, long tick) {
        Trace trace = traces.computeIfAbsent(entity, ignored -> new Trace());
        trace.position.set(position);
        trace.velocity.set(velocity);
        trace.integrity = integrity;
        trace.teamId = teamId;
        trace.lastSeenTick = tick;
    }

    /** The trace for an entity if it is still within {@link #TARGET_MEMORY_TICKS}, else null. */
    public Trace recall(int entity, long tick) {
        Trace trace = traces.get(entity);
        if (trace == null || tick - trace.lastSeenTick > TARGET_MEMORY_TICKS) {
            return null;
        }
        return trace;
    }

    /** Every trace still within the memory window, ascending by entity id. */
    public Map<Integer, Trace> traces() {
        return traces;
    }

    /**
     * Drops everything older than the memory window.
     *
     * <p>Run once per sensor refresh rather than per lookup: a bot in a long match would otherwise
     * accumulate a trace per vehicle it has ever seen, and the map would grow without bound in a
     * mode where players respawn with new entity ids.
     */
    public void forgetStale(long tick) {
        traces.entrySet().removeIf(entry -> tick - entry.getValue().lastSeenTick > TARGET_MEMORY_TICKS);
    }

    /** Forgets everything. Called when a bot's vehicle is replaced. */
    public void clear() {
        traces.clear();
    }
}
