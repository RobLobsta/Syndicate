/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.model.BotDifficulty;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The difficulty table, and the shipped content that has to agree with it
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.2, D11-R4).
 */
@Tag("unit")
class BotDifficultyTableTest {

    private static final Path ASSET_ROOT = repositoryRoot().resolve("assets");

    /** D11-R3: harder is not the same as better-equipped, but it is monotonically sharper. */
    @Test
    void defaults_areMonotonicallyHarder() {
        BotDifficultyTable table = BotDifficultyTable.defaults();
        BotDifficultyParams previous = null;
        for (BotDifficulty level : BotDifficulty.values()) {
            BotDifficultyParams params = table.get(level);
            if (previous != null) {
                assertThat(params.reactionDelayS())
                        .as("%s reacts sooner", level)
                        .isLessThan(previous.reactionDelayS());
                assertThat(params.aimErrorRad()).as("%s aims truer", level).isLessThan(previous.aimErrorRad());
                assertThat(params.sensorUpdateHz())
                        .as("%s perceives more often", level)
                        .isGreaterThan(previous.sensorUpdateHz());
                assertThat(params.leadPredictionQuality())
                        .as("%s leads better", level)
                        .isGreaterThan(previous.leadPredictionQuality());
            }
            previous = params;
        }
    }

    /** Every level resolves, including a null one — a bot with no difficulty is a NORMAL bot. */
    @Test
    void get_neverReturnsNull() {
        BotDifficultyTable table = BotDifficultyTable.defaults();
        for (BotDifficulty level : BotDifficulty.values()) {
            assertThat(table.get(level)).isNotNull();
        }
        assertThat(table.get(null)).isEqualTo(table.get(BotDifficulty.NORMAL));
    }

    /** A partial table is completed from the defaults rather than left with holes. */
    @Test
    void of_fillsMissingLevelsFromTheDefaults() {
        BotDifficultyTable table = BotDifficultyTable.of(java.util.Map.of(
                BotDifficulty.EASY, BotDifficultyTable.defaults().get(BotDifficulty.BRUTAL)));

        assertThat(table.get(BotDifficulty.EASY))
                .isEqualTo(BotDifficultyTable.defaults().get(BotDifficulty.BRUTAL));
        assertThat(table.get(BotDifficulty.HARD))
                .isEqualTo(BotDifficultyTable.defaults().get(BotDifficulty.HARD));
    }

    /**
     * The shipped {@code balance/bot_difficulty.json} is the D11-S4.2 table.
     *
     * <p>The point of the code copy is that a file which fails to load leaves bots imperfect rather
     * than flawless. That only works if the two agree, and this is what keeps them agreeing.
     */
    @Test
    void shippedFile_matchesTheDefaults() {
        Path file = ASSET_ROOT.resolve("balance").resolve("bot_difficulty.json");
        assumeTrue(Files.isRegularFile(file), "assets/balance/bot_difficulty.json is not present");

        InMemoryAssetIndex index = new InMemoryAssetIndex();
        new AssetLoader((partTypeId, ref, dir) -> (MeshData) null).loadBotDifficulties(file, index);

        for (BotDifficulty level : BotDifficulty.values()) {
            assertThat(index.botDifficulties().get(level))
                    .as("%s", level)
                    .isEqualTo(BotDifficultyTable.defaults().get(level));
        }
    }

    /** A missing file leaves the defaults in place, not an empty table (D11-R4). */
    @Test
    void missingFile_keepsTheDefaults() {
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        new AssetLoader((partTypeId, ref, dir) -> (MeshData) null)
                .loadBotDifficulties(Path.of("does", "not", "exist.json"), index);

        assertThat(index.botDifficulties().get(BotDifficulty.EASY))
                .isEqualTo(BotDifficultyTable.defaults().get(BotDifficulty.EASY));
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("docs"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("") : path;
    }
}
