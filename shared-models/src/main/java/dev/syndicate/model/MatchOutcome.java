/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * How a match ended (docs/01_product_game_design.md#D01-S5.5).
 *
 * <p>The <em>kind</em> of result only. Who won is an entity id or a team id, and those live on
 * {@code MatchStateComponent} beside this — a shared model cannot name an entity, and an enum that
 * carried a winner would have to be recreated per match rather than compared by identity.
 *
 * <p>{@link #UNDECIDED} is the value during every phase before {@code ENDING}. It is not an error
 * state: D01-S5.5's evaluation returns {@code CONTINUE} for most of a match, and the outcome field
 * has to say something while that is true.
 */
public enum MatchOutcome {
    /** The match has not been decided; the win condition still returns {@code CONTINUE}. */
    UNDECIDED,

    /** One player won outright (`DEATHMATCH`, `LAST_MACHINE`). */
    PLAYER_WIN,

    /** One team won outright (`TEAM_DEATHMATCH`, `PAYLOAD`). */
    TEAM_WIN,

    /** Nobody won: tied at the limit with sudden death exhausted or disabled (D01-E2). */
    DRAW;

    /** True once the match has been decided one way or the other. */
    public boolean isDecided() {
        return this != UNDECIDED;
    }
}
