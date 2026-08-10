/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import dev.syndicate.model.BotDifficulty;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The four rows of D11-S4.2, keyed by level
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.2, D11-R4).
 *
 * <p>D11-R4 puts these numbers in {@code assets/balance/bot_difficulty.json} so that tuning a bot is
 * content work. {@link #defaults()} holds the same numbers in code, and that duplication is
 * deliberate rather than sloppy: a difficulty file that fails to load must not leave every bot with
 * zero reaction delay and perfect aim, which is what an empty table would mean. The shipped file is
 * asserted against these defaults by a test, so the two cannot drift without something going red.
 *
 * <p>An {@code EnumMap} so iteration is in declaration order, which is ascending difficulty (G3).
 */
public final class BotDifficultyTable {

    private final Map<BotDifficulty, BotDifficultyParams> rows;

    private BotDifficultyTable(Map<BotDifficulty, BotDifficultyParams> rows) {
        this.rows = rows;
    }

    /** Builds a table from a complete set of rows. */
    public static BotDifficultyTable of(Map<BotDifficulty, BotDifficultyParams> rows) {
        EnumMap<BotDifficulty, BotDifficultyParams> copy = new EnumMap<>(BotDifficulty.class);
        copy.putAll(rows);
        for (BotDifficulty level : BotDifficulty.values()) {
            copy.putIfAbsent(level, defaults().get(level));
        }
        return new BotDifficultyTable(copy);
    }

    /** The D11-S4.2 table exactly as published. */
    public static BotDifficultyTable defaults() {
        EnumMap<BotDifficulty, BotDifficultyParams> rows = new EnumMap<>(BotDifficulty.class);
        rows.put(
                BotDifficulty.EASY,
                new BotDifficultyParams(
                        0.60f, 4, 0.070f, 1.2f, 0.30f, 0.55f, 0.5f, 4.0f, false, 0.15f, 1.30f, false, false));
        rows.put(
                BotDifficulty.NORMAL,
                new BotDifficultyParams(
                        0.35f, 8, 0.035f, 2.2f, 0.65f, 0.75f, 0.9f, 2.5f, false, 0.30f, 1.10f, true, false));
        rows.put(
                BotDifficulty.HARD,
                new BotDifficultyParams(
                        0.18f, 15, 0.015f, 3.5f, 0.90f, 0.90f, 1.4f, 1.2f, true, 0.35f, 1.00f, true, true));
        rows.put(
                BotDifficulty.BRUTAL,
                new BotDifficultyParams(
                        0.08f, 30, 0.005f, 5.0f, 1.00f, 1.00f, 1.8f, 0.6f, true, 0.40f, 0.95f, true, true));
        return new BotDifficultyTable(rows);
    }

    /** The parameters for a level, never null. */
    public BotDifficultyParams get(BotDifficulty difficulty) {
        BotDifficultyParams params = rows.get(difficulty == null ? BotDifficulty.NORMAL : difficulty);
        return params == null ? rows.get(BotDifficulty.NORMAL) : params;
    }

    /** Every row, in ascending difficulty. Unmodifiable rather than copied, to keep the enum order. */
    public Map<BotDifficulty, BotDifficultyParams> rows() {
        return Collections.unmodifiableMap(rows);
    }
}
