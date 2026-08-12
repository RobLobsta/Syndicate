/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;

/**
 * What one vehicle's engine sounds like, as numbers rather than as a recording
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37).
 *
 * <p><b>Two cars must not sound the same, and neither may cost an audio pass.</b> Those pull in
 * opposite directions if a sound is an asset, and not at all if it is a parameterised one. The bank
 * holds one loop per {@link EngineConfiguration} — six files, a closed set, no new file for a new
 * car — and this record holds the numbers that make a particular car's engine *that* engine:
 *
 * <ul>
 *   <li><b>Configuration.</b> A V8 fires eight times per two revolutions and a V6 six, so at the
 *       same rpm they are a musical fourth apart before anything else is done. It also decides how
 *       evenly each bank fires, which is what a cross-plane V8's burble actually is.
 *   <li><b>Idle and redline.</b> The rev range the loop is pitched across. A road car spinning to
 *       6,500 and a race engine to 9,000 sound different at full throttle even sharing a
 *       configuration, because the second one gets a whole extra fifth of pitch.
 *   <li><b>Power.</b> A more powerful engine is louder and weightier, and both are audible.
 *       {@link #gainAt} and {@link #lowEndWeight()} are where "more powerful sounds like it" is
 *       actually cashed out, rather than being left as an intention.
 *   <li><b>Induction.</b> The second voice, and on a forced engine the one a listener names the car
 *       by. Both shipped cars are forced-induction and for two sessions neither sounded like it —
 *       the bank had one exhaust note per configuration and nothing else, so a supercharged V8 and a
 *       naturally aspirated one were the same file at the same pitch. {@link #inductionGainAt} is
 *       where a geared blower and an exhaust-driven turbo stop being the same sound.
 * </ul>
 *
 * <p>The playback rate is {@code firingHz(rpm) / referenceFiringHz}, which is a pitch ratio — the
 * one thing that has to be right, because an engine that revs is an engine whose fundamental moves.
 * Nothing else can substitute for it.
 */
public record EngineVoice(
        EngineConfiguration configuration, float idleRpm, float redlineRpm, float peakPowerW, Induction induction) {

    /**
     * The rpm each configuration's loop is synthesised at.
     *
     * <p>The middle of a usable rev band rather than idle. A loop recorded at idle spends the whole
     * of a race being stretched upward, and pitch-shifting is only convincing over about an octave
     * either way — so the reference sits where the stretch is smallest at the speeds a car is
     * actually driven at.
     */
    public static final float REFERENCE_RPM = 4000f;

    /** Power at which an engine is at full voice. Above it, gain saturates rather than growing. */
    public static final float REFERENCE_POWER_W = 500_000f;

    /** The quietest an engine gets, at idle with no load. */
    public static final float IDLE_GAIN = 0.35f;

    /** Throttle below which a lift counts as a lift, for the induction release. */
    public static final float LIFT_THROTTLE = 0.12f;

    /** Fraction of the rev range below which a lift is too lazy to produce a release. */
    public static final float LIFT_MIN_REV_FRACTION = 0.35f;

    public EngineVoice {
        if (configuration == null) {
            configuration = EngineConfiguration.V6;
        }
        if (induction == null) {
            induction = Induction.NATURALLY_ASPIRATED;
        }
        idleRpm = Math.max(200f, idleRpm);
        redlineRpm = Math.max(idleRpm + 500f, redlineRpm);
        peakPowerW = Math.max(1f, peakPowerW);
    }

    /** The firing frequency the configuration's loop was synthesised at. */
    public float referenceFiringHz() {
        return configuration.firingHzAt(REFERENCE_RPM);
    }

    /**
     * Engine speed from road speed, as a fraction of the rev range.
     *
     * <p>There is no gearbox in the simulation (DEC-032: tractive force is capped by power, not
     * shifted through ratios), so rpm has to be reconstructed. This does it the way a
     * continuously-variable transmission behaves — rpm rises with road speed across the whole band
     * — which is the honest match for a model that has no gears, and avoids the alternative's
     * failure mode: faking shift points produces a car that changes gear at speeds unrelated to
     * anything the physics is doing.
     *
     * @param speedMps current road speed
     * @param topSpeedMps the vehicle's top speed, from its stats
     * @param throttle the driver's throttle in {@code [0,1]}, which lifts revs at low speed the way
     *     blipping a stationary engine does
     */
    public float rpmFor(float speedMps, float topSpeedMps, float throttle) {
        float span = redlineRpm - idleRpm;
        float speedFraction = topSpeedMps <= 0f ? 0f : clamp01(speedMps / topSpeedMps);
        // Throttle contributes at low speed and fades out as the road speed takes over, so a
        // stationary car revs when you press the pedal and a car at 200 km/h does not gain 2,000
        // rpm for the same press.
        float blip = clamp01(throttle) * (1f - speedFraction) * 0.35f;
        return idleRpm + span * clamp01(speedFraction + blip);
    }

    /**
     * The playback pitch ratio at an engine speed.
     *
     * <p>Clamped to a factor of four either way. Beyond that, resampling a loop stops sounding like
     * an engine and starts sounding like a tape being abused — and a ratio outside that range means
     * the rev range and the reference disagree, which is a content bug worth hearing rather than
     * hiding.
     */
    public float pitchAt(float rpm) {
        float reference = referenceFiringHz();
        if (reference <= 0f) {
            return 1f;
        }
        return clamp(configuration.firingHzAt(rpm) / reference, 0.25f, 4f);
    }

    /**
     * How loud the engine is at an engine speed, in {@code [0,1]}.
     *
     * <p>Two terms, and the second is the user-visible half of "a more powerful engine sounds like
     * it": revs raise the volume as they do on any engine, and peak power raises the whole curve, so
     * an 815 hp V8 at half revs is louder than a 630 hp V6 at half revs. Saturating rather than
     * linear, because loudness is perceived logarithmically and a linear map makes the biggest
     * engine deafening long before it is the fastest.
     */
    public float gainAt(float rpm) {
        float revFraction = clamp01((rpm - idleRpm) / Math.max(1f, redlineRpm - idleRpm));
        float revGain = IDLE_GAIN + (1f - IDLE_GAIN) * revFraction;
        float powerGain = (float) Math.sqrt(clamp01(peakPowerW / REFERENCE_POWER_W));
        return clamp01(revGain * (0.7f + 0.3f * powerGain));
    }

    /**
     * How much the low harmonics are emphasised, in {@code [0,1]}.
     *
     * <p>The other half of power being audible, and the more convincing one. A big engine is not
     * simply a loud small engine: it has more mass moving and its energy sits lower in the spectrum,
     * which is what makes a large-capacity car sound *heavy* through a small speaker where the
     * volume difference is lost.
     */
    public float lowEndWeight() {
        float power = clamp01(peakPowerW / REFERENCE_POWER_W);
        float capacity = configuration.cylinders() / 12f;
        return clamp01(0.35f + 0.4f * power + 0.25f * capacity);
    }

    /** The sound id in the bank this voice plays, e.g. {@code engine_loop_v8}. */
    public String soundId() {
        return "engine_loop_" + configuration.token();
    }

    // ---- Induction: the second voice -----------------------------------------------------

    /** Whether this engine has a forced-induction voice at all. */
    public boolean hasInductionVoice() {
        return induction.isForced();
    }

    /** The induction loop in the bank, e.g. {@code induction_loop_supercharged}. */
    public String inductionSoundId() {
        return "induction_loop_" + induction.token();
    }

    /** The release one-shot, or {@code null} for a device that has none. */
    public String inductionReleaseSoundId() {
        return induction.hasRelease() ? "induction_release_" + induction.token() : null;
    }

    /**
     * The playback pitch ratio of the induction loop at an engine speed.
     *
     * <p>Identical in form to {@link #pitchAt} and identical in value, which is not a coincidence
     * worth hiding: both the firing frequency and a crank-geared blower are fixed multiples of engine
     * rotation, so both scale with {@code rpm / REFERENCE_RPM} and the multiplier cancels. Keeping it
     * as its own method is what lets a turbo diverge — its speed follows exhaust flow rather than the
     * crank, so {@link #inductionGainAt} carries load and this does not.
     */
    public float inductionPitchAt(float rpm) {
        return clamp(rpm / REFERENCE_RPM, 0.25f, 4f);
    }

    /**
     * How loud the induction voice is, in {@code [0,1]}.
     *
     * <p><b>The two devices differ here rather than in the asset</b>, which is the point of keying
     * the bank on {@link Induction} and letting the runtime do the rest. A supercharger is geared to
     * the crank, so it whines whenever the engine turns and its volume tracks revs almost alone. A
     * turbo is driven by exhaust flow, so it is nearly silent off the throttle however fast the
     * engine is spinning, and that lag is most of what makes a turbo car sound like one.
     *
     * @param throttle the driver's throttle in {@code [0,1]}
     */
    public float inductionGainAt(float rpm, float throttle) {
        if (!induction.isForced()) {
            return 0f;
        }
        float revFraction = clamp01((rpm - idleRpm) / Math.max(1f, redlineRpm - idleRpm));
        float load = clamp01(throttle);
        float base = induction == Induction.SUPERCHARGED
                // Geared: present at idle, rising steeply with revs, only lightly gated by throttle.
                ? (0.28f + 0.72f * revFraction) * (0.72f + 0.28f * load)
                // Exhaust-driven: needs both revs and load, and multiplying them is what produces
                // the hole a turbo car has off-boost.
                : revFraction * revFraction * load;
        // A bigger engine moves more air and its blower is bigger with it.
        float powerTerm = 0.75f + 0.25f * (float) Math.sqrt(clamp01(peakPowerW / REFERENCE_POWER_W));
        return clamp01(base * powerTerm);
    }

    /**
     * Whether lifting off at this moment should fire the release one-shot.
     *
     * <p>Both conditions are needed. Without the rev floor a car blows off every time it rolls to a
     * halt, which is the single most obvious way to make a turbo sound like a toy; without the
     * throttle test it never fires at all.
     */
    public boolean shouldRelease(float rpm, float throttle, float previousThrottle) {
        if (!induction.hasRelease()) {
            return false;
        }
        float revFraction = clamp01((rpm - idleRpm) / Math.max(1f, redlineRpm - idleRpm));
        return revFraction >= LIFT_MIN_REV_FRACTION && previousThrottle > 0.45f && throttle <= LIFT_THROTTLE;
    }

    // ---- Start, stop and overrun ---------------------------------------------------------

    /** The ignition one-shot for this engine, e.g. {@code engine_start_v8}. */
    public String startSoundId() {
        return "engine_start_" + configuration.token();
    }

    /** The shutdown one-shot. */
    public String stopSoundId() {
        return "engine_stop_" + configuration.token();
    }

    /** The off-throttle overrun one-shot. */
    public String overrunSoundId() {
        return "engine_overrun_" + configuration.token();
    }

    /**
     * The pitch to play a start or stop at.
     *
     * <p>Authored at a nominal 800 rpm idle, so a car that idles at 750 plays them slightly low and
     * one that idles at 900 slightly high — which is the same trick the loop uses and costs no extra
     * asset.
     */
    public float transientPitch() {
        return clamp(idleRpm / REFERENCE_IDLE_RPM, 0.5f, 2f);
    }

    /** The idle speed the start and stop one-shots were synthesised at. */
    public static final float REFERENCE_IDLE_RPM = 800f;

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
