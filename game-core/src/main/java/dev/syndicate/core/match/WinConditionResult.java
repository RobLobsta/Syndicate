/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.ecs.EntityId;

/**
 * What {@link WinCondition#evaluate} decided this tick (docs/01_product_game_design.md#D01-S5.5).
 *
 * <p>The four outcomes of D01-S5.5's pseudocode, carrying whichever winner applies. A record rather
 * than an enum because {@code WIN(leader)} names a leader, and rebuilding "who won" from the
 * scoreboard afterwards would ask the same tie-break question a second time and could answer it
 * differently.
 */
public record WinConditionResult(Kind kind, int winnerPlayerEntity, int winnerTeamId) {

    /** The four returns of D01-S5.5. */
    public enum Kind {
        /** No decision this tick. */
        CONTINUE,
        /** A player or a team has won. */
        WIN,
        /** Tied at the limit with no extension left (D01-E2). */
        DRAW,
        /** Tied at the limit and {@code suddenDeathTicks > 0}: extend once, no respawns. */
        ENTER_SUDDEN_DEATH
    }

    /** The singleton "nothing happened" result, so the common path allocates nothing. */
    public static final WinConditionResult CONTINUE =
            new WinConditionResult(Kind.CONTINUE, EntityId.NULL, TeamComponent.FREE_FOR_ALL);

    /** Tied at the limit; nobody wins. */
    public static final WinConditionResult DRAW =
            new WinConditionResult(Kind.DRAW, EntityId.NULL, TeamComponent.FREE_FOR_ALL);

    /** Extend the clock once and stop respawning. */
    public static final WinConditionResult SUDDEN_DEATH =
            new WinConditionResult(Kind.ENTER_SUDDEN_DEATH, EntityId.NULL, TeamComponent.FREE_FOR_ALL);

    /** A win by one player. */
    public static WinConditionResult playerWin(int playerEntity) {
        return new WinConditionResult(Kind.WIN, playerEntity, TeamComponent.FREE_FOR_ALL);
    }

    /** A win by one team. */
    public static WinConditionResult teamWin(int teamId) {
        return new WinConditionResult(Kind.WIN, EntityId.NULL, teamId);
    }

    /** True when the match is over, whichever way. */
    public boolean isTerminal() {
        return kind == Kind.WIN || kind == Kind.DRAW;
    }
}
