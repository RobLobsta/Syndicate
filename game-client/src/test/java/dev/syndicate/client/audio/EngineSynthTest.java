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
 * What the synthesised engine actually produces (T-D15-11, T-D15-12, T-D15-13).
 *
 * <p><b>These exist because the tests they replace could not fail.</b> The bank's engine loops were
 * covered by two assertions — that a V8 carried at least three sub-firing-order lines, and that
 * every loop's spectrum had at least two resonant peaks — and both were satisfied by the very defect
 * that made the loops wrong (DISC-025). A filter bank that gates away everything between its
 * formants creates sub-order lines and creates spectral peaks; what it destroys is the firing order,
 * and nothing asked about the firing order.
 *
 * <p>So the two tests here are the ones that were missing: <b>the firing order survives the exhaust</b>
 * (R38a3), and <b>the spectrum moves when the engine speed moves</b> (R38a2). On the bank this
 * replaces, the V8's loudest component sat at 0.375× its firing frequency and the V6's at 3×.
 */
class EngineSynthTest {

    private static final int SR = EngineSynth.SAMPLE_RATE_HZ;

    /** Long enough for a stable spectrum at the lowest speed tested, and a whole number of blocks. */
    private static final int ANALYSIS_FRAMES = SR;

    /** Discarded before analysis, so the resonators are not still settling from silence. */
    private static final int SETTLE_FRAMES = SR / 4;

    /**
     * The firing order is never buried by the exhaust.
     *
     * <p><b>This is the assertion the old suite was missing</b>, and it is deliberately not "the
     * firing order is the loudest order" — that is false of real engines and of this one. At low rpm
     * a four-cylinder's fundamental sits below every exhaust resonance and its second harmonic
     * carries; at high rpm a cross-plane V8's burble can edge past it. Both are correct behaviour and
     * both would make a strict test flap.
     *
     * <p>What is <em>not</em> correct is the firing order being inaudible. Measured across the six
     * configurations at five speeds, it leads in 21 of 30 cases and is never worse than 9.6 dB down;
     * the bank this replaces had the V8's firing order 25 dB down, with 95% of the energy in two
     * fixed formant bands. Twelve decibels separates those two worlds with room to tune inside it.
     */
    @Test
    @Tag("unit")
    void theFiringOrderIsNeverBuriedByTheExhaust() {
        for (EngineConfiguration configuration : EngineConfiguration.values()) {
            for (double rpm : new double[] {1200, 2000, 3000, 4500, 6000}) {
                double[] orders = orderSpectrum(configuration, Induction.NATURALLY_ASPIRATED, rpm, 24);
                double loudest = 0.0;
                for (int order = 1; order <= 24; order++) {
                    loudest = Math.max(loudest, orders[order]);
                }
                double belowDb = 20.0 * Math.log10(orders[configuration.cylinders()] / loudest);
                assertThat(belowDb)
                        .as(
                                "%s at %.0f rpm: the firing order is %.1f dB below the loudest",
                                configuration, rpm, belowDb)
                        .isGreaterThan(-MAX_FIRING_ORDER_DEFICIT_DB);
            }
        }
    }

    /** How far under the loudest order the firing order may sit before the engine has been filtered away. */
    private static final double MAX_FIRING_ORDER_DEFICIT_DB = 12.0;

    /**
     * The spectrum moves with engine speed.
     *
     * <p><b>The direct regression test for DISC-025.</b> The defect that made the old bank wrong was
     * not a bad number anywhere — it was that a chain of three band-passes with no dry path pinned
     * the output to the formant frequencies, so a V8's loudest component was 100 Hz at every rpm the
     * loop was pitched to and only moved because the whole file was being resampled. Synthesised at
     * the true rpm, doubling the engine speed must double the frequencies it produces. A fixed
     * formant cannot pass this; an engine cannot fail it.
     *
     * <p>Measured on the spectral centroid rather than on the loudest single order, because the
     * loudest order hops between a fundamental and its harmonic as the two trade places through a
     * resonance, and twice landed on the same frequency at two speeds by coincidence. The centroid
     * is where the energy actually is.
     *
     * <p>The bounds are wide on purpose. Over a four-fold rpm change the centroid rises by between
     * 2.85× and 3.51× across the six configurations — less than four, and it <em>should</em> be less
     * than four, because the exhaust's resonances are fixed and hold some energy where they are.
     * That sub-linearity is the thing that makes a big engine sound big at any speed. Only a design
     * that had gone back to filtering everything else away would score near 1.
     */
    @Test
    @Tag("unit")
    void theSpectrumTracksEngineSpeed() {
        for (EngineConfiguration configuration : EngineConfiguration.values()) {
            double low = spectralCentroidHz(configuration, 1500);
            double high = spectralCentroidHz(configuration, 6000);
            assertThat(high / low)
                    .as("%s: centroid %.0f Hz at 1500 rpm and %.0f Hz at 6000 rpm", configuration, low, high)
                    .isBetween(2.0, 4.0);
        }
    }

    /** Energy-weighted mean frequency over the engine's own orders. */
    private static double spectralCentroidHz(EngineConfiguration configuration, double rpm) {
        double[] orders = orderSpectrum(configuration, Induction.NATURALLY_ASPIRATED, rpm, 24);
        double weighted = 0.0;
        double total = 0.0;
        for (int order = 1; order <= 24; order++) {
            double power = orders[order] * orders[order];
            weighted += rpm / 120.0 * order * power;
            total += power;
        }
        return total <= 0.0 ? 0.0 : weighted / total;
    }

    /**
     * A cross-plane V8 burbles and an even-firing V does not.
     *
     * <p>The same claim the old suite made, but measured against the firing order rather than in
     * isolation — the burble is odd-order energy that is <em>audible next to</em> the firing order,
     * and the previous version could be satisfied by an engine that had no firing order at all.
     */
    @Test
    @Tag("unit")
    void aCrossPlaneV8BurblesAndAnEvenFiringVDoesNot() {
        assertThat(subFiringOrderLines(EngineConfiguration.V8))
                .as("a cross-plane V8 should carry a series of sub-firing orders")
                .isGreaterThanOrEqualTo(3);

        for (EngineConfiguration configuration :
                new EngineConfiguration[] {EngineConfiguration.I4, EngineConfiguration.I6}) {
            assertThat(subFiringOrderLines(configuration))
                    .as("%s has one bank and fires evenly; it must not burble", configuration)
                    .isZero();
        }
        for (EngineConfiguration configuration :
                new EngineConfiguration[] {EngineConfiguration.V6, EngineConfiguration.V10, EngineConfiguration.V12}) {
            assertThat(subFiringOrderLines(configuration))
                    .as("%s fires evenly in both banks; at most a single offbeat line", configuration)
                    .isLessThanOrEqualTo(1);
        }
    }

    /**
     * A dead cylinder fills in the even-order nulls, which is what a misfire <em>is</em>.
     *
     * <p>A healthy cross-plane V8 bank has exact nulls at its even orders — the pulse train's own
     * geometry puts them there. Losing a cylinder breaks that symmetry, and the nulls filling in is
     * the lope a listener hears. Nobody wrote this behaviour; it falls out of not firing one pulse,
     * which is the argument for synthesising an engine rather than filtering a recording of one.
     */
    @Test
    @Tag("unit")
    void aDeadCylinderFillsTheEvenOrderNulls() {
        double[] healthy = orderSpectrum(
                EngineConfiguration.V8,
                Induction.NATURALLY_ASPIRATED,
                3000,
                16,
                new EngineSynth.State(3000f, 0.9f, 0.9f, false, -1, 0f, 0f));
        double[] sick = orderSpectrum(
                EngineConfiguration.V8,
                Induction.NATURALLY_ASPIRATED,
                3000,
                16,
                new EngineSynth.State(3000f, 0.9f, 0.9f, false, 3, 0f, 0f));

        double healthyEven = 0;
        double sickEven = 0;
        for (int order : new int[] {2, 4, 6, 10, 12, 14}) {
            healthyEven += healthy[order] / healthy[8];
            sickEven += sick[order] / sick[8];
        }
        assertThat(sickEven)
                .as("a dropped cylinder must break the bank symmetry that nulls the even orders")
                .isGreaterThan(healthyEven * 5.0);
    }

    /**
     * A supercharger is audible off the throttle and a turbo is not.
     *
     * <p>The whole reason {@link Induction} is an axis rather than a volume knob, and it moved here
     * from {@code EngineVoiceTest} when the curve did: a blower is geared to the crank so it whines
     * whenever the engine turns, and a turbo is spun by exhaust flow so at the same revs with a shut
     * throttle it has nothing driving it. Measured on the output at the blower's own order rather
     * than on the gain function, so it stays true of what a listener would hear.
     */
    @Test
    @Tag("unit")
    void aSuperchargerIsAudibleOffThrottleAndATurboIsNot() {
        double blownOff = inductionMagnitude(Induction.SUPERCHARGED, 0f);
        double blownOn = inductionMagnitude(Induction.SUPERCHARGED, 1f);
        double turboOff = inductionMagnitude(Induction.TURBO, 0f);
        double turboOn = inductionMagnitude(Induction.TURBO, 1f);

        assertThat(blownOff / blownOn)
                .as("a geared blower whines whether or not the driver is on the throttle")
                .isGreaterThan(0.5);
        assertThat(turboOff / turboOn)
                .as("an exhaust-driven turbo has nothing driving it off the throttle")
                .isLessThan(0.1);
    }

    /** Magnitude at the induction device's own order, with the engine held at 5,000 rpm. */
    private static double inductionMagnitude(Induction induction, float throttle) {
        EngineConfiguration configuration =
                induction == Induction.SUPERCHARGED ? EngineConfiguration.V8 : EngineConfiguration.V6;
        EngineSynth synth = new EngineSynth(configuration, induction, 800f, 8000f, 4242L);
        float[] block = new float[EngineMixer.BLOCK_FRAMES];
        float[] captured = new float[ANALYSIS_FRAMES];
        EngineSynth.State state = new EngineSynth.State(5000f, throttle, throttle, false, -1, 0f, 0f);

        int produced = 0;
        int skipped = 0;
        while (produced < ANALYSIS_FRAMES) {
            synth.render(block, block.length, state);
            for (int i = 0; i < block.length && produced < ANALYSIS_FRAMES; i++) {
                if (skipped < SETTLE_FRAMES) {
                    skipped++;
                } else {
                    captured[produced++] = block[i];
                }
            }
        }
        return magnitudeAt(captured, 5000.0 / 60.0 * induction.driveRatio());
    }

    /** A stopped engine is silent — including its blower, whose air term has no fundamental to lose. */
    @Test
    @Tag("unit")
    void aStoppedEngineIsCompletelySilent() {
        EngineSynth synth = new EngineSynth(EngineConfiguration.V8, Induction.SUPERCHARGED, 750f, 7600f, 99L);
        float[] block = new float[EngineMixer.BLOCK_FRAMES];
        // Run it first, so the filters have something in them to keep ringing if anything would.
        for (int i = 0; i < 200; i++) {
            synth.render(block, block.length, new EngineSynth.State(4000f, 1f, 1f, false, -1, 0f, 0f));
        }
        for (int i = 0; i < 200; i++) {
            synth.render(block, block.length, EngineSynth.State.STOPPED);
        }
        for (float sample : block) {
            assertThat(Math.abs(sample)).isZero();
        }
    }

    /** Same seed, same state, same samples — a voice must not depend on when it was created. */
    @Test
    @Tag("unit")
    void synthesisIsDeterministicForASeed() {
        assertThat(firstBlock(7L)).isEqualTo(firstBlock(7L));
        assertThat(firstBlock(7L)).isNotEqualTo(firstBlock(8L));
    }

    private static float[] firstBlock(long seed) {
        EngineSynth synth = new EngineSynth(EngineConfiguration.V8, Induction.SUPERCHARGED, 750f, 7600f, seed);
        float[] block = new float[EngineMixer.BLOCK_FRAMES];
        for (int i = 0; i < 20; i++) {
            synth.render(block, block.length, new EngineSynth.State(3000f, 0.8f, 0.8f, false, -1, 0f, 0f));
        }
        return block.clone();
    }

    /**
     * Counts sub-firing-order lines that are audible next to the firing order itself.
     *
     * <p>Half-orders only: a V8's burble lives at orders 1..7 against a firing order of 8, and an
     * even-firing arrangement has nothing there at all.
     */
    private static int subFiringOrderLines(EngineConfiguration configuration) {
        double[] orders = orderSpectrum(configuration, Induction.NATURALLY_ASPIRATED, 3000, 24);
        int firing = configuration.cylinders();
        int lines = 0;
        for (int order = 1; order < firing; order++) {
            if (orders[order] > orders[firing] * SUB_ORDER_THRESHOLD) {
                lines++;
            }
        }
        return lines;
    }

    /** Fraction of the firing order's magnitude at which a sub-order line counts as audible. */
    private static final double SUB_ORDER_THRESHOLD = 0.25;

    private static double[] orderSpectrum(
            EngineConfiguration configuration, Induction induction, double rpm, int maxOrder) {
        return orderSpectrum(
                configuration,
                induction,
                rpm,
                maxOrder,
                new EngineSynth.State((float) rpm, 0.9f, 0.9f, false, -1, 0f, 0f));
    }

    /**
     * Magnitude at each engine order, by Goertzel.
     *
     * <p>Order {@code n} is {@code n} events per 720° of crank, so the firing order of an engine is
     * its cylinder count. A whole FFT would answer a question nobody asked; these tests probe a
     * couple of dozen known frequencies, which Goertzel evaluates exactly and in one pass.
     */
    private static double[] orderSpectrum(
            EngineConfiguration configuration, Induction induction, double rpm, int maxOrder, EngineSynth.State state) {

        EngineSynth synth = new EngineSynth(configuration, induction, 800f, 8000f, 12345L);
        float[] block = new float[EngineMixer.BLOCK_FRAMES];
        float[] captured = new float[ANALYSIS_FRAMES];

        int produced = 0;
        int skipped = 0;
        while (produced < ANALYSIS_FRAMES) {
            synth.render(block, block.length, state);
            for (int i = 0; i < block.length && produced < ANALYSIS_FRAMES; i++) {
                if (skipped < SETTLE_FRAMES) {
                    skipped++;
                } else {
                    captured[produced++] = block[i];
                }
            }
        }

        double cycleHz = rpm / 120.0;
        double[] magnitudes = new double[maxOrder + 1];
        for (int order = 1; order <= maxOrder; order++) {
            magnitudes[order] = magnitudeAt(captured, cycleHz * order);
        }
        return magnitudes;
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
        double real = s1 - s2 * Math.cos(omega);
        double imaginary = s2 * Math.sin(omega);
        return 2.0 * Math.hypot(real, imaginary) / samples.length;
    }
}
