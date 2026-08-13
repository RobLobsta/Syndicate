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
            int cylinders = configuration.cylinders();
            float[] start = renderStart(configuration, 2.0);
            // Measured from the end of the spin-up to the catch: both ends move per arrangement,
            // because a twelve takes longer to drag up to speed than a four.
            double from = 0.20;
            double to = EngineRunState.crankSeconds(cylinders);
            double expected = EngineRunState.crankRpm(cylinders) / 120.0 * cylinders;
            double measured = chuffRateHz(start, from, to);
            assertThat(measured)
                    .as("%s chuffs at %.1f Hz; its compression rate is %.1f Hz", configuration, measured, expected)
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
        float[] afterLift = afterLift(EngineConfiguration.V8, 5500f);
        float[] settled = coasting(EngineConfiguration.V8, 5500f);

        // Crest factor rather than energy in a fixed band, and the change is not cosmetic. The
        // first version of this asked for energy between 1.2 and 3 kHz, which measured where the
        // crackle *happened to be* when it was a single band-passed hiss. Giving the bang the low
        // thump a real detonation has moved most of its energy out of that band and the assertion
        // failed on a strictly better sound. What a bang is, is a peak that stands clear of its own
        // surroundings — which is band-agnostic, and is what the ear is doing.
        assertThat(crest(afterLift) / crest(settled))
                .as(
                        "a lift's peaks (crest %.1f) should stand clear of a coast's (%.1f)",
                        crest(afterLift), crest(settled))
                .isGreaterThan(1.35);

        assertThat(peak(afterLift) / peak(settled))
                .as("the bangs should stand clear of the coast, not merely raise its floor")
                .isGreaterThan(1.6);
    }

    /**
     * Pops arrive at exhaust events, so twice the engine speed is about twice the rate.
     *
     * <p><b>The regression this exists for was reported by ear as "popcorn"</b> (R38a7a). The
     * crackle fired from a free-running 26 Hz clock, which produced 11 to 14 evenly spaced bangs a
     * second on every engine at every speed — a stream of identical clicks with no relationship to
     * the car making them. It is the same class of fault as the hardcoded 6 Hz crank in R38a5, and
     * this is the assertion that catches it: a constant in hertz cannot pass, whatever its value,
     * because it does not know how fast the engine is turning.
     */
    @Test
    @Tag("unit")
    void popsArriveAtExhaustEventsRatherThanOnAClock() {
        // Summed over the arrangements rather than asserted on each. One lift yields only a handful
        // of bangs by design — a dozen a second was the defect — and a handful is too few to carry
        // a ratio on its own. Six of them is enough, and a clock would still give the same total at
        // both speeds, which is the thing being ruled out.
        double slow = 0.0;
        double fast = 0.0;
        for (EngineConfiguration configuration : EngineConfiguration.values()) {
            slow += bangs(afterLift(configuration, 3000f));
            fast += bangs(afterLift(configuration, 6000f));
        }
        assertThat(fast)
                .as("%.0f bangs at 6,000 rpm against %.0f at 3,000, over six arrangements", fast, slow)
                .isGreaterThan(slow * 1.5);
    }

    /** An engine that has just been lifted off at {@code rpm}. */
    private static float[] afterLift(EngineConfiguration configuration, float rpm) {
        EngineSynth synth = new EngineSynth(configuration, Induction.NATURALLY_ASPIRATED, 750f, 7600f, 608_000f, 3L);
        float[] block = new float[B];
        for (int i = 0; i < 200; i++) {
            synth.render(block, B, new EngineSynth.State(rpm, 1f, 1f, false, -1, 0f, 0f));
        }
        return capture(synth, new EngineSynth.State(rpm, 0f, 0f, false, -1, 0f, 0f), 1.4);
    }

    /** The same engine at the same speed and load, which simply never lifted. */
    private static float[] coasting(EngineConfiguration configuration, float rpm) {
        EngineSynth synth = new EngineSynth(configuration, Induction.NATURALLY_ASPIRATED, 750f, 7600f, 608_000f, 3L);
        float[] block = new float[B];
        EngineSynth.State coast = new EngineSynth.State(rpm, 0f, 0f, false, -1, 0f, 0f);
        for (int i = 0; i < 400; i++) {
            synth.render(block, B, coast);
        }
        return capture(synth, coast, 1.4);
    }

    /** Peak over RMS: how far the loudest moment stands above the general level. */
    private static double crest(float[] x) {
        double sum = 0.0;
        for (float v : x) {
            sum += (double) v * v;
        }
        return peak(x) / Math.max(1e-9, Math.sqrt(sum / x.length));
    }

    /**
     * How many transients stand clear of their own surroundings.
     *
     * <p>A fast envelope against a slow one, which is what makes a bang audible as a separate event
     * rather than as part of the note. Counted on rising edges at least 8 ms apart, so one bang is
     * one bang.
     */
    private static double bangs(float[] x) {
        double fast = 0.0;
        double slow = 0.0;
        double fastPole = Math.exp(-1.0 / (0.002 * SR));
        double slowPole = Math.exp(-1.0 / (0.150 * SR));
        int count = 0;
        int last = -SR;
        boolean hot = false;
        for (int i = 0; i < x.length; i++) {
            double a = Math.abs(x[i]);
            fast = a + fastPole * (fast - a);
            slow = a + slowPole * (slow - a);
            boolean now = fast > 2.5 * slow + 1e-6;
            if (now && !hot && i - last > 0.008 * SR) {
                count++;
                last = i;
            }
            hot = now;
        }
        return count;
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
     * How strongly the signal's loudness rises and falls at one rate, over one window.
     *
     * <p>Measured on the rectified envelope rather than the waveform: the chuff of a cranking engine
     * is an amplitude modulation, not a pitch, and looking for it in the spectrum proper would find
     * the exhaust pulses instead. Searched over a narrow band around the target because the crank
     * speed itself swings while it is being measured.
     */
    /**
     * How often the loudness repeats, in Hz, by autocorrelation of the envelope.
     *
     * <p><b>Autocorrelation rather than a spectral peak, and the reason is a bug this test had
     * three times.</b> A chuff is not a sinusoid — the starter labours under compression and coasts
     * over the top — so its second harmonic can be larger than its fundamental, and on a four and a
     * twelve it was. Every version of this that asked for the loudest Fourier component therefore
     * reported exactly twice the true rate, and one that preferred subharmonics to compensate
     * reported half of it on a different arrangement. Autocorrelation asks the question the ear
     * asks — how long until this repeats — and the first strong peak is that period whatever shape
     * the repeating thing has.
     */
    private static final double FIRST_PEAK_SHARE = 0.60;

    private static double chuffRateHz(float[] x, double fromSeconds, double toSeconds) {
        int a = (int) (fromSeconds * SR);
        int b = Math.min(x.length, (int) (toSeconds * SR));
        int n = b - a;
        double mean = 0.0;
        for (int i = a; i < b; i++) {
            mean += Math.abs(x[i]);
        }
        mean /= n;
        double[] envelope = new double[n];
        for (int i = 0; i < n; i++) {
            envelope[i] = Math.abs(x[a + i]) - mean;
        }

        int shortest = (int) (SR / 45.0);
        int longest = Math.min(n / 2, (int) (SR / 4.0));
        double[] correlation = new double[longest + 1];
        double best = 0.0;
        for (int lag = shortest; lag <= longest; lag++) {
            double sum = 0.0;
            for (int i = 0; i + lag < n; i++) {
                sum += envelope[i] * envelope[i + lag];
            }
            correlation[lag] = sum / (n - lag);
            best = Math.max(best, correlation[lag]);
        }
        // The *first* lag that gets close to the best one. Autocorrelation peaks again at every
        // multiple of the true period, so taking the largest would prefer two chuffs to one.
        for (int lag = shortest; lag < longest; lag++) {
            if (correlation[lag] > FIRST_PEAK_SHARE * best
                    && correlation[lag] >= correlation[lag - 1]
                    && correlation[lag] >= correlation[lag + 1]) {
                return (double) SR / lag;
            }
        }
        return 0.0;
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
