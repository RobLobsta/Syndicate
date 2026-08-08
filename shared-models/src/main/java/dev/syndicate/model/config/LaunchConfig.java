/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.config;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.RuntimeMode;
import dev.syndicate.model.SimulationConstants;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The single configuration object produced at startup (docs/03_runtime_modes.md#D03-S4.2).
 *
 * <p>Every field of the D03-S4.2 table is present; D03-R4 states there is no hidden configuration,
 * so a field that is not here does not exist. Resolution — defaults, then config file, then
 * environment, then CLI flags — lives in {@link LaunchConfigResolver}.
 *
 * <p>Instances are immutable. {@link #sources()} records where each effective value came from, which
 * is what makes AC-D03-5 checkable and what the startup log prints.
 */
public record LaunchConfig(
        RuntimeMode mode,
        boolean headless,
        String serverHost,
        int serverPort,
        int maxPlayers,
        GameMode gameMode,
        AssetId arenaId,
        int botCount,
        BotDifficulty botDifficulty,
        long matchSeed,
        Path assetRoot,
        boolean strictAssets,
        int snapshotRateHz,
        String logLevel,
        Path logFile,
        boolean vsync,
        int maxFps,
        int windowWidth,
        int windowHeight,
        boolean fullscreen,
        Path configFile,
        boolean profile,
        boolean deterministicMode,
        boolean autoStart,
        int timeLimitSeconds,
        boolean adminConsole,
        Map<String, ConfigSource> sources) {

    /** Default listen/connect port (D03-S4.2). */
    public static final int DEFAULT_PORT = 27015;

    /** Default server capacity (D03-S4.2). */
    public static final int DEFAULT_MAX_PLAYERS = 12;

    /** Default arena (D03-S4.2). */
    public static final String DEFAULT_ARENA = "arena_scrapyard_01";

    /** Bots added in {@code SINGLE_PLAYER} when none are requested (D03-S4.2). */
    public static final int DEFAULT_SINGLE_PLAYER_BOTS = 7;

    public LaunchConfig {
        sources = Map.copyOf(sources);
    }

    /**
     * Read-only, present for logging only. D03-S4.2 marks {@code tickRateHz} read-only and G2
     * forbids varying it, so it is a method rather than a field: there is nothing to configure.
     */
    public int tickRateHz() {
        return SimulationConstants.TICK_RATE_HZ;
    }

    /** True when this process owns authoritative state (G1). */
    public boolean isAuthority() {
        return mode.isAuthority();
    }

    /** Where a given field's effective value came from (D03-R5). */
    public ConfigSource sourceOf(String field) {
        return sources.getOrDefault(field, ConfigSource.DEFAULT);
    }

    /**
     * The mode/flag combinations D03-S5.2 rejects. Each is fatal with {@link ExitCode#USAGE},
     * because a silently ignored contradiction is how a server runs misconfigured for a week
     * (D03-R6).
     *
     * @throws ConfigException if any combination is contradictory
     */
    public void validateCombination() {
        fatalIf(
                mode == RuntimeMode.LOCAL_CLIENT && botCount > 0,
                "--bots is meaningless when joining a server; bots belong to the authority");
        fatalIf(mode == RuntimeMode.DEDICATED_SERVER && !headless, "dedicated server cannot render");
        fatalIf(
                mode != RuntimeMode.LOCAL_CLIENT && serverHost != null,
                "--connect implies LOCAL_CLIENT, but --mode " + mode + " was given");
        fatalIf(headless && fullscreen, "contradictory display options: --headless with --fullscreen");
        fatalIf(headless && mode.renders(), "mode " + mode + " renders and cannot run headless");
        fatalIf(serverPort < 1 || serverPort > 65535, "port out of range: " + serverPort);
        fatalIf(maxPlayers < 1, "max-players must be at least 1");
        fatalIf(botCount < 0, "bots cannot be negative");
        fatalIf(snapshotRateHz < 1 || snapshotRateHz > tickRateHz(), "snapshot-rate must be in 1.." + tickRateHz());
    }

    private static void fatalIf(boolean condition, String message) {
        if (condition) {
            throw new ConfigException(ExitCode.USAGE, message);
        }
    }

    /** Every effective value with its source, ordered, for the startup log (D03-R5). */
    public List<String> describeEffectiveValues() {
        return List.of(
                describe("mode", mode),
                describe("headless", headless),
                describe("serverHost", serverHost),
                describe("serverPort", serverPort),
                describe("maxPlayers", maxPlayers),
                describe("gameMode", gameMode),
                describe("arenaId", arenaId),
                describe("botCount", botCount),
                describe("botDifficulty", botDifficulty),
                describe("matchSeed", matchSeed),
                describe("assetRoot", assetRoot),
                describe("strictAssets", strictAssets),
                "tickRateHz = " + tickRateHz() + " [FIXED: G2]",
                describe("snapshotRateHz", snapshotRateHz),
                describe("logLevel", logLevel),
                describe("logFile", logFile),
                describe("vsync", vsync),
                describe("maxFps", maxFps),
                describe("windowWidth", windowWidth),
                describe("windowHeight", windowHeight),
                describe("fullscreen", fullscreen),
                describe("configFile", configFile),
                describe("profile", profile),
                describe("deterministicMode", deterministicMode),
                describe("autoStart", autoStart),
                describe("timeLimitSeconds", timeLimitSeconds),
                describe("adminConsole", adminConsole));
    }

    private String describe(String field, Object value) {
        return field + " = " + value + " [" + sourceOf(field) + "]";
    }
}
