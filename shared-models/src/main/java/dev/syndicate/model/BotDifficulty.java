/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * Bot difficulty levels (docs/11_ai_bots_and_match_simulation.md#D11-S4.2).
 *
 * <p>The enum carries no numbers. Every difference between levels is a perception or execution
 * parameter, and those live in {@code assets/balance/bot_difficulty.json} so tuning is content
 * rather than code (D11-R4). A bot never receives a damage, health, or speed bonus (D11-R6).
 */
public enum BotDifficulty {
    EASY,
    NORMAL,
    HARD,
    BRUTAL;
}
