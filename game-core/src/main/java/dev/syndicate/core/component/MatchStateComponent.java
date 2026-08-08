/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.MatchPhase;

/**
 * Which phase the match is in (docs/04_entity_component_model.md#D04-S4.3.5,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.7).
 *
 * <p>Lives on the match singleton, entity id {@link dev.syndicate.core.ecs.EntityId#MATCH}
 * (D04-R5).
 */
public final class MatchStateComponent implements Component {

    /** The current phase. */
    public MatchPhase phase = MatchPhase.LOBBY;

    /** The tick {@link #phase} was entered; phase timeouts are measured from it. */
    public long phaseEnteredTick;

    @Override
    public void reset() {
        phase = MatchPhase.LOBBY;
        phaseEnteredTick = 0L;
    }
}
