/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline.audio;

import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regenerates {@code assets/audio/} (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>A separate entry point from {@code PipelineMain} because generating content and validating it
 * are different jobs with different failure modes, and a validator that could rewrite its own inputs
 * is a validator whose green run proves nothing.
 */
public final class SoundBankMain {

    private static final Logger LOG = LoggerFactory.getLogger(SoundBankMain.class);

    private SoundBankMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws IOException {
        Path assetRoot = Path.of("assets");
        for (int i = 0; i < args.length - 1; i++) {
            if ("--assets".equals(args[i])) {
                assetRoot = Path.of(args[i + 1]);
            }
        }
        int written = new SoundBankBuilder(assetRoot).build();
        LOG.info("wrote {} sounds to {}", written, assetRoot.resolve(SoundBankBuilder.AUDIO_DIRECTORY));
    }
}
