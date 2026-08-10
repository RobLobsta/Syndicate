/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.MatchPhase;

/**
 * Which phase the match is in, and what that phase permits
 * (docs/04_entity_component_model.md#D04-S4.3.5,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.7).
 *
 * <p>Lives on the match singleton, entity id {@link dev.syndicate.core.ecs.EntityId#MATCH}
 * (D04-R5).
 *
 * <p><b>The two gates are state, not a global.</b> D11-S5.7's pseudocode writes
 * {@code world.inputEnabled} and {@code world.damageEnabled}. {@link dev.syndicate.core.ecs.World}
 * deliberately knows nothing about matches, and a pair of process-wide flags would be exactly the
 * hidden simulation state D04-R3 exists to prevent — the offline simulator runs many matches in one
 * process (D11-S5.8). They are therefore replicated match state like the phase itself, and both
 * default to <b>open</b>: a world that never schedules {@code MatchFlowSystem} — every physics and
 * damage test in the suite — behaves as it did before the gates existed.
 */
public final class MatchStateComponent implements Component {

    /** The current phase. */
    public MatchPhase phase = MatchPhase.LOBBY;

    /** The tick {@link #phase} was entered; phase timeouts are measured from it. */
    public long phaseEnteredTick;

    /**
     * Whether driver intent reaches the vehicles this tick (D01-R21, D01-R23).
     *
     * <p>False during {@code COUNTDOWN}, so vehicles settle onto their suspension without being
     * driven, and during {@code ENDING} and {@code RESULTS}, so the final wreck plays out under
     * physics alone.
     */
    public boolean inputEnabled = true;

    /** Whether damage events are applied this tick. False until {@code ACTIVE} begins (D01-R22). */
    public boolean damageEnabled = true;

    /** How the match ended, or {@link MatchOutcome#UNDECIDED} while it has not. */
    public MatchOutcome outcome = MatchOutcome.UNDECIDED;

    /** The winning player entity when {@link #outcome} is {@code PLAYER_WIN}, else {@code NULL}. */
    public int winnerPlayerEntity = EntityId.NULL;

    /** The winning team when {@link #outcome} is {@code TEAM_WIN}, else {@link TeamComponent#FREE_FOR_ALL}. */
    public int winnerTeamId = TeamComponent.FREE_FOR_ALL;

    /**
     * Whether the match has already been extended into sudden death.
     *
     * <p>Latched rather than derived from the clock, because D01-E2 allows exactly one extension: a
     * second tie at the extended limit is a draw, and without this flag it would extend forever.
     */
    public boolean suddenDeath;

    @Override
    public void reset() {
        phase = MatchPhase.LOBBY;
        phaseEnteredTick = 0L;
        inputEnabled = true;
        damageEnabled = true;
        outcome = MatchOutcome.UNDECIDED;
        winnerPlayerEntity = EntityId.NULL;
        winnerTeamId = TeamComponent.FREE_FOR_ALL;
        suddenDeath = false;
    }
}
