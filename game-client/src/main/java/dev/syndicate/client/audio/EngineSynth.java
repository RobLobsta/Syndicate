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
    private static final double CRACKLE_DECAY_SECONDS = 0.85;

    /** The rev fraction below which a lift is too lazy to have left anything unburnt. */
    private static final double CRACKLE_MIN_REV_FRACTION = 0.30;

    /**
     * The chance that any one exhaust event lights off, at full intensity.
     *
     * <p><b>A rate in hertz was the wrong shape and it sounded like it.</b> The first cut fired
     * bangs from a free-running 26 Hz clock, which is exactly the fault R38a5 exists to prevent —
     * a periodic component of an engine that is not tied to the crank. Measured on a rendered lift
     * it produced 11 to 14 evenly spaced pops a second on every engine regardless of arrangement or
     * engine speed, which reads as popcorn rather than as a car: the ear hears a steady stream of
     * identical clicks and correctly concludes that whatever is making them is not the engine.
     *
     * <p>Unburnt charge lights when an exhaust valve opens onto a hot pipe, so a pop is an
     * <em>exhaust event that went wrong</em> and its opportunities arrive at the firing rate. As a
     * per-event probability the rate rises with revs, differs between a four and a twelve, and dies
     * with them — all of which it should, and none of which a constant in hertz can do.
     */
    private static final double CRACKLE_PER_EVENT = 0.115;

    /**
     * How loud a bang is, and how much of it is a low thump rather than a crack.
     *
     * <p>Loud on purpose: an overrun pop is a detonation in a pipe. But the first cut was a single
     * band-pass at 1.9 kHz, which is a <em>tick</em> — all crack and no body — and a stream of ticks
     * is the other half of why this read as popcorn. A real bang shoves a slug of gas down the pipe
     * and thumps at the bottom of the exhaust's range before it cracks at the top.
     */
    private static final double CRACKLE_GAIN = 3.4;

    private static final double CRACKLE_THUMP_HZ = 240.0;

    private static final double CRACKLE_THUMP_SHARE = 0.62;

    /**
     * Level correction for the thump path.
     *
     * <p>A band-pass passes white noise in proportion to its bandwidth, so the narrow low filter
     * emits roughly a third of what the broad high one does from the same excitation. Without this
     * the thump share is a share of nothing: adding body to the bang made it quieter, and measured
     * on a lift the bangs stopped standing clear of the coast at all.
     */
    private static final double CRACKLE_THUMP_MAKEUP = 3.4;

    /**
     * How much likelier a bang is when the last event also banged.
     *
     * <p>Real crackle arrives in bursts and then stops, because one detonation leaves the pipe
     * hotter and the mixture behind it richer. Independent coin flips per event give a flat Poisson
     * stream instead, which is uniform by construction, and uniform is the definition of the sound
     * being complained about.
     */
    private static final double CRACKLE_CLUSTERING = 4.5;

    /**
     * The tailpipe: reflections inside the exhaust, as a short feedback network.
     *
     * <p><b>This is where the rumble was missing, and filters could never have supplied it.</b>
     * Measured against a real V8 idling, the reference modulates its loudness by 5 to 22% at every
     * order <em>below</em> the firing order and only 4.9% at the firing order itself — that spread
     * is what a listener calls the rumble or the lope. This synthesiser did the opposite: 33% at the
     * firing order and about 1% everywhere else, which is a machine pulsing evenly rather than an
     * engine.
     *
     * <p>The cause is that an exhaust is not a filter, it is a system of pipes and chambers with
     * multiple reflections, and a biquad's memory is a handful of samples. Real pulses arrive into a
     * cavity that is still ringing from the last three, so they partly fuse — which lowers the
     * firing-order modulation — while the cavity's memory carries the pattern of the whole 720°
     * cycle forward, which raises everything below it. A dry pulse train through a filter has no way
     * to do either.
     *
     * <p>Delays are the round trips a real system has: about 6, 11 and 20 ms for a manifold, a
     * silencer chamber and the run to the tip, scaled by engine size.
     */
    /** How much of the firing interval the blowdown itself occupies. */
    private static final double PULSE_WIDTH_FRACTION = 0.34;

    /** Decay of the blowdown across its own width, in e-folds. Low overlaps the next cylinder. */
    private static final double PULSE_DECAY = 3.6;

    /**
     * The exhaust stroke: the piston pushing the burnt charge out, in crank degrees.
     *
     * <p><b>This is what was missing, and it is why the engine pumped instead of rumbling.</b> A
     * cylinder's contribution to the pipe was modelled as the blowdown alone — a third of a firing
     * interval, violent, and then nothing. Measured that way this synthesiser modulated its own
     * loudness by 29% at the firing order where a real four manages 2.2% and a real V8 1.5%: eight
     * separable bangs a cycle rather than an engine.
     *
     * <p>Blowdown is only the first part of an exhaust event. Once cylinder pressure has equalised
     * the piston still has 180° of crank to sweep the rest of the charge out, and that flow is
     * smooth, low, and <em>four times longer than the blowdown</em>. Because 180° is a quarter of
     * the 720° cycle, however many cylinders an engine has, a quarter of them are always sweeping —
     * two on a V8, three on a V12. Their overlap is what fills the gaps between blowdowns, and what
     * is left modulating is no longer the firing rate but the differences between the cylinders
     * themselves, which repeat once per cycle. That is the rumble, and it is a sub-order by
     * construction.
     *
     * <p>Expressed in crank degrees rather than in seconds or in intervals, so it stays the same
     * stroke at every engine speed and on every arrangement.
     */
    private static final double SCAVENGE_DEGREES = 180.0;

    /** How loud the sweep is against the blowdown that precedes it. */
    private static final double SCAVENGE_LEVEL = 0.20;

    /**
     * The exhaust system as a diffuser: an all-pass chain, not a comb.
     *
     * <p><b>This is where the rumble was, and why every earlier attempt at it broke something
     * else.</b> Measured against a real Mustang GT idling, this synthesiser's sub-orders were
     * already right — 28.1% against the reference's 27.0% — and every order it had below the firing
     * order stood clear of the floor. What was wrong was one number: the modulation <em>at</em> the
     * firing order, 20.3% against the reference's 1.6%. A real engine's loudness barely moves
     * between one cylinder and the next; this one pumped once per firing, and a pump at 60 Hz is a
     * buzz where a lope is what the ear is listening for.
     *
     * <p>Only time-smearing fixes that: successive blowdowns have to overlap until the ripple
     * between them fills in, while the 5 to 30 Hz pattern of the whole cycle — which is the rumble —
     * passes through untouched because its period is ten times longer than the smear.
     *
     * <p><b>A feedback comb was tried first and cannot be used.</b> Driven hard enough to fuse the
     * pulses it does raise the rumble, and it also pins the spectrum: fixed delays have fixed peaks,
     * so the I4's spectral centroid stopped tracking engine speed (×1.8 against a ×2.0 bound) —
     * which is DISC-025's defect, reintroduced by a different mechanism. An all-pass has unity
     * magnitude at every frequency by construction. It <em>cannot</em> pin a spectrum, and that is
     * the whole reason it is the right component: it disperses in time and does nothing else.
     *
     * <p><b>Eight stages, and measured in firing intervals rather than in milliseconds.</b> Both
     * choices are corrections to a version that had neither. With four fixed delays the smear worked
     * at some engine speeds and inverted at others: an all-pass whose delay lands near the firing
     * period returns each echo on top of the <em>next</em> cylinder instead of into the gap before
     * it, and so reinforces the pumping it exists to remove. Measured, the V8's firing-order
     * modulation was 1.6% at 900 rpm and 16.8% at 1,100 — a tenfold swing across a fifth of the rev
     * range, which is one stage coming into alignment and nothing else. Eight delays cannot all
     * align at once, and expressing them as fractions of the firing interval means none of them ever
     * does: the whole system is scale-invariant in time, so the engine has the same character at
     * every speed, which is the one thing a real engine reliably does.
     *
     * <p>The fractions are irregular on purpose — no two in a simple ratio, and none near 1.0.
     *
     * <p><b>This is a model of pulse fusion, not a pipe.</b> A real exhaust is a fixed length and
     * its resonances do sit still; what scales with engine speed is the valve overlap, which is
     * fixed in crank degrees and therefore in firing intervals. Those resonances are already here,
     * as the formants, and they are the part that should not move.
     */
    private static final double[] DIFFUSER_FRACTIONS = {0.13, 0.21, 0.31, 0.44, 0.61, 0.83, 1.13, 1.47};

    /** Longest smear any stage may hold, in seconds. Sized for an idle no car actually has. */
    private static final double DIFFUSER_MAX_SECONDS = 0.085;

    /** How much of each stage is dispersed. Higher spreads a pulse over more of its own tail. */
    private static final double DIFFUSER_GAIN = 0.80;

    private static final double DIFFUSER_MIX = 1.0;

    /**
     * How much of the diffuser is left while the starter is turning the engine over.
     *
     * <p>A cranking engine is not heard through its exhaust. There is no combustion, gas velocity is
     * a fraction of even an idle's, and what reaches a bystander is the starter and the block —
     * measured on a recording of an engine failing to catch, 72% of the energy sits above 1.3 kHz,
     * which is not a tailpipe. Left at full the smear ran to half a compression stroke and washed
     * the chuff out: the four's crank measured 4.0 Hz against its true 7.5, and a start that does
     * not chug is the fault sound this synthesiser was rewritten to stop making.
     */
    private static final double DIFFUSER_CRANK_MIX = 0.20;

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

    /** One delay line per diffuser stage, each long enough for the slowest speed it will see. */
    private final double[][] diffuser = new double[DIFFUSER_FRACTIONS.length][];

    private final int[] diffuserPos = new int[DIFFUSER_FRACTIONS.length];

    /** Current delay of each stage in samples, chased towards the target so a rev does not click. */
    private final int[] diffuserDelay = new int[DIFFUSER_FRACTIONS.length];

    /** Chased rather than switched, so the engine catching is not also a step in the exhaust. */
    private double diffuserMix = DIFFUSER_MIX;

    private final Biquad crackleFilter = new Biquad();
    private final Biquad crackleThump = new Biquad();

    /** How much crackle is left to come, and the transient currently sounding. */
    private double crackleArmed;

    private double crackleEnvelope;
    private double crackleDecay;
    private double previousLoad;

    /** Whether the last exhaust event banged, so the next is likelier to (CRACKLE_CLUSTERING). */
    private boolean crackleFollowing;

    // ---- Pulse scheduling --------------------------------------------------------------------

    /**
     * Excitation ring per bank, in samples.
     *
     * <p>Must hold one whole exhaust event at the slowest speed the engine ever turns, and the
     * event is now the 180° sweep rather than the blowdown. A starter spins at about 200 rpm, where
     * 180° of crank takes 150 ms — 7,200 samples — so 16,384 covers every arrangement down to
     * 90 rpm and costs 128 kB a bank.
     */
    private static final int RING = 16_384;

    private final double[][] ring;
    private int ringPos;
    private double crankDeg;
    private long event;

    // ---- Flywheel -----------------------------------------------------------------------------

    /**
     * How much the crank speeds up and slows down within one cycle, at idle.
     *
     * <p><b>This is the mechanism behind the part of a lope you feel rather than hear, and it was
     * missing entirely.</b> A crank does not turn at a constant speed. Each power stroke is an
     * impulse and the flywheel only partly smooths it, so an idling engine surges and drops a few
     * percent within every cycle — you can watch it on a tachometer needle. The crank angle here
     * integrated forward at exactly the rpm the simulation reported, which is an engine with an
     * infinite flywheel.
     *
     * <p><b>Why it lands where the ear wants it.</b> A flywheel is a low-pass on the torque
     * impulses: it cannot respond to the firing rate but it responds fully to the once-and-twice-
     * per-cycle pattern underneath. Measured against a real Mustang idling, that is exactly where
     * the reference puts its lope — orders 1 and 2 at 4.8% and 8.9% of its loudness, at 6 and 12 Hz
     * — while this synthesiser had 0.9% and 1.1% there and dumped everything into order 3. The
     * totals matched; the placement did not, and placement is the whole of "I cannot feel the
     * cycles". Nothing else in this file has a mechanism that prefers the low orders.
     *
     * <p>It also does the right thing for free when an engine is hurt: a cylinder that does not
     * fire does not kick, so the crank drops further before the next one catches it, which is the
     * limping idle of a car running on seven.
     */
    private static final double SPEED_RIPPLE_AT_IDLE = 0.45;

    /** The most the crank may deviate from its mean speed, either way. */
    private static final double SPEED_RIPPLE_LIMIT = 0.22;

    /**
     * Flywheel time constants, in engine cycles: how fast a kick arrives and how fast it bleeds off.
     *
     * <p>The pair makes a band-pass centred near the cycle rate, which is what a rotating mass with
     * a load on it is. Taking the difference of the two also makes the result zero-mean by
     * construction, so the engine's average speed is still exactly what the simulation asked for
     * and the firing frequencies stay accurate.
     */
    private static final double FLYWHEEL_FAST_CYCLES = 0.16;

    private static final double FLYWHEEL_SLOW_CYCLES = 1.30;

    /** Above this fraction of the rev range the flywheel has won and the ripple is gone. */
    private static final double SPEED_RIPPLE_FADE_REV_FRACTION = 0.45;

    private double flywheelFast;
    private double flywheelSlow;
    private double speedRipple;

    /**
     * The rocking couple: how hard the engine shakes on its mounts, once per crank revolution.
     *
     * <p><b>A cross-plane V8 has an inherent first-order rocking couple.</b> Its crank throws sit at
     * 90° and the reciprocating masses do not balance end-to-end, so the whole engine rocks about
     * its centre once per revolution — it is why these engines carry heavy counterweights and why
     * one at idle visibly shakes on its mounts. An engine that is moving radiates differently as it
     * moves, and a tailpipe that swings towards and away from a listener does the same.
     *
     * <p><b>This is where a V8's lope actually lives.</b> The reference Mustang's strongest lope
     * component is order 2 — once per revolution — at 8.9% and 16.3% across two idle windows, with
     * order 1 next. Nothing else in this synthesiser produces order 2 at all: the bank imbalance
     * lands on the odd orders and the flywheel on order 1, which is why this had 1.1% there and read
     * as a drone with no thump in it.
     *
     * <p>Locked to crank angle rather than run from an oscillator, so it is tied to the engine the
     * way R38a5 requires, and scaled by bank unevenness so an even-firing V does not inherit a shake
     * its crank does not have.
     */
    private static final double ROCKING_COUPLE_DEPTH = 0.26;

    /** How much further an engine rocks with one cylinder no longer contributing. */
    private static final double DEAD_CYLINDER_SHAKE = 0.60;

    /** Where in the revolution the shake peaks, in degrees. Per vehicle, so two cars differ. */
    private final double rockingPhaseDeg;

    private final double rockingDepth;

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
     * <p><b>Scaled by how unevenly the banks fire, and DEC-054 is why.</b> Applied flat to every V
     * it repeated that entry's original mistake exactly: the even-firing V10 came out burbling
     * harder than the cross-plane V8 at 2,000 rpm, because banks that fire identically should stay
     * matched and a constant does not know that.
     *
     * <p><b>With no floor under the scaling, unlike the detune and the delay.</b> Those two
     * decorrelate two banks without inventing structure, so a small amount of them belongs on every
     * V. A gain difference is not like that: two banks firing the <em>same</em> pattern at different
     * levels still sum to that same pattern, so an imbalance between them creates no sub-orders at
     * all — it only changes the total. The 10% floor was therefore giving the V6 and the V10 a lope
     * that has no physical source, and it capped the V8's: measured, the V6's odd sub-orders crossed
     * the −12 dB bound the moment the ceiling went past 0.22, which is a limit imposed by an
     * even-firing engine on the one arrangement that is supposed to be uneven.
     */
    private static final double BANK_GAIN_IMBALANCE_MAX = 0.45;

    private static final int BANK_DELAY_SAMPLES_MAX = 512;

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

    /**
     * How loud the starter is against the engine it is turning.
     *
     * <p>Raised with the crank length (DEV-006). A starter is a small motor, but it is also the only
     * thing making a sound at the moment it runs — there is no combustion to compete with it — and
     * at the previous level the whole ignition sat far enough under the running engine that a
     * listener reported no starting sound at all.
     */
    private static final double STARTER_LEVEL = 0.42;

    /**
     * How much louder the starter gets as the compression stroke drags it down.
     *
     * <p><b>The chuff belongs to the starter, not to the exhaust.</b> Until now the compression
     * labour existed only as a swing in crank speed, and the audible chuff was whatever that swing
     * happened to do to the spacing of the pulses — which is indirect, weak, and different on every
     * arrangement: measured across the six, the modulation at the compression rate ranged from 13%
     * down to 5%, and on some of them it was no stronger than the noise beside it.
     *
     * <p>A real starter is a series motor. Load it and it slows, draws more current and growls: the
     * sound gets louder and heavier at exactly the moment the crank slows. That puts the chuff in
     * the loudest thing happening — there is no combustion yet to compete with it — and makes it the
     * same event on every engine.
     */
    private static final double STARTER_LABOUR = 1.35;

    /** Smoothed crank speed, so the labour is measured against this engine's own cranking mean. */
    private double starterMeanRpm;

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

        double unevenness = Math.min(1.0, this.configuration.bankFiringSpreadDegrees() / 180.0);
        double divergence = BANK_DIVERGENCE_MIN + (1.0 - BANK_DIVERGENCE_MIN) * unevenness;
        this.bankGainImbalance = BANK_GAIN_IMBALANCE_MAX * unevenness;
        this.rockingDepth = ROCKING_COUPLE_DEPTH * unevenness;
        this.rockingPhaseDeg = nextUnit() * 360.0;
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
        crackleThump.bandPass(CRACKLE_THUMP_HZ, 0.8);
        this.starterGearScale = 0.90 + nextUnit() * 0.20;

        int longest = (int) Math.round(DIFFUSER_MAX_SECONDS * SAMPLE_RATE_HZ) + 2;
        for (int i = 0; i < DIFFUSER_FRACTIONS.length; i++) {
            diffuser[i] = new double[longest];
            diffuserDelay[i] = longest / 2;
        }

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
        double pulseSeconds = PULSE_WIDTH_FRACTION * firingIntervalSeconds;
        // And the sweep behind it lasts 180° of crank however fast the engine is turning.
        double scavengeSeconds = s.rpm() > 1f ? SCAVENGE_DEGREES / (s.rpm() * 6.0) : 0.0;
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

        if (s.starter()) {
            starterGear.bandPass(Math.max(40.0, s.rpm() / 60.0 * STARTER_RING_TEETH * starterGearScale), 5.0);
        }

        // The diffuser tracks the firing interval, so its smear is the same in crank degrees at
        // every speed. Chased a few samples a block rather than jumped: an all-pass whose delay
        // moves in one step is a click, and a car sweeping its rev range would tick continuously.
        double mixTarget = s.starter() ? DIFFUSER_CRANK_MIX : DIFFUSER_MIX;
        diffuserMix += (mixTarget - diffuserMix) * 0.06;
        if (firingIntervalSeconds > 0.0) {
            int limit = diffuser[0].length - 1;
            // Shrink the whole set together when the longest stage will not fit, rather than
            // clipping the long ones individually. Clipped, the last four stages collapse onto the
            // same delay and eight diffusers become one hard echo — which at cranking speed is a
            // ringing the engine does not have, and which was measurable as a false 4 Hz chuff.
            double longest = DIFFUSER_FRACTIONS[DIFFUSER_FRACTIONS.length - 1] * firingIntervalSeconds * SAMPLE_RATE_HZ;
            double fit = longest > limit ? limit / longest : 1.0;
            for (int i = 0; i < DIFFUSER_FRACTIONS.length; i++) {
                int want = (int) Math.round(DIFFUSER_FRACTIONS[i] * fit * firingIntervalSeconds * SAMPLE_RATE_HZ);
                want = Math.max(2, Math.min(limit, want));
                int step = Math.max(1, Math.abs(want - diffuserDelay[i]) / 8);
                diffuserDelay[i] += Integer.signum(want - diffuserDelay[i]) * step;
            }
        }

        double inductionHz = s.rpm() / 60.0 * induction.driveRatio();
        double inductionGain = inductionGain(s);
        double releaseDecay = Math.exp(-1.0 / (RELEASE_DECAY_SECONDS * SAMPLE_RATE_HZ) * 6.0);

        // The flywheel's two poles, in samples. Both are fixed in *cycles*, so the ripple stays the
        // same shape in crank degrees however fast the engine is turning.
        double cycleSamples = s.rpm() > 1f ? 120.0 / s.rpm() * SAMPLE_RATE_HZ : SAMPLE_RATE_HZ;
        double fastPole = Math.exp(-1.0 / (FLYWHEEL_FAST_CYCLES * cycleSamples));
        double slowPole = Math.exp(-1.0 / (FLYWHEEL_SLOW_CYCLES * cycleSamples));
        // A big flywheel at speed swallows the impulses; at idle it barely keeps up with them.
        // Normalised by the mean torque arriving per sample, so the ripple is a *fraction* of
        // engine speed rather than a number that depends on how many cylinders there are and how
        // long a cycle lasts. Without this the whole term came out at 1e-5 and did nothing.
        double meanTorquePerSample = cylinders * Math.max(0.05, burn) / cycleSamples;
        // A cranking engine has no power strokes to be kicked by, only compressions to drag over —
        // which EngineRunState already supplies as the crank labour.
        double shakes = s.starter() ? 0.0 : 1.0 - clamp01(revFraction / SPEED_RIPPLE_FADE_REV_FRACTION);
        double rippleGain = SPEED_RIPPLE_AT_IDLE * shakes / meanTorquePerSample;
        // The same fade for the rocking couple: a V8 shakes its mounts at idle and settles as the
        // revs rise, which is what anyone standing next to one has felt. A dead cylinder leaves the
        // engine more unbalanced, not less, so losing one deepens the shake rather than hiding it.
        double rocking = rockingDepth * shakes * (s.deadCylinder() >= 0 ? 1.0 + DEAD_CYLINDER_SHAKE : 1.0);

        for (int n = 0; n < frames; n++) {
            if (s.rpm() > 1f) {
                // The crank runs at the speed the flywheel actually allows, not the mean.
                crankDeg += degreesPerSample * (1.0 + speedRipple);
                while (crankDeg >= nextFiringAngle()) {
                    int cylinder = (int) (event % cylinders);
                    fire(cylinder, pulseSeconds, scavengeSeconds, burn, s);
                    // The power stroke's kick. A cylinder that fires weakly kicks weakly, so a
                    // misfiring engine limps without anybody writing a limp.
                    flywheelFast += cylinderLevel[cylinder] * burn * (cylinder == s.deadCylinder() ? 0.1 : 1.0);
                    flywheelSlow += cylinderLevel[cylinder] * burn * (cylinder == s.deadCylinder() ? 0.1 : 1.0);
                    // A pop is an exhaust event that lit off, so this is the only place one can
                    // start. Clustered, because one detonation makes the next likelier.
                    if (crackleArmed > 1e-3) {
                        double chance =
                                CRACKLE_PER_EVENT * crackleArmed * (crackleFollowing ? CRACKLE_CLUSTERING : 1.0);
                        crackleFollowing = nextUnit() < chance;
                        if (crackleFollowing) {
                            crackleEnvelope = crackleArmed * (0.45 + 0.55 * nextUnit());
                            // Each bang is its own length; a uniform decay reads as a machine gun.
                            crackleDecay = Math.exp(-1.0 / ((0.020 + 0.055 * nextUnit()) * SAMPLE_RATE_HZ));
                        }
                    }
                    event++;
                }
            }
            flywheelFast *= fastPole;
            flywheelSlow *= slowPole;
            // Zero-mean by construction: the two poles integrate the same impulses, so their
            // difference has no DC and the engine's average speed is untouched.
            // Clamped: a crank slows under compression, it does not stop or run backwards. Left
            // open, a four — whose four impulses a cycle are each twice the share an eight's are —
            // swung 64% peak to peak at idle, which is an engine about to stall rather than one
            // idling. An eight sits at 31%, which is what an instantaneous crank speed really does
            // at idle and is the reason misfire detection can be done by watching it.
            double raw = (flywheelFast * (1.0 - fastPole) - flywheelSlow * (1.0 - slowPole)) * rippleGain;
            speedRipple = Math.max(-SPEED_RIPPLE_LIMIT, Math.min(SPEED_RIPPLE_LIMIT, raw));

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
            // Through the system: four all-pass stages, which spread each blowdown over the
            // interval that follows it without touching where its energy sits in frequency. This
            // is what fuses eight bangs a cycle into one engine.
            double dispersed = exhaust;
            for (int i = 0; i < diffuser.length; i++) {
                double[] line = diffuser[i];
                int write = diffuserPos[i];
                int read = write - diffuserDelay[i];
                double delayed = line[read < 0 ? read + line.length : read];
                double v = dispersed + DIFFUSER_GAIN * delayed;
                line[write] = v;
                dispersed = delayed - DIFFUSER_GAIN * v;
                diffuserPos[i] = write + 1 == line.length ? 0 : write + 1;
            }
            exhaust += (dispersed - exhaust) * diffuserMix;

            // The engine rocking on its mounts, once every revolution of the crank. Applied to the
            // exhaust rather than to the excitation because it is the whole engine and its pipe
            // moving, not the combustion changing.
            if (rocking > 0.0) {
                double rev = crankDeg % 360.0;
                exhaust *= 1.0 + rocking * Math.cos((rev + rockingPhaseDeg) * Math.PI / 180.0);
            }

            double result = lowShelf.process(dcBlock.process(exhaust));

            if (crackleArmed > 1e-3) {
                crackleArmed *= crackleRelease;
            }
            if (crackleEnvelope > 1e-4) {
                double excitation = nextSample() * crackleEnvelope;
                // Body first, then the crack on top of it.
                result += (crackleThump.process(excitation) * CRACKLE_THUMP_SHARE * CRACKLE_THUMP_MAKEUP
                                + crackleFilter.process(excitation) * (1.0 - CRACKLE_THUMP_SHARE))
                        * CRACKLE_GAIN;
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

    /**
     * Lays one whole exhaust event into its bank's ring, starting at the current read position.
     *
     * <p>Two parts, because a cylinder empties in two. The <b>blowdown</b> is the valve cracking
     * open against combustion pressure: a third of a firing interval, a near-vertical attack, and
     * all of the harmonic content. The <b>sweep</b> behind it is the piston displacing what is left
     * over its 180° exhaust stroke — four times as long, half as loud, and smooth, with no edge for
     * the ear to hear as an event. The sweeps of a quarter of the engine's cylinders are always
     * overlapping, and that is what stops the output pumping at the firing rate.
     */
    private void fire(int cylinder, double pulseSeconds, double scavengeSeconds, double burn, State s) {
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
            double envelope = u < rise ? u / rise : Math.exp(-(u - rise) / (1.0 - rise) * PULSE_DECAY);
            // Turbulence in the port, scaled by the envelope so it belongs to the pulse rather
            // than sitting under the whole engine as a hiss.
            double turbulence = 1.0 + nextSample() * roughness * 0.45;
            int at = ringPos + i;
            target[at >= RING ? at - RING : at] += peak * envelope * turbulence;
        }
        int sweep = Math.min(RING - 1, Math.max(2, (int) Math.round(scavengeSeconds * SAMPLE_RATE_HZ)));
        double sweepPeak = level * SCAVENGE_LEVEL;
        for (int i = 0; i < sweep; i++) {
            // Piston velocity: zero at both dead centres, greatest in the middle of the stroke.
            // A half sine, so the sweep neither begins nor ends on a step.
            double flow = Math.sin(Math.PI * i / sweep);
            double turbulence = 1.0 + nextSample() * roughness * 0.45;
            int at = ringPos + i;
            target[at >= RING ? at - RING : at] += sweepPeak * flow * turbulence;
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
        // The motor labouring: heavier and louder exactly where the crank is being dragged down.
        starterMeanRpm += (rpm - starterMeanRpm) / (0.30 * SAMPLE_RATE_HZ);
        // Two-sided: the motor is heavier than usual under compression *and* lighter than usual as
        // the piston goes over the top. Half-wave — labouring or nothing — made the chuff sharp
        // enough that its second harmonic outgrew its fundamental, and a four whose chuff measures
        // at twice its compression rate is the R38a5 fault in a new costume.
        double labour = starterMeanRpm > 1.0
                ? Math.max(0.25, Math.min(2.6, 1.0 + STARTER_LABOUR * (starterMeanRpm / Math.max(1.0, rpm) - 1.0)))
                : 1.0;
        return (armature + whine * 0.42 + nextSample() * 0.22) * STARTER_LEVEL * starterEnvelope * labour;
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
