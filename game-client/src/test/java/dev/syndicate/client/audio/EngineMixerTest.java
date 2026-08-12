/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Many engines, in many places, summed to two channels (T-D15-14, T-D15-15). */
class EngineMixerTest {

    private static final int SR = EngineMixer.SAMPLE_RATE_HZ;
    private static final int FRAMES = EngineMixer.BLOCK_FRAMES;

    private static EngineSynth.State running(float rpm) {
        return new EngineSynth.State(rpm, 0.9f, 0.9f, false, -1, 0f, 0f);
    }

    private static int acquireV8(EngineMixer mixer, long seed) {
        return mixer.acquire(EngineConfiguration.V8, Induction.SUPERCHARGED, 750f, 7600f, seed);
    }

    /** A car to the right is louder in the right ear, and to the left in the left. */
    @Test
    @Tag("unit")
    void aVoiceIsPannedToWhereItIs() {
        EngineMixer mixer = new EngineMixer();
        int slot = acquireV8(mixer, 1L);
        mixer.setListener(EngineMixer.Listener.ORIGIN);

        float[] stereo = new float[FRAMES * 2];
        mixer.publish(slot, new EngineMixer.VoiceUpdate(running(4000f), 30f, 0f, 0f, 1f));
        settle(mixer, stereo, 60);
        assertThat(energy(stereo, 1)).as("a car 30 m to the right").isGreaterThan(energy(stereo, 0) * 2.0);

        mixer.publish(slot, new EngineMixer.VoiceUpdate(running(4000f), -30f, 0f, 0f, 1f));
        settle(mixer, stereo, 60);
        assertThat(energy(stereo, 0)).as("a car 30 m to the left").isGreaterThan(energy(stereo, 1) * 2.0);
    }

    /** Further away is quieter, and past the audible range is nothing at all. */
    @Test
    @Tag("unit")
    void distanceAttenuatesAndEventuallySilences() {
        assertThat(EngineMixer.distanceGain(0f)).isEqualTo(1f);
        assertThat(EngineMixer.distanceGain(EngineMixer.REFERENCE_DISTANCE_M)).isEqualTo(1f);
        assertThat(EngineMixer.distanceGain(50f)).isLessThan(EngineMixer.distanceGain(20f));
        assertThat(EngineMixer.distanceGain(EngineMixer.MAX_AUDIBLE_M)).isEqualTo(0f);
        assertThat(EngineMixer.distanceGain(EngineMixer.MAX_AUDIBLE_M + 50f)).isEqualTo(0f);

        // And the top end goes first, which is why a distant car is a rumble.
        assertThat(EngineMixer.airCutoffHz(150f)).isLessThan(EngineMixer.airCutoffHz(10f));
    }

    /**
     * A car beyond the audible range costs nothing and cannot be heard.
     *
     * <p>The silence matters more than the saving: a voice that is skipped must be skipped
     * completely, or a distant engine leaks in through a delay line that is still being read.
     */
    @Test
    @Tag("unit")
    void aVoiceBeyondRangeIsSilent() {
        EngineMixer mixer = new EngineMixer();
        int slot = acquireV8(mixer, 2L);
        mixer.setListener(EngineMixer.Listener.ORIGIN);
        float[] stereo = new float[FRAMES * 2];
        mixer.publish(slot, new EngineMixer.VoiceUpdate(running(5000f), EngineMixer.MAX_AUDIBLE_M + 10f, 0f, 0f, 1f));
        settle(mixer, stereo, 40);
        assertThat(energy(stereo, 0) + energy(stereo, 1)).isZero();
    }

    /**
     * A car closing on the listener is doppler-shifted up, and one receding is shifted down.
     *
     * <p>Nothing in the mixer computes a doppler ratio. The shift is the delay line being read
     * faster than it is written, which is what an approaching source does to the air — so this test
     * is really asking whether the propagation delay is real.
     */
    @Test
    @Tag("unit")
    void anApproachingCarIsShiftedUpAndAReceptingOneDown() {
        double still = dominantHz(0f);
        double closing = dominantHz(-60f);
        double leaving = dominantHz(60f);

        // c / (c ∓ v) at 60 m/s is 1.21 and 0.85. Generous bounds, because the source is moving
        // throughout the capture and the measurement is an average over it.
        assertThat(still).as("a stationary car is not shifted").isBetween(195.0, 205.0);
        assertThat(closing / still).as("closing at 60 m/s").isBetween(1.08, 1.30);
        assertThat(leaving / still).as("receding at 60 m/s").isBetween(0.78, 0.94);
    }

    /**
     * Renders a steady engine while it moves at a constant speed along the listener's right axis,
     * and reports the frequency the listener actually receives.
     */
    private static double dominantHz(float metresPerSecond) {
        EngineMixer mixer = new EngineMixer();
        int slot = acquireV8(mixer, 3L);
        mixer.setListener(EngineMixer.Listener.ORIGIN);

        float[] stereo = new float[FRAMES * 2];
        // Starts near enough to stay well inside the audible range for the whole sweep, so the
        // measurement is not contaminated by the distance fade at the far edge.
        float x = 100f;
        // Let the delay line fill and the gains settle before anything is measured.
        for (int block = 0; block < 200; block++) {
            mixer.publish(slot, new EngineMixer.VoiceUpdate(running(3000f), x, 0f, 0f, 1f));
            mixer.render(stereo, FRAMES);
        }
        int captureBlocks = 140;
        float[] captured = new float[captureBlocks * FRAMES];
        for (int block = 0; block < captureBlocks; block++) {
            x += metresPerSecond * FRAMES / (float) SR;
            mixer.publish(slot, new EngineMixer.VoiceUpdate(running(3000f), x, 0f, 0f, 1f));
            mixer.render(stereo, FRAMES);
            for (int n = 0; n < FRAMES; n++) {
                captured[block * FRAMES + n] = stereo[n * 2] + stereo[n * 2 + 1];
            }
        }
        // The V8's firing order at 3,000 rpm is 200 Hz. Find where it actually landed — the band has
        // to be wide enough to contain the shift it is looking for, which at 60 m/s is ±21%.
        double best = 0;
        double bestHz = 0;
        for (double hz = 140; hz <= 280; hz += 0.25) {
            double magnitude = magnitudeAt(captured, hz);
            if (magnitude > best) {
                best = magnitude;
                bestHz = hz;
            }
        }
        return bestHz;
    }

    /** Every slot can run at once, and asking for one more is refused rather than overflowing. */
    @Test
    @Tag("unit")
    void everySlotCanSoundAtOnceAndTheNextIsRefused() {
        EngineMixer mixer = new EngineMixer();
        for (int i = 0; i < EngineMixer.MAX_VOICES; i++) {
            assertThat(acquireV8(mixer, i)).isNotNegative();
        }
        assertThat(acquireV8(mixer, 999L))
                .as("the 25th car is silent, not an exception")
                .isEqualTo(-1);
        assertThat(mixer.activeVoices()).isEqualTo(EngineMixer.MAX_VOICES);

        // All of them sounding, spread around the listener, must stay inside the rails.
        float[] stereo = new float[FRAMES * 2];
        for (int i = 0; i < EngineMixer.MAX_VOICES; i++) {
            float angle = (float) (i * 2 * Math.PI / EngineMixer.MAX_VOICES);
            mixer.publish(
                    i,
                    new EngineMixer.VoiceUpdate(
                            running(3000f + i * 100f),
                            (float) Math.cos(angle) * 12f,
                            0f,
                            (float) Math.sin(angle) * 12f,
                            1f));
        }
        settle(mixer, stereo, 100);
        for (float sample : stereo) {
            assertThat(Math.abs(sample)).as("24 engines must not clip the bus").isLessThanOrEqualTo(1f);
        }
        assertThat(energy(stereo, 0) + energy(stereo, 1)).isPositive();
    }

    /** A released slot stops sounding and can be taken again. */
    @Test
    @Tag("unit")
    void releasingASlotSilencesItAndFreesIt() {
        EngineMixer mixer = new EngineMixer();
        int slot = acquireV8(mixer, 4L);
        mixer.setListener(EngineMixer.Listener.ORIGIN);
        float[] stereo = new float[FRAMES * 2];
        mixer.publish(slot, new EngineMixer.VoiceUpdate(running(4000f), 5f, 0f, 0f, 1f));
        settle(mixer, stereo, 40);
        assertThat(energy(stereo, 0) + energy(stereo, 1)).isPositive();

        mixer.release(slot);
        settle(mixer, stereo, 4);
        assertThat(energy(stereo, 0) + energy(stereo, 1)).isZero();
        assertThat(mixer.activeVoices()).isZero();
        assertThat(acquireV8(mixer, 5L)).isEqualTo(slot);
    }

    /** A slot with nothing published is silent rather than rendering a default engine. */
    @Test
    @Tag("unit")
    void anUnpublishedSlotIsSilent() {
        EngineMixer mixer = new EngineMixer();
        acquireV8(mixer, 6L);
        float[] stereo = new float[FRAMES * 2];
        settle(mixer, stereo, 8);
        assertThat(energy(stereo, 0) + energy(stereo, 1)).isZero();
    }

    private static void settle(EngineMixer mixer, float[] stereo, int blocks) {
        for (int i = 0; i < blocks; i++) {
            mixer.render(stereo, FRAMES);
        }
    }

    /** Mean square of one channel of an interleaved buffer. */
    private static double energy(float[] stereo, int channel) {
        double sum = 0;
        for (int n = 0; n < stereo.length / 2; n++) {
            double v = stereo[n * 2 + channel];
            sum += v * v;
        }
        return sum / (stereo.length / 2.0);
    }

    private static double magnitudeAt(float[] samples, double frequencyHz) {
        double omega = 2.0 * Math.PI * frequencyHz / SR;
        double coefficient = 2.0 * Math.cos(omega);
        double s1 = 0.0;
        double s2 = 0.0;
        for (float sample : samples) {
            double s0 = sample + coefficient * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return Math.hypot(s1 - s2 * Math.cos(omega), s2 * Math.sin(omega)) / samples.length;
    }
}
