/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;

/**
 * The authoritative tick counter (docs/04_entity_component_model.md#D04-S4.3.5).
 *
 * <p>This is the only clock the simulation has. G5 forbids reading wall-clock time in simulation
 * code, and the reason is that a replay, a headless batch run, and a live match must produce
 * identical results at different real-world speeds — which they can only do if "how much time has
 * passed" means "how many ticks have elapsed", each worth exactly {@code TICK_DT}.
 */
public final class MatchClockComponent implements Component {

    /** Ticks since the match began. */
    public long tick;

    /** The match's tick budget, or {@code 0} for no limit. */
    public int timeLimitTicks;

    @Override
    public void reset() {
        tick = 0L;
        timeLimitTicks = 0;
    }
}
