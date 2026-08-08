/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The content-level game modes of docs/01_product_game_design.md#D01-S4.2.
 *
 * <p>Distinct from {@link RuntimeMode}: a game mode is what the match's rules are, a runtime mode is
 * what the process is. Every game mode runs inside any of the four runtime modes (D03-R1).
 */
public enum GameMode {

    /** Free-for-all. First to {@code scoreLimit} kills, or highest at the time limit. */
    DEATHMATCH,

    /** Two teams. Team reaches {@code scoreLimit}, or is ahead at the time limit. */
    TEAM_DEATHMATCH,

    /** No respawn. The mode where degradation is the whole game (D01-R5). */
    LAST_MACHINE,

    /** Attackers move a payload to the goal; defenders hold until time. */
    PAYLOAD,

    /** Single player, complete the course. No win condition against opponents. */
    TIME_TRIAL,

    /** Development mode with no win condition, for inspecting parts under damage (D01-R5). */
    TEST_RANGE;
}
