/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.audio;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;

/**
 * One engine, synthesised as it runs (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37a3).
 *
 * <p><b>This replaces a loop and a pitch shift, and the reason is not fidelity for its own sake.</b>
 * A loop can only ever be the engine it was recorded as, played faster or slower. It cannot drop a
 * cylinder, it cannot lose its exhaust, and it cannot start or stop — those had to be three more
 * files, pitched from a nominal idle that no car actually has. Everything a damaged engine does is
 * a change to <em>when</em> the cylinders fire and <em>what happens to the pulse afterwards</em>,
 * and neither survives resampling.
 *
 * <p><b>The structure is the physical one.</b> The crank angle integrates forward at whatever rpm
 * the simulation reports; a cylinder fires when its angle comes round; each firing lays a unipolar
 * blowdown pulse into its own bank's excitation; each bank runs through the exhaust; the two banks
 * are summed with a small delay and detune between them. Nothing here knows the future, so it can
 * be driven a block at a time from an audio callback.
 *
 * <p><b>The exhaust colours the pulse train; it does not replace it.</b> This is the correction that
 * motivated the rewrite (DISC-025). The previous synthesiser summed three band-pass filters and
 * emitted only their output, which is a 25 dB gate on everything between them — and a V8's firing
 * frequency at 4,000 rpm falls in the gap between the 105 Hz and 560 Hz formants. Measured on the
 * shipped file, the loudest component of {@code engine_loop_v8.wav} was 100 Hz against a firing
 * frequency of 266.7 Hz, and four of the six loops sounded at a pitch unrelated to their engine.
 * Here the dry pulse train is the signal and the resonances are added on top of it, which is what a
 * pipe does to a pressure wave.
 *
 * <p><b>Not thread-safe, and single-owner by construction.</b> One instance belongs to one
 * {@link EngineMixer} voice slot and is touched only by the audio thread. State arrives through the
 * immutable {@link State} handed to {@link #render}.
 */
public final class EngineSynth {

    /** Output rate. Matches the rest of the bank, so nothing resamples. */
    public static final int SAMPLE_RATE_HZ = 48_000;

    /** Degrees of crank in one four-stroke cycle. Every firing angle is measured against this. */
    private static final double CYCLE_DEGREES = EngineConfiguration.CYCLE_DEGREES;

    // ---- Arrangement -----------------------------------------------------------------------

    private final EngineConfiguration configuration;
    private final Induction induction;
    private final int cylinders;
    private final int bankCount;
    private final double roughness;
    private final double[] firingAngles;

    /**
     * Fixed per-cylinder trim in level and in crank degrees.
     *
     * <p><b>No two cylinders on a real engine are the same, and the difference is not noise.</b>
     * Measured against a Ford Mustang GT startup, this synthesiser's even orders sat 14 to 24 dB
     * below the real car's while its odd orders matched within a few dB — because two banks that are
     * exact time-reverses of each other cancel their even content almost perfectly, and nothing here
     * broke that symmetry. Cycle-to-cycle randomness could not: random jitter spreads energy across
     * the spectrum as noise, where a *fixed* difference between cylinders puts it back into the
     * orders, which is where the real engine has it.
     *
     * <p>Derived from the vehicle seed, so it is also what makes two cars of the same model sound
     * like two cars rather than one recording played twice.
     */
    private final double[] cylinderLevel;

    private final double[] cylinderTimingDeg;

    /**
     * How far cylinders differ in charge, and in crank degrees of timing scatter.
     *
     * <p>Deliberately near what an engine is actually built to rather than what would flatter one
     * measurement. Pushed to ±20% and ±3.2° the V8 matched a real Mustang's even orders exactly —
     * and every other arrangement became just as lumpy, which collapsed the contrast that makes a
     * cross-plane V8 recognisable. Cylinder scatter is the wrong lever for a V engine's even-order
     * content; {@link #BANK_GAIN_IMBALANCE} is the right one.
     */
    private static final double CYLINDER_LEVEL_SPREAD = 0.12;

    private static final double CYLINDER_TIMING_SPREAD_DEG = 2.0;

    // ---- Exhaust ---------------------------------------------------------------------------

    /**
     * The exhaust's resonances for a reference eight-cylinder engine, in Hz.
     *
     * <p>Applied as boosts over a dry path rather than as the only path. The frequencies themselves
     * were never the problem — 105 Hz is a plausible primary pipe resonance — so they are unchanged
     * from the bank they replace. The {@code Q} values are lower, because a resonance a listener
     * hears as the character of a pipe is broad, and one at {@code Q=8} is a sine oscillator that
     * happens to be excited by an engine.
     */
    private static final double[] FORMANT_HZ = {105.0, 560.0, 1750.0};

    private static final double[] FORMANT_Q = {2.5, 1.8, 1.2};
    private static final double[] FORMANT_GAIN = {1.30, 0.85, 0.40};

    /**
     * Muffler corner with the system intact, and with it torn completely open.
     *
     * <p><b>Both were far too low, and it took real recordings to see it</b> (DISC-026). The first
     * cut cascaded two low-passes at 640 Hz and 1,408 Hz, which is 24 dB per octave above the
     * second corner. Measured against forty CC-licensed engine recordings, a real exhaust's
     * harmonics fall at about 7 dB per octave between 100 Hz and 4 kHz; this synthesiser fell at
     * 22. Everything above about 800 Hz was being thrown away, which is why it read as muffled
     * rather than as loud.
     */
    private static final double MUFFLER_HZ_INTACT = 2600.0;

    private static final double MUFFLER_HZ_BREACHED = 6000.0;

    /**
     * Turbulent flow noise in the pipe, as a fraction of the exhaust's own level.
     *
     * <p>Continuous rather than per-pulse, which is the point: the pulses already carry port
     * turbulence scaled by their own envelope, and that produces a signal whose harmonics stand
     * nearly 37 dB above everything between them. Real engines measure about 9 to 17 dB, and the
     * gap is what makes a synthesised engine sound synthesised — too clean, not too dark. Gas is
     * still moving through a pipe between blowdowns, and this is that.
     */
    private static final double FLOW_NOISE = 0.90;

    /**
     * The low shelf a big engine gets on the throttle, and where it sits.
     *
     * <p><b>Power has to be audible as weight, not only as volume.</b> A 608 kW V8 does not sound
     * like a 110 kW four played louder: it displaces far more gas per pulse, and that energy is
     * low. The shelf is scaled by {@code sqrt(power)} and by load together, so it swells when the
     * driver is actually on it and falls away on a lift — which is the half a listener feels rather
     * than hears, and it survives a small speaker where a volume difference does not.
     *
     * <p>Below the low formant deliberately. That resonance is the pipe and stays where it is; this
     * is the size of the engine breathing through it.
     */
    private static final double LOW_SHELF_HZ = 75.0;

    private static final double LOW_SHELF_MAX_GAIN = 2.80;

    /** Power at which the low shelf is at full lift. Matches {@code EngineVoice.REFERENCE_POWER_W}. */
    private static final double REFERENCE_POWER_W = 500_000.0;

    /**
     * Overrun crackle: unburnt charge lighting off in a hot exhaust when the throttle shuts.
     *
     * <p>The deleted {@code engine_overrun_*} files carried these and nothing replaced them.
     * Dropping the load reproduces the overrun's thin, hollow note but not the bangs, and the bangs
     * are the part anybody actually notices. Armed by the transition rather than by the state: a
     * car that coasts at zero load for ten seconds pops for the first second and then just coasts.
     */
    private static final double CRACKLE_DECAY_SECONDS = 1.30;

    /** Cracks per second at full intensity, and the rev fraction below which a lift is too lazy. */
    private static final double CRACKLE_RATE_HZ = 26.0;

    private static final double CRACKLE_MIN_REV_FRACTION = 0.30;

    /**
     * How loud a bang is.
     *
     * <p>High, and it needs to be. The first cut let the crackle out at roughly the amplitude of the
     * band-passed noise that makes it, which put it 0 dB above a coasting exhaust — measured, it was
     * inaudible. An overrun pop is a detonation in a pipe; it is supposed to be the loudest thing
     * the car does apart from a collision.
     */
    private static final double CRACKLE_GAIN = 4.0;

    /** Where a breached exhaust's rasp sits, and how much of it there is at full breach. */
    private static final double RASP_HZ = 2400.0;

    private static final double RASP_GAIN_MAX = 0.55;

    private final Biquad[][] formant;
    private final Biquad muffler1 = new Biquad();
    private final Biquad muffler2 = new Biquad();
    private final Biquad dcBlock = new Biquad();
    private final Biquad rasp = new Biquad();
    private final Biquad flow = new Biquad();
    private final Biquad lowShelf = new Biquad();
    private final Biquad crackleFilter = new Biquad();

    /** How much crackle is left to come, and the transient currently sounding. */
    private double crackleArmed;

    private double crackleEnvelope;
    private double crackleDecay;
    private double previousLoad;

    // ---- Pulse scheduling --------------------------------------------------------------------

    /**
     * Excitation ring per bank, in samples.
     *
     * <p>Must hold one whole blowdown pulse at the slowest speed the engine ever turns. A starter
     * spins at about 250 rpm, where a V8's pulse is 20 ms — 980 samples — so 4,096 is comfortable
     * for every arrangement and costs 32 kB a voice.
     */
    private static final int RING = 4096;

    private final double[][] ring;
    private int ringPos;
    private double crankDeg;
    private long event;

    // ---- Bank divergence ------------------------------------------------------------------

    /**
     * The most a second bank's manifold detunes and lags the first's.
     *
     * <p>Carried over unchanged, along with the reasoning in DEC-054: a cross-plane V8's bank
     * patterns are time-reverses, so summed perfectly their odd orders cancel and the burble
     * disappears. What stops them cancelling is the two banks reaching the listener differently,
     * which is why divergence scales with how unevenly a bank fires rather than being a constant.
     */
    private static final double BANK_DETUNE_MAX = 0.10;

    private static final double BANK_DELAY_MAX_SECONDS = 0.0007;
    private static final double BANK_DIVERGENCE_MIN = 0.10;

    /**
     * How much quieter a V's second bank reaches the listener than its first.
     *
     * <p><b>Where a V engine's even orders come from.</b> Two banks that are exact time-reverses of
     * one another cancel their even content when summed, and measured against a real Mustang GT this
     * synthesiser's even orders sat 14 to 24 dB low because nothing broke that symmetry. Real banks
     * are not summed equally: one manifold is longer, one is further from the listener, and one
     * silencer is not the other. An imbalance breaks the cancellation without touching an inline
     * engine, which has only one bank, and without making every arrangement lumpy the way cylinder
     * scatter does.
     *
     * <p><b>Scaled by {@link #bankDivergence}, and DEC-054 is why.</b> Applied flat to every V it
     * repeated that entry's original mistake exactly: the even-firing V10 came out burbling harder
     * than the cross-plane V8 at 2,000 rpm, because banks that fire identically should stay matched
     * and a constant does not know that. Every asymmetry between banks in this synthesiser has to
     * scale with how differently they fire.
     */
    private static final double BANK_GAIN_IMBALANCE_MAX = 0.22;

    private static final int BANK_DELAY_SAMPLES_MAX = 128;

    private final double[] bankDelayLine = new double[BANK_DELAY_SAMPLES_MAX];
    private int bankDelayPos;
    private final int bankDelaySamples;
    private final double bankGainImbalance;

    // ---- Induction ---------------------------------------------------------------------------

    /**
     * How loud the induction voice is at full song.
     *
     * <p><b>Low, and it has to be.</b> The induction voice is a stack of sines and the exhaust is a
     * broadband pulse train; matched for energy the sine sits well above it perceptually, and the
     * blower becomes the car. A GTD's whine is a layer over the V8, not the V8.
     */
    private static final double INDUCTION_GAIN = 0.11;

    private double inductionPhase;
    private final Biquad inductionAir = new Biquad();

    /** Blow-off: a triggered decay rather than a one-shot file, so it belongs to this engine. */
    private double releaseEnvelope;

    private final Biquad releaseFilter = new Biquad();
    private static final double RELEASE_DECAY_SECONDS = 0.42;

    // ---- Starter ------------------------------------------------------------------------------

    /**
     * How fast the starter fades in and out, in seconds.
     *
     * <p><b>A starter engages; it does not switch on.</b> Without this the gear whine began at full
     * amplitude on the first sample, which is a step function — and a step into a near-sine reads
     * as a beep before it reads as a motor. It was clearly audible at the head of every rendered
     * sample and 25 dB above the body of the file.
     */
    private static final double STARTER_FADE_SECONDS = 0.07;

    /**
     * How much of the gear whine is tone rather than filtered noise.
     *
     * <p>Low on purpose. A starter's whine is a gear mesh under load, which is broadband with a
     * pitch in it, and the first cut modelled it as a bare 1,160 Hz sinusoid. Two of those in a
     * scene phase-lock into something that sounds like a reversing alarm.
     */
    private static final double STARTER_TONE_FRACTION = 0.30;

    /**
     * Ring-gear teeth: the whine is the pinion meshing this many times per crank revolution.
     *
     * <p><b>The whine is geared to the crank, so it must follow the crank.</b> Holding it at a fixed
     * frequency was the other half of why a start sounded like a fault — the engine's speed swung
     * 26% on every compression while the whine sat perfectly still above it, which is a combination
     * no machine makes. Tied to rpm it dips and recovers with the labour, and the two together are
     * what a listener recognises as an engine being turned over.
     */
    private static final double STARTER_RING_TEETH = 130.0;

    private double starterPhase;
    private double starterGearPhase;
    private double starterEnvelope;

    /** Per-vehicle variation on the ring gear, so two cars cranking together do not phase-lock. */
    private final double starterGearScale;

    private final Biquad starterGear = new Biquad();

    private long rng;

    /** The car's own rev range, so the induction curve is scaled to this engine and not a nominal one. */
    private final double idleRpm;

    private final double redlineRpm;

    /** How big this engine sounds, in {@code [0,1]}: {@code sqrt(power / REFERENCE_POWER_W)}. */
    private final double weight;

    /**
     * @param configuration which arrangement to be; supplies the firing angles and the bank split
     * @param induction the forced-induction device, or {@link Induction#NATURALLY_ASPIRATED}
     * @param idleRpm the car's idle speed
     * @param redlineRpm the car's rev limit
     * @param peakPowerW the engine's peak power, which decides how much low end it gets on throttle
     * @param seed the per-vehicle noise stream, so two identical cars are not sample-identical
     */
    public EngineSynth(
            EngineConfiguration configuration,
            Induction induction,
            float idleRpm,
            float redlineRpm,
            float peakPowerW,
            long seed) {
        this.configuration = configuration == null ? EngineConfiguration.V6 : configuration;
        this.induction = induction == null ? Induction.NATURALLY_ASPIRATED : induction;
        this.idleRpm = Math.max(200.0, idleRpm);
        this.redlineRpm = Math.max(this.idleRpm + 500.0, redlineRpm);
        this.weight = Math.sqrt(clamp01(Math.max(1f, peakPowerW) / REFERENCE_POWER_W));
        this.cylinders = this.configuration.cylinders();
        this.bankCount = this.configuration.bankCount();
        this.roughness = this.configuration.roughness();
        this.firingAngles = this.configuration.firingAngles();
        this.rng = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        this.ring = new double[bankCount][RING];

        double divergence = BANK_DIVERGENCE_MIN
                + (1.0 - BANK_DIVERGENCE_MIN) * Math.min(1.0, this.configuration.bankFiringSpreadDegrees() / 180.0);
        this.bankGainImbalance = BANK_GAIN_IMBALANCE_MAX * divergence;
        this.bankDelaySamples = Math.min(
                BANK_DELAY_SAMPLES_MAX - 1, (int) Math.round(BANK_DELAY_MAX_SECONDS * divergence * SAMPLE_RATE_HZ));
        double detune = 1.0 - BANK_DETUNE_MAX * divergence;

        double scale = Math.pow(8.0 / cylinders, 0.35);
        this.formant = new Biquad[bankCount][FORMANT_HZ.length];
        for (int bank = 0; bank < bankCount; bank++) {
            for (int k = 0; k < FORMANT_HZ.length; k++) {
                formant[bank][k] = new Biquad();
                formant[bank][k].bandPass(FORMANT_HZ[k] * scale * (bank == 0 ? 1.0 : detune), FORMANT_Q[k]);
            }
        }
        rasp.bandPass(RASP_HZ, 1.1);
        // A blower's rush is broad and sits under its tone; a turbo's is narrow and is the sound.
        boolean geared = this.induction.tonality() > 0.5;
        inductionAir.bandPass(geared ? 1800.0 : 5200.0, geared ? 1.2 : 2.4);
        // Broad and low-Q: pipe flow noise has no pitch, and giving it one would be a whistle.
        flow.bandPass(900.0, 0.5);
        // Bright and broad: an overrun bang is a sharp crack, not a thud.
        crackleFilter.bandPass(1900.0, 0.8);
        this.starterGearScale = 0.90 + nextUnit() * 0.20;

        this.cylinderLevel = new double[cylinders];
        this.cylinderTimingDeg = new double[cylinders];
        for (int i = 0; i < cylinders; i++) {
            cylinderLevel[i] = 1.0 + nextSample() * CYLINDER_LEVEL_SPREAD;
            cylinderTimingDeg[i] = nextSample() * CYLINDER_TIMING_SPREAD_DEG;
        }
        dcBlock.highPass(38.0, 0.707);
    }

    /**
     * Everything the voice needs to know about the car for the next block.
     *
     * <p>Immutable, so the simulation thread can publish one and the audio thread can read it
     * without a lock (DEC-055).
     *
     * @param rpm engine speed. Zero means a stopped engine, which is silent rather than idling
     * @param throttle driver demand in {@code [0,1]}
     * @param load how hard the engine is working in {@code [0,1]}. Separate from throttle because
     *     off-throttle overrun is load 0 at high rpm — the cylinders still pump but barely burn,
     *     and that is the whole difference between a pull and a lift
     * @param starter whether the starter motor is engaged
     * @param deadCylinder index in firing order of a cylinder that no longer fires, or {@code -1}
     * @param misfire probability per firing event of a partial burn, in {@code [0,1]}
     * @param exhaustBreach how far the exhaust is torn open, in {@code [0,1]}
     */
    public record State(
            float rpm,
            float throttle,
            float load,
            boolean starter,
            int deadCylinder,
            float misfire,
            float exhaustBreach) {

        /** An engine that is not running. */
        public static final State STOPPED = new State(0f, 0f, 0f, false, -1, 0f, 0f);

        public State {
            rpm = Math.max(0f, rpm);
            throttle = clamp01(throttle);
            load = clamp01(load);
            misfire = clamp01(misfire);
            exhaustBreach = clamp01(exhaustBreach);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : Math.min(v, 1f);
        }
    }

    /** Fires the blow-off. Called when the driver lifts on a device that has a release. */
    public void triggerRelease() {
        if (induction.hasRelease()) {
            releaseEnvelope = 1.0;
        }
    }

    /**
     * Fills {@code out[0..frames)} with this engine's next block of mono samples.
     *
     * <p>Adds nothing and allocates nothing: the caller owns the buffer and this overwrites it.
     */
    public void render(float[] out, int frames, State s) {
        if (s.rpm() < 1f && !s.starter() && releaseEnvelope <= 0.0 && crackleArmed <= 0.0 && crackleEnvelope <= 0.0) {
            java.util.Arrays.fill(out, 0, frames, 0f);
            return;
        }

        double degreesPerSample = s.rpm() * 6.0 / SAMPLE_RATE_HZ;
        double firingIntervalSeconds = s.rpm() > 1f ? (120.0 / s.rpm()) / cylinders : 0.0;
        // Blowdown lasts about a third of a firing interval, which is why a V12's pulses are short
        // and overlapping and an I4's are separate bangs.
        double pulseSeconds = 0.34 * firingIntervalSeconds;
        // Off the throttle a cylinder still fills and pumps but burns almost nothing, so the train
        // drops to a fraction of its height rather than stopping.
        double burn = 0.18 + 0.82 * s.load();

        double mufflerHz = mufflerHz(s.exhaustBreach());
        muffler1.lowPass(mufflerHz, 0.707);
        muffler2.lowPass(mufflerHz * 4.0, 0.707);
        double raspGain = RASP_GAIN_MAX * s.exhaustBreach();
        // Mass flow, roughly: how fast the engine is turning times how much it is drawing.
        double flowGain = FLOW_NOISE * Math.sqrt(clamp01(s.rpm() / redlineRpm)) * (0.35 + 0.65 * s.load());

        // Weight on the throttle: a big engine leaning on it, falling away the moment it lifts.
        lowShelf.lowShelf(LOW_SHELF_HZ, 1.0 + (LOW_SHELF_MAX_GAIN - 1.0) * weight * s.load());

        // A lift is a transition, so it is detected as one. Arming on the state instead would have
        // a coasting car popping forever.
        double revFraction = clamp01((s.rpm() - idleRpm) / (redlineRpm - idleRpm));
        if (previousLoad - s.load() > 0.35 && revFraction >= CRACKLE_MIN_REV_FRACTION) {
            crackleArmed = Math.max(crackleArmed, revFraction * (0.55 + 0.45 * s.exhaustBreach()));
        }
        previousLoad = s.load();
        double crackleRelease = Math.exp(-1.0 / (CRACKLE_DECAY_SECONDS * SAMPLE_RATE_HZ));
        double crackleChance = CRACKLE_RATE_HZ / SAMPLE_RATE_HZ;

        if (s.starter()) {
            starterGear.bandPass(Math.max(40.0, s.rpm() / 60.0 * STARTER_RING_TEETH * starterGearScale), 5.0);
        }

        double inductionHz = s.rpm() / 60.0 * induction.driveRatio();
        double inductionGain = inductionGain(s);
        double releaseDecay = Math.exp(-1.0 / (RELEASE_DECAY_SECONDS * SAMPLE_RATE_HZ) * 6.0);

        for (int n = 0; n < frames; n++) {
            if (s.rpm() > 1f) {
                crankDeg += degreesPerSample;
                while (crankDeg >= nextFiringAngle()) {
                    fire((int) (event % cylinders), pulseSeconds, burn, s);
                    event++;
                }
            }

            double sample = 0.0;
            for (int bank = 0; bank < bankCount; bank++) {
                double excitation = ring[bank][ringPos];
                ring[bank][ringPos] = 0.0;
                double coloured = excitation;
                for (int k = 0; k < FORMANT_HZ.length; k++) {
                    coloured += FORMANT_GAIN[k] * formant[bank][k].process(excitation);
                }
                double weighted = coloured * (bank == 0 ? 1.0 : 1.0 - bankGainImbalance);
                sample += (bank == 0 ? weighted : bankDelay(weighted)) / bankCount;
            }
            ringPos = ringPos + 1 == RING ? 0 : ringPos + 1;

            // Flow noise joins the excitation, so it is shaped by the same exhaust the pulses are
            // — it is gas in the pipe, not hiss added to the output.
            double exhaust = muffler2.process(muffler1.process(sample + flow.process(nextSample()) * flowGain));
            if (raspGain > 0.0) {
                exhaust += rasp.process(sample) * raspGain;
            }
            double result = lowShelf.process(dcBlock.process(exhaust));

            if (crackleArmed > 1e-3) {
                if (nextUnit() < crackleChance * crackleArmed) {
                    crackleEnvelope = crackleArmed * (0.35 + 0.65 * nextUnit());
                    // Each bang is its own length; a uniform decay reads as a machine gun.
                    crackleDecay = Math.exp(-1.0 / ((0.012 + 0.030 * nextUnit()) * SAMPLE_RATE_HZ));
                }
                crackleArmed *= crackleRelease;
            }
            if (crackleEnvelope > 1e-4) {
                result += crackleFilter.process(nextSample()) * crackleEnvelope * CRACKLE_GAIN;
                crackleEnvelope *= crackleDecay;
            }

            if (inductionGain > 0.0) {
                result += induction(inductionHz) * inductionGain;
            }
            if (releaseEnvelope > 1e-4) {
                result += releaseFilter.process(nextSample()) * releaseEnvelope * 0.5;
                releaseEnvelope *= releaseDecay;
            }
            result += starter(s.starter(), s.rpm());
            out[n] = (float) result;
        }
    }

    /** Where the crank has to reach for the next cylinder in the order to fire. */
    private double nextFiringAngle() {
        int cylinder = (int) (event % cylinders);
        return firingAngles[cylinder] + cylinderTimingDeg[cylinder] + CYCLE_DEGREES * (event / cylinders);
    }

    private double mufflerHz(double breach) {
        // Squared, because a small hole in a muffler changes very little and a missing one changes
        // everything; the ear tracks the open area rather than the damage fraction.
        return MUFFLER_HZ_INTACT + (MUFFLER_HZ_BREACHED - MUFFLER_HZ_INTACT) * breach * breach;
    }

    /** Lays one exhaust blowdown into its bank's ring, starting at the current read position. */
    private void fire(int cylinder, double pulseSeconds, double burn, State s) {
        double level = burn * cylinderLevel[cylinder];
        if (cylinder == s.deadCylinder()) {
            // A dead cylinder still pumps air past an open valve. Quiet and dull, and audible as
            // the hole in the beat rather than as silence.
            level *= 0.10;
        } else if (s.misfire() > 0f && nextUnit() < s.misfire()) {
            level *= 0.25;
        }
        if (level <= 0.001) {
            return;
        }
        int span = Math.min(RING - 1, Math.max(2, (int) Math.round(pulseSeconds * SAMPLE_RATE_HZ)));
        double peak = level * (1.0 + nextSample() * roughness * 0.30);
        double rise = 0.18;
        double[] target = ring[configuration.bankOf(cylinder)];
        for (int i = 0; i < span; i++) {
            double u = (double) i / span;
            double envelope = u < rise ? u / rise : Math.exp(-(u - rise) / (1.0 - rise) * 3.6);
            // Turbulence in the port, scaled by the envelope so it belongs to the pulse rather
            // than sitting under the whole engine as a hiss.
            double turbulence = 1.0 + nextSample() * roughness * 0.45;
            int at = ringPos + i;
            target[at >= RING ? at - RING : at] += peak * envelope * turbulence;
        }
    }

    private double bankDelay(double value) {
        int read = bankDelayPos - bankDelaySamples;
        double out = bankDelayLine[read < 0 ? read + BANK_DELAY_SAMPLES_MAX : read];
        bankDelayLine[bankDelayPos] = value;
        bankDelayPos = bankDelayPos + 1 == BANK_DELAY_SAMPLES_MAX ? 0 : bankDelayPos + 1;
        return out;
    }

    /**
     * How loud the induction voice is.
     *
     * <p>A supercharger is geared to the crank, so it whines whenever the engine turns. A turbo is
     * spun by exhaust flow, so it needs revs <em>and</em> load, and multiplying them is what makes
     * the hole a turbo car has off boost. Neither makes a sound when the crank is not turning,
     * which is what the rpm gate is for — the air term is noise and does not care that the
     * fundamental went to zero.
     */
    private double inductionGain(State s) {
        if (!induction.isForced() || s.rpm() < 1f) {
            return 0.0;
        }
        double revs = clamp01((s.rpm() - idleRpm) / (redlineRpm - idleRpm));
        double base = induction.tonality() > 0.5
                ? (0.28 + 0.72 * revs) * (0.72 + 0.28 * s.throttle())
                : revs * revs * s.throttle();
        return INDUCTION_GAIN * base;
    }

    private double induction(double fundamentalHz) {
        inductionPhase += 2.0 * Math.PI * fundamentalHz / SAMPLE_RATE_HZ;
        if (inductionPhase > 2.0 * Math.PI) {
            inductionPhase -= 2.0 * Math.PI;
        }
        double tone = 0.0;
        int partials = induction.tonality() > 0.5 ? 4 : 2;
        for (int p = 1; p <= partials; p++) {
            if (fundamentalHz * p >= SAMPLE_RATE_HZ * 0.45) {
                break;
            }
            // A screw blower's harmonics fall slowly, which is what makes the whine cut through an
            // exhaust note rather than sitting behind it.
            tone += Math.sin(inductionPhase * p) / Math.pow(p, 0.7);
        }
        double air = inductionAir.process(nextSample());
        return tone * induction.tonality() + air * (1.0 - induction.tonality()) * 1.8;
    }

    /**
     * A starter motor: a low armature whirr with a gear whine riding on it.
     *
     * @param engaged whether the starter is turning this sample; the envelope does the rest
     */
    private double starter(boolean engaged, double rpm) {
        double target = engaged ? 1.0 : 0.0;
        starterEnvelope += (target - starterEnvelope) / (STARTER_FADE_SECONDS * SAMPLE_RATE_HZ);
        if (starterEnvelope < 1e-4) {
            return 0.0;
        }
        // Both follow the crank: the pinion through the ring gear, the armature through the drive.
        double gearHz = Math.max(40.0, rpm / 60.0 * STARTER_RING_TEETH * starterGearScale);
        starterPhase += 2.0 * Math.PI * (gearHz / 6.4) / SAMPLE_RATE_HZ;
        starterGearPhase += 2.0 * Math.PI * gearHz / SAMPLE_RATE_HZ;
        double whine = starterGear.process(nextSample()) * (1.0 - STARTER_TONE_FRACTION)
                + Math.sin(starterGearPhase) * STARTER_TONE_FRACTION;
        double armature = Math.sin(starterPhase) * 0.34;
        return (armature + whine * 0.42 + nextSample() * 0.22) * 0.20 * starterEnvelope;
    }

    // ---- Deterministic noise -------------------------------------------------------------------

    private double nextUnit() {
        rng ^= rng << 13;
        rng ^= rng >>> 7;
        rng ^= rng << 17;
        return ((rng >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private double nextSample() {
        return nextUnit() * 2.0 - 1.0;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /**
     * Direct-form-II transposed biquad — the only filter this needs.
     *
     * <p>Coefficients are recomputed only when they change. The muffler moves as an exhaust is torn
     * open and on the overwhelming majority of blocks nothing has, so the trigonometry runs a
     * handful of times a second rather than once a block per filter.
     */
    static final class Biquad {

        private double b0 = 1;
        private double b1;
        private double b2;
        private double a1;
        private double a2;
        private double z1;
        private double z2;
        private double lastHz = -1;
        private double lastQ = -1;
        private double shelfGain = -1;
        private int lastKind = -1;

        void bandPass(double hz, double q) {
            set(0, hz, q);
        }

        void lowPass(double hz, double q) {
            set(1, hz, q);
        }

        void highPass(double hz, double q) {
            set(2, hz, q);
        }

        /** Low shelf. {@code gain} is linear, so 2.0 lifts everything below {@code hz} by 6 dB. */
        void lowShelf(double hz, double gain) {
            if (gain == shelfGain && 3 == lastKind && hz == lastHz) {
                return;
            }
            shelfGain = gain;
            lastKind = 3;
            lastHz = hz;
            lastQ = -1;
            double a = Math.sqrt(Math.max(1e-6, gain));
            double omega = 2.0 * Math.PI * Math.min(hz, SAMPLE_RATE_HZ * 0.45) / SAMPLE_RATE_HZ;
            double cos = Math.cos(omega);
            // RBJ shelf at slope 1, where the alpha term collapses to sin(omega)/sqrt(2). The
            // gentlest shelf there is, which is what "more weight" wants rather than a corner the
            // ear can locate as an effect.
            double alpha = Math.sin(omega) / Math.sqrt(2.0);
            double twoSqrtAAlpha = 2.0 * Math.sqrt(a) * alpha;
            double a0 = (a + 1.0) + (a - 1.0) * cos + twoSqrtAAlpha;
            b0 = a * ((a + 1.0) - (a - 1.0) * cos + twoSqrtAAlpha) / a0;
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos) / a0;
            b2 = a * ((a + 1.0) - (a - 1.0) * cos - twoSqrtAAlpha) / a0;
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cos) / a0;
            a2 = ((a + 1.0) + (a - 1.0) * cos - twoSqrtAAlpha) / a0;
        }

        private void set(int kind, double hz, double q) {
            if (kind == lastKind && hz == lastHz && q == lastQ) {
                return;
            }
            lastKind = kind;
            lastHz = hz;
            lastQ = q;
            double omega = 2.0 * Math.PI * Math.min(hz, SAMPLE_RATE_HZ * 0.45) / SAMPLE_RATE_HZ;
            double alpha = Math.sin(omega) / (2.0 * Math.max(0.05, q));
            double cos = Math.cos(omega);
            double a0 = 1.0 + alpha;
            switch (kind) {
                case 0 -> {
                    b0 = alpha / a0;
                    b1 = 0.0;
                    b2 = -alpha / a0;
                }
                case 1 -> {
                    b0 = (1.0 - cos) / 2.0 / a0;
                    b1 = (1.0 - cos) / a0;
                    b2 = b0;
                }
                default -> {
                    b0 = (1.0 + cos) / 2.0 / a0;
                    b1 = -(1.0 + cos) / a0;
                    b2 = b0;
                }
            }
            a1 = -2.0 * cos / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double process(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
