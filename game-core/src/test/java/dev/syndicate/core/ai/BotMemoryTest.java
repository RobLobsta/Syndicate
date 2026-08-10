/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * AC-D11-5: a bot does not track a target through a wall beyond {@code TARGET_MEMORY_S}
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.3, D11-R6).
 */
@Tag("unit")
class BotMemoryTest {

    private final BotMemory memory = new BotMemory();

    @Test
    void aSighting_isRecalledInsideTheWindow() {
        memory.remember(42, new Vector3(1f, 0f, 2f), new Vector3(0f, 0f, 10f), 0.8f, 1, 100L);

        BotMemory.Trace trace = memory.recall(42, 100L + BotMemory.TARGET_MEMORY_TICKS);

        assertThat(trace).isNotNull();
        assertThat(trace.position).isEqualTo(new Vector3(1f, 0f, 2f));
        assertThat(trace.integrity).isEqualTo(0.8f);
        assertThat(trace.teamId).isEqualTo(1);
    }

    @Test
    void pastTheWindow_theTargetIsForgotten() {
        memory.remember(42, new Vector3(), new Vector3(), 1f, 0, 100L);

        assertThat(memory.recall(42, 100L + BotMemory.TARGET_MEMORY_TICKS + 1)).isNull();
    }

    /** Stale traces are swept, so a long match does not accumulate one per vehicle ever seen. */
    @Test
    void forgetStale_dropsExpiredTraces() {
        memory.remember(1, new Vector3(), new Vector3(), 1f, 0, 0L);
        memory.remember(2, new Vector3(), new Vector3(), 1f, 0, 500L);

        memory.forgetStale(500L);

        assertThat(memory.traces()).containsOnlyKeys(2);
    }

    /** A second sighting replaces the first rather than accumulating. */
    @Test
    void rememberingAgain_replacesTheTrace() {
        memory.remember(7, new Vector3(1f, 0f, 0f), new Vector3(), 1f, 0, 10L);
        memory.remember(7, new Vector3(5f, 0f, 0f), new Vector3(), 0.5f, 0, 20L);

        BotMemory.Trace trace = memory.recall(7, 20L);

        assertThat(memory.traces()).hasSize(1);
        assertThat(trace.position.x).isEqualTo(5f);
        assertThat(trace.lastSeenTick).isEqualTo(20L);
    }

    /** Iteration is ascending by entity id, so two peers walk the same traces in the same order (G3). */
    @Test
    void traces_iterateInAscendingEntityOrder() {
        memory.remember(9, new Vector3(), new Vector3(), 1f, 0, 0L);
        memory.remember(3, new Vector3(), new Vector3(), 1f, 0, 0L);
        memory.remember(6, new Vector3(), new Vector3(), 1f, 0, 0L);

        assertThat(memory.traces().keySet()).containsExactly(3, 6, 9);
    }
}
