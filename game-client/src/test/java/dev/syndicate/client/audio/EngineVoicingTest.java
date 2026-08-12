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

/**
 * The three things a listener notices that a spectrum does not check
 * (T-D15-17, T-D15-18, T-D15-19).
 *
 * <p>{@link EngineSynthTest} asks whether the engine is at the right pitch and has the right
 * texture. These ask whether it does the right things: turns over like an engine, leans on the
 * throttle like a powerful one, and bangs when the driver lifts. All three were reported by ear
 * before they were measured, which is the argument for having them here.
 */
class EngineVoicingTest {

    private static final int SR = EngineSynth.SAMPLE_RATE_HZ;
    private static final int B = EngineMixer.BLOCK_FRAMES;
    private static final float DT = (float) B / SR;

    /** Cranking speed, from {@code EngineRunState}. The compression rate is derived from it. */
    private static final double CRANK_RPM = 260.0;

    /**
     * A cranking engine chuffs once per compression, and that rate is the engine's own.
     *
     * <p><b>The regression this exists for was a hardcoded 6 Hz</b> (R38a5) — a modulation rate
     * unrelated to the engine, under a comment claiming it was the compression rate. It made every
     * arrangement start identically and it made all of them sound like a fault rather than a car.
     * A four and a twelve differ by a factor of three here, and they should.
     */
    @Test
    @Tag("unit")
    void crankingChuffsAtTheCompressionRate() {
        for (EngineConfiguration configuration : EngineConfiguration.values()) {
            float[] start = renderStart(configuration, 2.0);
            double measured = dominantModulationHz(start, 0.25, 0.60);
            double expected = CRANK_RPM / 120.0 * configuration.cylinders();
            assertThat(measured)
                    .as("%s cranks at %.1f Hz; its compression rate is %.1f Hz", configuration, measured, expected)
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(expected * 0.15));
        }
    }

    /**
     * Power is audible as weight on the throttle, not only as volume.
     *
     * <p>Asserted as a difference between two engines rather than as an absolute, because the
     * absolute low-band share depends mostly on where the firing frequency happens to land — a four
     * at 3,000 rpm fires at 100 Hz and a V8 at 200, so the four looks bassier on any fixed band.
     * What has to be true is that the <em>powerful</em> engine's low end swings further between a
     * closed throttle and an open one.
     */
    @Test
    @Tag("unit")
    void aPowerfulEngineLeansOnTheThrottleMoreThanAWeakOne() {
        double strong = lowEndSwingDb(EngineConfiguration.V8, 608_000f);
        double weak = lowEndSwingDb(EngineConfiguration.I4, 110_000f);

        assertThat(strong)
                .as("a 608 kW V8 should gain real low end on the throttle")
                .isGreaterThan(3.0);
        assertThat(strong - weak)
                .as("the V8 swings %.1f dB and the small four %.1f dB", strong, weak)
                .isGreaterThan(2.0);
    }

    /** Low-band share on full throttle against a closed one, in dB, at a fixed engine speed. */
    private static double lowEndSwingDb(EngineConfiguration configuration, float powerW) {
        float[] open = renderSteady(configuration, powerW, 3000f, 1.0f);
        float[] shut = renderSteady(configuration, powerW, 3000f, 0.05f);
        double a = bandEnergy(open, 20, 120) / bandEnergy(open, 20, 4000);
        double b = bandEnergy(shut, 20, 120) / bandEnergy(shut, 20, 4000);
        return 10.0 * Math.log10(a / b);
    }

    /**
     * Lifting off at speed pops, and a car that is merely coasting does not.
     *
     * <p>Both halves matter. The crackle is armed by the throttle transition, so an engine that has
     * been off the throttle for a while must be quiet again — otherwise every car in the arena
     * crackles continuously, which is worse than not having it.
     */
    @Test
    @Tag("unit")
    void liftingOffPopsAndCoastingDoesNot() {
        EngineSynth lifted = v8();
        float[] block = new float[B];
        EngineSynth.State pull = new EngineSynth.State(5500f, 1f, 1f, false, -1, 0f, 0f);
        EngineSynth.State coast = new EngineSynth.State(5500f, 0f, 0f, false, -1, 0f, 0f);
        for (int i = 0; i < 200; i++) {
            lifted.render(block, B, pull);
        }
        float[] afterLift = capture(lifted, coast, 1.4);

        // The same engine at the same speed and load, which simply never lifted.
        EngineSynth steady = v8();
        for (int i = 0; i < 400; i++) {
            steady.render(block, B, coast);
        }
        float[] settled = capture(steady, coast, 1.4);

        double lift = bandEnergy(afterLift, 1200, 3000);
        double flat = bandEnergy(settled, 1200, 3000);
        assertThat(10.0 * Math.log10(lift / flat))
                .as("a lift should crackle above a coast at the same rpm")
                .isGreaterThan(1.5);

        double peakLift = peak(afterLift);
        assertThat(peakLift / peak(settled))
                .as("the bangs should stand clear of the coast, not merely raise its floor")
                .isGreaterThan(1.6);
    }

    // ---- Rendering -----------------------------------------------------------------------

    private static EngineSynth v8() {
        return new EngineSynth(EngineConfiguration.V8, Induction.SUPERCHARGED, 750f, 7600f, 608_000f, 3L);
    }

    /** Drives a whole ignition through {@link EngineRunState}, the way {@code AudioSystem} does. */
    private static float[] renderStart(EngineConfiguration configuration, double seconds) {
        EngineSynth synth = new EngineSynth(configuration, Induction.NATURALLY_ASPIRATED, 800f, 7500f, 400_000f, 5L);
        EngineRunState run = new EngineRunState(configuration.cylinders(), 800f, 5L);
        float[] block = new float[B];
        float[] out = new float[(int) (seconds * SR)];
        int at = 0;
        while (at < out.length) {
            synth.render(block, B, run.advance(DT, 800f, 0f, 0f, 1f));
            for (int i = 0; i < B && at < out.length; i++) {
                out[at++] = block[i];
            }
        }
        return out;
    }

    private static float[] renderSteady(EngineConfiguration configuration, float powerW, float rpm, float throttle) {
        EngineSynth synth = new EngineSynth(configuration, Induction.NATURALLY_ASPIRATED, 800f, 7000f, powerW, 5L);
        EngineSynth.State state = new EngineSynth.State(rpm, throttle, throttle, false, -1, 0f, 0f);
        float[] block = new float[B];
        // Settle the filters before capturing, so the shelf is where it will stay.
        for (int i = 0; i < 80; i++) {
            synth.render(block, B, state);
        }
        return capture(synth, state, 1.0);
    }

    private static float[] capture(EngineSynth synth, EngineSynth.State state, double seconds) {
        float[] block = new float[B];
        float[] out = new float[(int) (seconds * SR)];
        int at = 0;
        while (at < out.length) {
            synth.render(block, B, state);
            for (int i = 0; i < B && at < out.length; i++) {
                out[at++] = block[i];
            }
        }
        return out;
    }

    // ---- Measurement ---------------------------------------------------------------------

    /**
     * The rate at which the signal's loudness rises and falls, in Hz.
     *
     * <p>Measured on the rectified envelope rather than the waveform: the chuff of a cranking engine
     * is an amplitude modulation, not a pitch, and looking for it in the spectrum proper would find
     * the exhaust pulses instead.
     */
    private static double dominantModulationHz(float[] x, double fromSeconds, double toSeconds) {
        int a = (int) (fromSeconds * SR);
        int b = Math.min(x.length, (int) (toSeconds * SR));
        double mean = 0.0;
        for (int i = a; i < b; i++) {
            mean += Math.abs(x[i]);
        }
        mean /= (b - a);

        double bestHz = 0.0;
        double best = 0.0;
        for (double hz = 4.0; hz <= 40.0; hz += 0.25) {
            double re = 0.0;
            double im = 0.0;
            for (int i = a; i < b; i++) {
                double envelope = Math.abs(x[i]) - mean;
                double w = 2.0 * Math.PI * hz * (i - a) / SR;
                re += envelope * Math.cos(w);
                im += envelope * Math.sin(w);
            }
            double magnitude = Math.hypot(re, im);
            if (magnitude > best) {
                best = magnitude;
                bestHz = hz;
            }
        }
        return bestHz;
    }

    /**
     * Mean power in a band, measured by filtering rather than by transforming.
     *
     * <p>A Goertzel grid was tried first and was wrong: probing a handful of frequencies that do not
     * line up with the engine's orders leaks badly, and it reported a 0.3 dB low-end swing where an
     * FFT of the same signal showed 4.9 dB. Filtering has no resolution to get wrong, and the
     * synthesiser's own biquad is right there in the package.
     */
    private static double bandEnergy(float[] x, double lo, double hi) {
        EngineSynth.Biquad high1 = new EngineSynth.Biquad();
        EngineSynth.Biquad high2 = new EngineSynth.Biquad();
        EngineSynth.Biquad low1 = new EngineSynth.Biquad();
        EngineSynth.Biquad low2 = new EngineSynth.Biquad();
        high1.highPass(lo, 0.707);
        high2.highPass(lo, 0.707);
        low1.lowPass(hi, 0.707);
        low2.lowPass(hi, 0.707);
        double sum = 0.0;
        // The first tenth is discarded: four biquads starting from rest have a transient of their
        // own, and at 20 Hz it is long enough to matter.
        int skip = x.length / 10;
        for (int i = 0; i < x.length; i++) {
            double v = low2.process(low1.process(high2.process(high1.process(x[i]))));
            if (i >= skip) {
                sum += v * v;
            }
        }
        return sum / Math.max(1, x.length - skip);
    }

    private static double peak(float[] x) {
        double max = 0.0;
        for (float v : x) {
            max = Math.max(max, Math.abs(v));
        }
        return max;
    }
}
