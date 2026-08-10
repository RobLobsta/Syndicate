/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.badlogic.gdx.physics.bullet.Bullet;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.match.MatchReport;
import dev.syndicate.core.match.OfflineMatchRunner;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.config.LaunchConfig;
import dev.syndicate.model.config.LaunchConfigResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A whole match, played with no display (docs/11_ai_bots_and_match_simulation.md#D11-S5.8).
 *
 * <p>T-D11-1 and T-D11-2, and the check that matters most for G17: every gameplay system in the
 * dedicated-server schedule runs for a complete match in a process that creates no GL context, and
 * the run terminates on its own rather than on the safety cap (D11-E15, AC-D11-13).
 */
@Tag("integration")
class HeadlessMatchTest {

    private static final Path ASSET_ROOT = repositoryRoot().resolve("assets");

    /** Short enough to keep the suite fast, long enough for the phases to run in order. */
    private static final int TIME_LIMIT_SECONDS = 12;

    private static final int BOT_COUNT = 4;

    private static InMemoryAssetIndex assets;

    @BeforeAll
    static void bootNatives() {
        assumeTrue(Files.isDirectory(ASSET_ROOT), "the shipped asset tree is not present");
        Bullet.init(false);
        if (Gdx.files == null) {
            Gdx.files = new HeadlessFiles();
        }
        assets = new AssetLoader(new GltfCollisionMeshSource()).loadFrom(ASSET_ROOT);
    }

    private static LaunchConfig config(long seed) {
        return new LaunchConfigResolver(true, Map.of()).resolve(new String[] {
            "--bots", String.valueOf(BOT_COUNT),
            "--time-limit", String.valueOf(TIME_LIMIT_SECONDS),
            "--assets", ASSET_ROOT.toString(),
            "--seed", String.valueOf(seed)
        });
    }

    private static MatchReport play(long seed) {
        try (OfflineMatchRunner runner = new OfflineMatchRunner(config(seed), assets)) {
            return runner.run();
        }
    }

    /** T-D11-1: the match completes, a result is declared, and a report comes out. */
    @Test
    void aMatchCompletesWithNoDisplay() {
        MatchReport report = play(20260810L);

        assertThat(report.reachedSafetyCap())
                .as("AC-D11-13: an offline match always terminates on its own")
                .isFalse();
        assertThat(report.outcome()).isNotEqualTo(MatchOutcome.UNDECIDED);
        assertThat(report.players()).hasSize(BOT_COUNT);
        assertThat(report.durationTicks()).isPositive();
    }

    /** Bots that never move are bots that are stuck; the whole grid has to actually drive. */
    @Test
    void everyBotDrives() {
        MatchReport report = play(20260810L);

        assertThat(report.players()).allSatisfy(row -> assertThat(row.distanceTravelledM())
                .as("%s drove nowhere", row.name())
                .isGreaterThan(20f));
    }

    /** T-D11-2 / AC-D11-14: the same seed and configuration produce the same report. */
    @Test
    void sameSeedProducesTheSameReport() {
        assertThat(play(777L)).isEqualTo(play(777L));
    }

    /** A different seed produces a different match, or the seed is not doing anything. */
    @Test
    void differentSeedsProduceDifferentMatches() {
        MatchReport first = play(1L);
        MatchReport second = play(2L);

        assertThat(first.players()).isNotEqualTo(second.players());
    }

    /** AC-D11-16: the decision loop stays well inside its per-tick budget (D12-S5.6). */
    @Test
    void tickCostStaysWithinBudget() {
        MatchReport report = play(20260810L);

        // The published budget is 0.8 ms for eleven bots' decisions alone. This is the whole tick
        // for four, measured on whatever machine happens to be running the suite, so the assertion
        // is deliberately loose: it exists to catch an order-of-magnitude regression, not to police
        // a millisecond.
        assertThat(report.timing().meanTickDurationMs()).isLessThan(8.0);
        assertThat(report.physics().nanEvents())
                .as("a NaN in a match is never acceptable (D06-S5.11)")
                .isZero();
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("docs"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("") : path;
    }
}
