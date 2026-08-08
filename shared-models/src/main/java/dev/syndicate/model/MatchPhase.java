/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * Match lifecycle phases (docs/11_ai_bots_and_match_simulation.md#D11-S5.7).
 *
 * <p>Held by {@code MatchStateComponent} and replicated (D04-S4.3.5).
 */
public enum MatchPhase {
    LOBBY,
    COUNTDOWN,
    ACTIVE,
    ENDING,
    RESULTS;
}
