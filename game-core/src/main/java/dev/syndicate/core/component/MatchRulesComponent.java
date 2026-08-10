/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.SimulationConstants;

/**
 * The rules the match is being played under
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/01_product_game_design.md#D01-S4.2,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.7).
 *
 * <p>A component on the match singleton rather than a static configuration object, because the
 * offline match simulator of D11-S5.8 runs many worlds in one process with different rules — and
 * because rules are replicated, so a joining client learns them the same way it learns everything
 * else.
 *
 * <p><b>Phase durations are rules, not constants.</b> D11-S5.7 writes {@code ENDING_TICKS (300)}
 * and {@code RESULTS_TICKS (900)} as literals, and D01-S5.6 restates them. They are fields here for
 * the reason the whole component exists: a headless smoke test that had to sit through twenty
 * seconds of scoreboard per match would make the 500-match sweep of D11-R14 twenty minutes longer
 * for no information. The defaults are the specified values.
 */
public final class MatchRulesComponent implements Component {

    /** {@code ENDING_TICKS} — five seconds of wreckage after the win condition fires (D11-S5.7). */
    public static final int DEFAULT_ENDING_TICKS = 300;

    /** {@code RESULTS_TICKS} — fifteen seconds of scoreboard (D11-S5.7). */
    public static final int DEFAULT_RESULTS_TICKS = 900;

    /** Three seconds of countdown, during which vehicles settle and nothing is driven (D01-R21). */
    public static final int DEFAULT_WARMUP_TICKS = 3 * SimulationConstants.TICK_RATE_HZ;

    /** Five seconds between a death and its respawn. */
    public static final int DEFAULT_RESPAWN_DELAY_TICKS = 5 * SimulationConstants.TICK_RATE_HZ;

    /** Which mode is being played. */
    public GameMode mode = GameMode.DEATHMATCH;

    /**
     * Which arena the match is fought in (D11-S5.7 {@code loadArena}).
     *
     * <p>Here rather than only in {@code LaunchConfig} because the spawn points a respawn chooses
     * from are the arena's, and slot 4 has a match singleton but not a launch configuration.
     */
    public AssetId arenaId;

    /** Score at which the match ends, or {@code 0} for no score limit. */
    public int scoreLimit;

    /** Ticks between a player's death and their respawn. */
    public int respawnDelayTicks = DEFAULT_RESPAWN_DELAY_TICKS;

    /** Whether damage to teammates counts. Always false in free-for-all modes. */
    public boolean friendlyFire;

    /** When true a dead player never returns, whatever {@link #respawnDelayTicks} says (D11-S5.7). */
    public boolean noRespawn;

    /** Ticks the {@code COUNTDOWN} phase lasts. */
    public int warmupTicks = DEFAULT_WARMUP_TICKS;

    /** Ticks the {@code ENDING} phase lasts. */
    public int endingTicks = DEFAULT_ENDING_TICKS;

    /** Ticks the {@code RESULTS} phase lasts. */
    public int resultsTicks = DEFAULT_RESULTS_TICKS;

    /** Ticks the clock is extended by on a tie, or {@code 0} to declare a draw instead (D01-E2). */
    public int suddenDeathTicks;

    /** How many bots {@code MatchFlowSystem} fills the lobby with (D11-S5.6). */
    public int botCount;

    /** Which difficulty those bots are created at (D11-S4.2). */
    public BotDifficulty botDifficulty = BotDifficulty.NORMAL;

    /** Whether the lobby starts without waiting for a human to be ready (D03-S4.2 {@code autoStart}). */
    public boolean autoStart;

    @Override
    public void reset() {
        mode = GameMode.DEATHMATCH;
        arenaId = null;
        scoreLimit = 0;
        respawnDelayTicks = DEFAULT_RESPAWN_DELAY_TICKS;
        friendlyFire = false;
        noRespawn = false;
        warmupTicks = DEFAULT_WARMUP_TICKS;
        endingTicks = DEFAULT_ENDING_TICKS;
        resultsTicks = DEFAULT_RESULTS_TICKS;
        suddenDeathTicks = 0;
        botCount = 0;
        botDifficulty = BotDifficulty.NORMAL;
        autoStart = false;
    }
}
