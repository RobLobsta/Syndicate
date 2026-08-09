/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.model.ExitCode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dedicated server's startup sequence and exit codes (docs/03_runtime_modes.md#D03-S5.1,
 * #D03-S4.4).
 *
 * <p>Each test drives {@code run} end to end — configuration, natives, assets, world, schedule,
 * loop, teardown — for a bounded number of ticks. That is the whole point: until this session the
 * simulation only ran inside a test scene that assembled its own schedule, and a bootstrap nobody
 * executes is a bootstrap that has never worked.
 */
@Tag("integration")
class ServerMainTest {

    /** AC-D03-7: a clean bounded run reports OK. */
    @Test
    @Timeout(60)
    void aBoundedRunStartsTicksAndShutsDownCleanly(@TempDir Path assetRoot) {
        ExitCode exit = ServerMain.run(args(assetRoot), 5L);

        assertThat(exit).isEqualTo(ExitCode.OK);
    }

    /** D03-S4.4: an unknown flag is 64 (USAGE), decided before anything is initialised. */
    @Test
    @Timeout(60)
    void anUnknownFlagIsUsage() {
        assertThat(ServerMain.run(new String[] {"--not-a-flag"}, 1L)).isEqualTo(ExitCode.USAGE);
    }

    /** D03-S4.4: a missing asset root is 66 (ASSETS_NOT_FOUND), not a stack trace. */
    @Test
    @Timeout(60)
    void aMissingAssetRootIsAssetsNotFound(@TempDir Path parent) {
        Path missing = parent.resolve("no-such-directory");

        assertThat(ServerMain.run(args(missing), 1L)).isEqualTo(ExitCode.ASSETS_NOT_FOUND);
    }

    /** G18: a malformed material table degrades the load rather than refusing to start. */
    @Test
    @Timeout(60)
    void aBadAssetDegradesRatherThanRefusingToStart(@TempDir Path assetRoot) throws Exception {
        writeBrokenMaterials(assetRoot);

        assertThat(ServerMain.run(args(assetRoot), 5L)).isEqualTo(ExitCode.OK);
    }

    /** D03-S5.1: the same content under {@code --strict-assets} is 67 (ASSETS_INVALID). */
    @Test
    @Timeout(60)
    void aBadAssetUnderStrictModeIsAssetsInvalid(@TempDir Path assetRoot) throws Exception {
        writeBrokenMaterials(assetRoot);

        String[] args = {"--mode", "DEDICATED_SERVER", "--assets", assetRoot.toString(), "--strict-assets"};
        assertThat(ServerMain.run(args, 5L)).isEqualTo(ExitCode.ASSETS_INVALID);
    }

    private static String[] args(Path assetRoot) {
        return new String[] {"--mode", "DEDICATED_SERVER", "--assets", assetRoot.toString()};
    }

    /** A material with a non-positive density, which the loader reports as A203. */
    private static void writeBrokenMaterials(Path assetRoot) throws Exception {
        Path materials = assetRoot.resolve("materials");
        Files.createDirectories(materials);
        Files.writeString(
                materials.resolve("materials.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "materials": [
                    { "materialId": "steel", "densityKgPerM3": 0.0, "resistance": {}, "fractureBrittleness": 0.5 }
                  ]
                }
                """);
    }
}
