/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.syndicate.verify.check.Check;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The harness CLI contract of docs/14_test_environment.md#D14-S4.2. */
@Tag("unit")
class VerifyOptionsTest {

    @Test
    void appliesTheDocumentedDefaults() {
        VerifyOptions options = VerifyOptions.parse(new String[] {"--asset", "assets/parts/plate"});

        assertThat(options.seed()).isEqualTo(VerifyOptions.DEFAULT_SEED).isEqualTo(1337L);
        assertThat(options.visual()).isFalse();
        assertThat(options.failFast()).isFalse();
        assertThat(options.categories()).containsExactlyInAnyOrder(Check.Category.values());
        // D14-S4.2: the default report path is derived from the asset name.
        assertThat(options.reportPath()).isEqualTo(Path.of("build", "verify", "plate.report.json"));
    }

    @Test
    void requiresAnAsset() {
        assertThatThrownBy(() -> VerifyOptions.parse(new String[] {"--headless"}))
                .isInstanceOf(VerifyOptions.UsageException.class)
                .hasMessageContaining("--asset");
    }

    @Test
    void rejectsContradictoryModes() {
        assertThatThrownBy(() -> VerifyOptions.parse(new String[] {"--asset", "a", "--visual", "--headless"}))
                .isInstanceOf(VerifyOptions.UsageException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void rejectsUnknownArguments() {
        // Silently ignoring a misspelled flag would produce a green run that checked something
        // other than what was asked for.
        assertThatThrownBy(() -> VerifyOptions.parse(new String[] {"--asset", "a", "--fastcheck"}))
                .isInstanceOf(VerifyOptions.UsageException.class)
                .hasMessageContaining("--fastcheck");
    }

    @Test
    void parsesACategorySubset() {
        VerifyOptions options = VerifyOptions.parse(new String[] {"--asset", "a", "--categories", "asset,progression"});
        assertThat(options.categories()).containsExactlyInAnyOrder(Check.Category.ASSET, Check.Category.PROGRESSION);
    }

    @Test
    void rejectsAnUnknownCategory() {
        assertThatThrownBy(() -> VerifyOptions.parse(new String[] {"--asset", "a", "--categories", "audio"}))
                .isInstanceOf(VerifyOptions.UsageException.class)
                .hasMessageContaining("audio");
    }

    /** A capture is a rendered frame, so requesting one must imply visual mode (D14-S5.11). */
    @Test
    void requestingACaptureImpliesVisualMode() {
        VerifyOptions options = VerifyOptions.parse(new String[] {"--asset", "a", "--capture", "out/frame.png"});
        assertThat(options.visual()).isTrue();
        assertThat(options.capturePath()).isEqualTo(Path.of("out/frame.png"));
    }
}
