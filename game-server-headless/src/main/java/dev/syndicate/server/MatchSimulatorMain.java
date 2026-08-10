/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.match.MatchReport;
import dev.syndicate.core.match.OfflineMatchRunner;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.ConfigException;
import dev.syndicate.model.config.LaunchConfig;
import dev.syndicate.model.config.LaunchConfigResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays complete matches with no window, no client and no human
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.8).
 *
 * <p>The answer to "can a match be played without a graphical display?" — run this, read the report.
 * It is also the smoke test D11-S5.8 exists for: every gameplay system runs, for a whole match, in a
 * process that creates no GL context (G17), and a crash or a hang comes back as a non-zero exit code
 * rather than as a screenshot nobody took.
 *
 * <p>Arguments are {@link LaunchConfig}'s, plus {@code --matches N} to run several with derived
 * seeds and {@code --report DIR} to write each report as JSON. Matches run one after another in one
 * process, which is exactly the case {@code MatchRulesComponent} exists as a component for: two
 * matches in one JVM must not share configuration through a static.
 */
public final class MatchSimulatorMain {

    private static final Logger LOG = LoggerFactory.getLogger(MatchSimulatorMain.class);

    /** How many matches to run when {@code --matches} is not given. */
    public static final int DEFAULT_MATCH_COUNT = 1;

    private MatchSimulatorMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.exit(run(args).code());
    }

    /**
     * Runs the configured matches.
     *
     * @return {@link ExitCode#OK} when every match reached {@code RESULTS} on its own,
     *     {@link ExitCode#INTERNAL_ERROR} when any hit the safety cap (D11-E15)
     */
    public static ExitCode run(String[] args) {
        List<String> remaining = new ArrayList<>();
        int matchCount = DEFAULT_MATCH_COUNT;
        Path reportDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--matches" -> matchCount = Math.max(1, Integer.parseInt(args[++i]));
                case "--report" -> reportDir = Path.of(args[++i]);
                default -> remaining.add(args[i]);
            }
        }

        LaunchConfig base;
        try {
            base = new LaunchConfigResolver(true, System.getenv()).resolve(remaining.toArray(new String[0]));
            base.validateCombination();
        } catch (ConfigException e) {
            LOG.error("configuration error: {}", e.getMessage());
            return e.exitCode();
        }

        try {
            Bullet.init(false);
        } catch (UnsatisfiedLinkError e) {
            LOG.error("Bullet natives are unavailable for this platform", e);
            return ExitCode.NATIVES_MISSING;
        }
        if (Gdx.files == null) {
            Gdx.files = new HeadlessFiles();
        }

        InMemoryAssetIndex assets = new AssetLoader(new GltfCollisionMeshSource()).loadFrom(base.assetRoot());
        boolean allTerminated = true;
        for (int i = 0; i < matchCount; i++) {
            // D11-R13: a sweep's seeds are derived from the base seed, so a crash found at match 47
            // is replayed by running match 47 alone with the same base seed.
            LaunchConfig config = withSeed(base, base.matchSeed() + i);
            MatchReport report;
            try (OfflineMatchRunner runner = new OfflineMatchRunner(config, assets)) {
                report = runner.run();
            }
            logSummary(i, report);
            allTerminated &= !report.reachedSafetyCap();
            if (reportDir != null) {
                writeReport(reportDir, i, report);
            }
        }
        return allTerminated ? ExitCode.OK : ExitCode.INTERNAL_ERROR;
    }

    private static void logSummary(int index, MatchReport report) {
        LOG.info(
                "match {} seed {}: {} after {} ticks ({} s of simulated time), mean tick {} ms, peak {} ms",
                index,
                report.seed(),
                report.outcome(),
                report.durationTicks(),
                report.durationTicks() / 60,
                String.format("%.3f", report.timing().meanTickDurationMs()),
                String.format("%.3f", report.timing().maxTickDurationMs()));
        for (MatchReport.PlayerRow row : report.players()) {
            LOG.info(
                    "  {} {}: {} kills, {} deaths, {} assists, {} points, {} m driven",
                    row.isBot() ? "bot" : "human",
                    row.name(),
                    row.kills(),
                    row.deaths(),
                    row.assists(),
                    row.objectiveScore(),
                    Math.round(row.distanceTravelledM()));
        }
    }

    private static void writeReport(Path directory, int index, MatchReport report) {
        try {
            Files.createDirectories(directory);
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Path file = directory.resolve(String.format("match-%03d-seed-%d.json", index, report.seed()));
            mapper.writeValue(file.toFile(), report);
            LOG.info("wrote {}", file);
        } catch (IOException e) {
            // A report that cannot be written is worth saying loudly and is not worth failing the
            // run over: the match itself already happened and its outcome is in the log.
            LOG.error("could not write the report for match {}", index, e);
        }
    }

    /**
     * The same configuration with a different match seed.
     *
     * <p>{@link LaunchConfig} is a record with no wither, and adding one for a field the simulator is
     * the only caller of would put a mutation affordance on a type whose immutability is the point
     * (D03-R4). Rebuilding it here keeps that property where it belongs.
     */
    private static LaunchConfig withSeed(LaunchConfig base, long seed) {
        return new LaunchConfig(
                base.mode(),
                base.headless(),
                base.serverHost(),
                base.serverPort(),
                base.maxPlayers(),
                base.gameMode(),
                base.arenaId(),
                base.botCount(),
                base.botDifficulty(),
                seed,
                base.assetRoot(),
                base.strictAssets(),
                base.snapshotRateHz(),
                base.logLevel(),
                base.logFile(),
                base.vsync(),
                base.maxFps(),
                base.windowWidth(),
                base.windowHeight(),
                base.fullscreen(),
                base.configFile(),
                base.profile(),
                base.deterministicMode(),
                base.autoStart(),
                base.timeLimitSeconds(),
                base.adminConsole(),
                base.sources());
    }
}
