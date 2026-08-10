/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.syndicate.model.AudioEvent;
import dev.syndicate.model.AudioMaterial;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The synthesised sound bank (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>Three properties are worth a test and one of them is the reason the bank is synthesised at all.
 * That it is <b>reproducible</b> — regenerating produces byte-identical files, so the committed bank
 * is exactly what the code says it is and a rebuild is a no-op diff. That every loop <b>joins
 * cleanly</b>, which is the property D15-R38 says generative audio cannot deliver. And that every
 * file is the format the engine expects, because a bank that is subtly 44.1 kHz is a bank that plays
 * everything slightly flat.
 */
@Tag("unit")
class SoundBankTest {

    private static final Path ASSET_ROOT = repositoryRoot().resolve("assets");
    private static final Path AUDIO_ROOT = ASSET_ROOT.resolve(SoundBankBuilder.AUDIO_DIRECTORY);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The committed bank is byte-for-byte what the generator produces today.
     *
     * <p>This is the drift check. Without it, a change to a synthesis constant would leave the
     * repository holding sounds nothing in the tree describes, and the only way to notice would be to
     * listen.
     */
    @Test
    void theCommittedBankMatchesWhatTheGeneratorProduces(@TempDir Path temp) throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        new SoundBankBuilder(temp).build();
        Path generated = temp.resolve(SoundBankBuilder.AUDIO_DIRECTORY);

        List<String> mismatched = new ArrayList<>();
        try (var stream = Files.list(generated)) {
            for (Path file : stream.sorted().toList()) {
                Path committed = AUDIO_ROOT.resolve(file.getFileName());
                if (!Files.exists(committed)) {
                    mismatched.add(file.getFileName() + " (missing from assets/audio)");
                } else if (!java.util.Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(committed))) {
                    mismatched.add(file.getFileName() + " (differs)");
                }
            }
        }
        assertThat(mismatched)
                .as("run ./gradlew :asset-pipeline:buildSoundBank to regenerate")
                .isEmpty();
    }

    /** Every manifest row names a file that exists, and every file has a row. */
    @Test
    void theManifestAndTheFilesAgree() throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        JsonNode manifest = mapper.readTree(
                AUDIO_ROOT.resolve(SoundBankBuilder.MANIFEST_FILE).toFile());
        List<String> declared = new ArrayList<>();
        for (JsonNode sound : manifest.path("sounds")) {
            String file = sound.path("file").asText();
            declared.add(file);
            assertThat(AUDIO_ROOT.resolve(file)).exists();
            assertThat(AudioEvent.valueOf(sound.path("event").asText())).isNotNull();
        }
        try (var stream = Files.list(AUDIO_ROOT)) {
            List<String> onDisk = stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".wav"))
                    .sorted()
                    .toList();
            assertThat(onDisk).containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    /** D15-R36: every event family the inventory names has at least one sound. */
    @Test
    void everyEventFamilyIsCovered() throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        JsonNode manifest = mapper.readTree(
                AUDIO_ROOT.resolve(SoundBankBuilder.MANIFEST_FILE).toFile());
        List<String> events = new ArrayList<>();
        manifest.path("sounds").forEach(sound -> events.add(sound.path("event").asText()));

        for (AudioEvent event : AudioEvent.values()) {
            assertThat(events).as("%s has no sound", event).contains(event.name());
        }
    }

    /** D15-R37: one impact and one settle per audio material, and no more. */
    @Test
    void impactsAreOnePerMaterialPerSeverity() throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        JsonNode manifest = mapper.readTree(
                AUDIO_ROOT.resolve(SoundBankBuilder.MANIFEST_FILE).toFile());
        for (AudioMaterial material : AudioMaterial.values()) {
            long impacts = countWhere(manifest, AudioEvent.IMPACT, material.name());
            long settles = countWhere(manifest, AudioEvent.DEBRIS_SETTLE, material.name());
            assertThat(impacts).as("%s impacts", material).isEqualTo(3);
            assertThat(settles).as("%s settles", material).isEqualTo(1);
        }
    }

    /**
     * Every loop joins without a click.
     *
     * <p>The test is not "the first and last samples are equal" — for broadband noise at 48 kHz that
     * would be absurd, because adjacent samples inside the buffer differ hugely too. What matters is
     * whether the step <em>at the join</em> stands out from the steps the buffer already contains, so
     * it is measured against the buffer's own 95th-percentile adjacent-sample difference.
     */
    @Test
    void everyLoopJoinsCleanly() throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        JsonNode manifest = mapper.readTree(
                AUDIO_ROOT.resolve(SoundBankBuilder.MANIFEST_FILE).toFile());
        for (JsonNode sound : manifest.path("sounds")) {
            if (!sound.path("loop").asBoolean()) {
                continue;
            }
            short[] samples = readPcm(AUDIO_ROOT.resolve(sound.path("file").asText()));
            int[] steps = new int[samples.length - 1];
            for (int i = 1; i < samples.length; i++) {
                steps[i - 1] = Math.abs(samples[i] - samples[i - 1]);
            }
            java.util.Arrays.sort(steps);
            int p95 = Math.max(1, steps[(int) (steps.length * 0.95)]);
            int seam = Math.abs(samples[0] - samples[samples.length - 1]);

            assertThat((double) seam / p95)
                    .as(
                            "%s joins with a step larger than the buffer's own",
                            sound.path("soundId").asText())
                    .isLessThan(1.0);
        }
    }

    /** 48 kHz mono 16-bit, and nothing at full scale — a clipped sound is a distorted one. */
    @Test
    void everySoundIsTheExpectedFormatAndDoesNotClip() throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        try (var stream = Files.list(AUDIO_ROOT)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".wav"))
                    .sorted()
                    .toList()) {
                byte[] bytes = Files.readAllBytes(file);
                assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                        .isEqualTo("RIFF");
                assertThat(littleEndianShort(bytes, 22))
                        .as("%s channels", file.getFileName())
                        .isEqualTo((short) 1);
                assertThat(littleEndianInt(bytes, 24))
                        .as("%s sample rate", file.getFileName())
                        .isEqualTo(Waveform.SAMPLE_RATE_HZ);
                assertThat(littleEndianShort(bytes, 34))
                        .as("%s bit depth", file.getFileName())
                        .isEqualTo((short) 16);

                short peak = 0;
                for (short sample : readPcm(file)) {
                    peak = (short) Math.max(peak, Math.abs(sample));
                }
                assertThat(peak).as("%s clips", file.getFileName()).isLessThan(Short.MAX_VALUE);
            }
        }
    }

    /** D15-R39: the licence sits beside the assets, and says they are generated. */
    @Test
    void theBankRecordsItsLicence() throws IOException {
        assumeTrue(Files.isDirectory(AUDIO_ROOT), "assets/audio has not been generated");

        String licence = Files.readString(AUDIO_ROOT.resolve("LICENCE.md"));
        assertThat(licence).contains("procedurally synthesised").contains("No third-party audio was used");
    }

    /** A per-sound seed derived from the id, so adding one sound does not move every other. */
    @Test
    void seedsAreDerivedFromTheSoundId() {
        assertThat(SoundBankBuilder.seedFor("impact_metal_light"))
                .isNotEqualTo(SoundBankBuilder.seedFor("impact_metal_medium"));
        assertThat(SoundBankBuilder.seedFor("impact_metal_light"))
                .isEqualTo(SoundBankBuilder.seedFor("impact_metal_light"));
    }

    // ---- Helpers -------------------------------------------------------------------------

    private static long countWhere(JsonNode manifest, AudioEvent event, String materialName) {
        long count = 0;
        for (JsonNode sound : manifest.path("sounds")) {
            if (event.name().equals(sound.path("event").asText())
                    && materialName.equals(sound.path("audioMaterial").asText(null))) {
                count++;
            }
        }
        return count;
    }

    private static short[] readPcm(Path wav) throws IOException {
        byte[] bytes = Files.readAllBytes(wav);
        int sampleCount = (bytes.length - 44) / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = littleEndianShort(bytes, 44 + i * 2);
        }
        return samples;
    }

    private static short littleEndianShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("docs"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("") : path;
    }
}
