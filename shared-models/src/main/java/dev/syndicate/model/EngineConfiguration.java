/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * How many cylinders an engine has and how they are arranged
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p><b>This is the axis on which cars sound different from each other.</b> D15-R37 says sounds are
 * per class and per material, never per vehicle, and the reason is cost: a per-vehicle bank makes
 * every new car wait for an audio pass. Vehicle <em>class</em> turns out to be the wrong axis for an
 * engine, though — "medium" and "heavy" describe how much a car weighs, and what an engine sounds
 * like is decided by how many times a second it fires and how evenly.
 *
 * <p>Configuration is the right axis and it is still a small closed set. Six of these cover every
 * road and race car worth modelling, a new vehicle picks one rather than commissioning anything, and
 * two cars sharing a configuration still sound different because the rest of the character —
 * firing frequency, gain, low-end weight — comes from their own rpm and power (see
 * {@code EngineVoice}).
 *
 * <p>{@link #cylinders} is what sets the firing frequency: a four-stroke fires
 * {@code cylinders × rpm / 120} times a second, so a V8 at 3,000 rpm fires at 200 Hz and a V6 at
 * 150. That is most of a musical fourth apart and is immediately audible.
 *
 * <p>{@link #roughness} is the other half. An engine with fewer, larger cylinders has bigger
 * pressure pulses and longer gaps between them, which the ear hears as a hard-edged bark; a V12's
 * overlapping pulses read as a smooth tearing note.
 *
 * <p><b>{@link #bankOf} is what makes a cross-plane V8 a cross-plane V8.</b> An engine is not a tone
 * generator at {@code firingHz}: it is a train of exhaust pulses at particular crank angles, and
 * where those pulses are unevenly spaced the ear hears the unevenness directly. The famous case is
 * the cross-plane V8, which fires evenly every 90° taken as a whole, but whose two banks each fire
 * at {@code 90-180-180-270} within one 720° cycle. Each bank has its own exhaust manifold, so a
 * listener hears two uneven pulse trains — and the beat between them is the burble. A flat-plane V8
 * of the same cylinder count fires each bank evenly at 180° and sounds nothing like it.
 *
 * <p>That is why an arrangement carries a bank assignment rather than only a roughness number. An
 * earlier cut of this enum modelled unevenness as a noise gain, which produces a hissier engine
 * rather than a lumpier one: the two are not substitutes, because the ear locates the unevenness in
 * <em>time</em> and a noise gain puts it in the spectrum.
 */
public enum EngineConfiguration {

    /** Inline four. Small, buzzy, coarse. One bank, evenly every 180°. */
    I4(4, 0.30f, inline(4)),

    /** Inline six. Perfectly balanced, smooth, and characteristically hard-edged at the top. */
    I6(6, 0.16f, inline(6)),

    /** V6. Compact and slightly uneven; the modern turbocharged supercar's engine. */
    V6(6, 0.22f, evenVee(6)),

    /**
     * Cross-plane V8. Even every 90° overall, {@code 90-180-180-270} within each bank.
     *
     * <p>Firing order 1-5-4-8-6-3-7-2 with cylinders 1–4 on the left bank, which is the American V8
     * arrangement the reference car uses. The bank assignment here is that order's, and it is the
     * whole reason this configuration sounds like it does.
     */
    V8(8, 0.26f, new int[] {0, 1, 0, 1, 1, 0, 1, 0}),

    /** V10. Flat-plane-ish, high-revving, and unmistakably shrill. */
    V10(10, 0.14f, evenVee(10)),

    /** V12. The smoothest thing here, and the one that sounds like tearing silk. */
    V12(12, 0.10f, evenVee(12));

    /** Crank degrees in one four-stroke cycle: two revolutions. */
    public static final double CYCLE_DEGREES = 720.0;

    private final int cylinders;
    private final float roughness;
    private final int[] banks;

    EngineConfiguration(int cylinders, float roughness, int[] banks) {
        this.cylinders = cylinders;
        this.roughness = roughness;
        this.banks = banks;
    }

    /** One bank, so every firing event shares an exhaust. */
    private static int[] inline(int cylinders) {
        return new int[cylinders];
    }

    /** Two banks taken alternately, which is what an even-firing V does. */
    private static int[] evenVee(int cylinders) {
        int[] banks = new int[cylinders];
        for (int i = 0; i < cylinders; i++) {
            banks[i] = i % 2;
        }
        return banks;
    }

    /** How many cylinders fire per two crank revolutions. */
    public int cylinders() {
        return cylinders;
    }

    /** How much cycle-to-cycle noise the loop carries, {@code [0,1]}. */
    public float roughness() {
        return roughness;
    }

    /**
     * The crank angle, in degrees within one 720° cycle, at which each firing event happens.
     *
     * <p>Evenly spaced for every configuration here, because even firing is what a crank is designed
     * to deliver and real engines overwhelmingly achieve it. The unevenness a listener hears in a
     * cross-plane V8 is not in this array — it is in {@link #bankOf}, which splits these events
     * between two exhausts that are each uneven on their own.
     */
    public double[] firingAngles() {
        double[] angles = new double[cylinders];
        for (int i = 0; i < cylinders; i++) {
            angles[i] = i * CYCLE_DEGREES / cylinders;
        }
        return angles;
    }

    /**
     * Which exhaust bank the {@code index}-th firing event leaves through.
     *
     * <p>Two banks means two manifolds of different length and therefore two slightly different
     * voices, which is what the synthesiser gives them. For an inline engine every event returns
     * bank 0 and the distinction costs nothing.
     */
    public int bankOf(int index) {
        return banks[Math.floorMod(index, banks.length)];
    }

    /** How many exhaust banks this arrangement has: 1 for an inline, 2 for a V. */
    public int bankCount() {
        int count = 0;
        for (int bank : banks) {
            count = Math.max(count, bank + 1);
        }
        return count;
    }

    /**
     * How unevenly a bank fires, as the spread of its firing intervals in crank degrees.
     *
     * <p>Zero for every even-firing bank; 180 for the cross-plane V8, whose bank intervals run
     * {@code 90, 180, 180, 270}. It is a measurement over {@link #bankOf} rather than a number
     * anybody authored, so it cannot disagree with the pulse train the synthesiser actually builds —
     * which is what makes it worth asserting on.
     */
    public double bankFiringSpreadDegrees() {
        double[] angles = firingAngles();
        double worst = 0.0;
        for (int bank = 0; bank < bankCount(); bank++) {
            double min = CYCLE_DEGREES;
            double max = 0.0;
            double previous = Double.NaN;
            double first = Double.NaN;
            for (int i = 0; i < cylinders; i++) {
                if (bankOf(i) != bank) {
                    continue;
                }
                if (Double.isNaN(first)) {
                    first = angles[i];
                } else {
                    double interval = angles[i] - previous;
                    min = Math.min(min, interval);
                    max = Math.max(max, interval);
                }
                previous = angles[i];
            }
            // The wrap-around interval closes the cycle. Leaving it out would call a bank even that
            // fires three times in quick succession and then waits half a revolution.
            double wrap = CYCLE_DEGREES - previous + first;
            min = Math.min(min, wrap);
            max = Math.max(max, wrap);
            worst = Math.max(worst, max - min);
        }
        return worst;
    }

    /**
     * How many engine cycles happen per second: {@code rpm / 120}.
     *
     * <p>This, not {@link #firingHzAt}, is the true period of the sound a pulse-train engine makes.
     * An uneven bank pattern repeats once per 720°, so a loop that held a whole number of *firing*
     * intervals but a fractional number of *cycles* would splice a bank's pulse train into the
     * middle of its own pattern.
     */
    public static double cycleHzAt(float rpm) {
        return rpm / 120.0;
    }

    /**
     * The firing frequency at an engine speed, in Hz.
     *
     * <p>Four-stroke: each cylinder fires once every two crank revolutions, so the rate is
     * {@code cylinders × rpm / 120}. This is the number the whole engine sound hangs off — pitch it
     * and the engine revs, and no sample-rate trick or filter can substitute for getting it right.
     */
    public float firingHzAt(float rpm) {
        return cylinders * rpm / 120f;
    }

    /** The asset-id token, e.g. {@code v8}. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
