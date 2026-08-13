/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bank loads, or the game is silent — never neither (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>These tests run with no audio device, which is the state of every CI runner and of this
 * project's own sandbox, and that is exactly the case they exist for: G18 says a content or
 * environment problem degrades what the player gets rather than stopping them playing. A bank that
 * threw here would take the whole client down on any machine without a sound card.
 */
@Tag("unit")
class SoundBankTest {

    /** No manifest is a warning and an empty bank, not an exception. */
    @Test
    void aMissingManifestGivesAnEmptyBank(@TempDir Path assetRoot) {
        SoundBank bank = SoundBank.load(assetRoot);

        assertThat(bank.isAvailable()).isFalse();
        assertThat(bank.size()).isZero();
        assertThat(bank.get("glass_shatter_large")).isNull();
    }

    /** A malformed manifest is the same: silent, logged, and not fatal. */
    @Test
    void anUnreadableManifestGivesAnEmptyBank(@TempDir Path assetRoot) throws Exception {
        Path audio = Files.createDirectories(assetRoot.resolve(SoundBank.AUDIO_DIRECTORY));
        Files.writeString(audio.resolve(SoundBank.MANIFEST_FILE), "{ this is not json");

        SoundBank bank = SoundBank.load(assetRoot);

        assertThat(bank.isAvailable()).isFalse();
    }

    /** Every lookup on an empty bank answers null rather than throwing (G18 again). */
    @Test
    void lookupsOnAnEmptyBankAreNull(@TempDir Path assetRoot) {
        SoundBank bank = SoundBank.load(assetRoot);

        assertThat(bank.forKey(dev.syndicate.model.AudioEvent.IMPACT, "metal_heavy"))
                .isNull();
        assertThat(bank.forKey(null, "metal_heavy")).isNull();
        assertThat(bank.entry("impact_metal_heavy")).isNull();
    }
}
