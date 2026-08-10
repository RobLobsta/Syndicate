/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.model.EngineConfiguration;

/**
 * What one vehicle's engine sounds like, as numbers rather than as a recording
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37).
 *
 * <p><b>Two cars must not sound the same, and neither may cost an audio pass.</b> Those pull in
 * opposite directions if a sound is an asset, and not at all if it is a parameterised one. The bank
 * holds one loop per {@link EngineConfiguration} — six files, a closed set, no new file for a new
 * car — and this record holds the four numbers that make a particular car's engine *that* engine:
 *
 * <ul>
 *   <li><b>Configuration.</b> A V8 fires eight times per two revolutions and a V6 six, so at the
 *       same rpm they are a musical fourth apart before anything else is done. It also sets how
 *       rough and how harmonically rich the loop is.
 *   <li><b>Idle and redline.</b> The rev range the loop is pitched across. A road car spinning to
 *       6,500 and a race engine to 9,000 sound different at full throttle even sharing a
 *       configuration, because the second one gets a whole extra fifth of pitch.
 *   <li><b>Power.</b> A more powerful engine is louder and weightier, and both are audible.
 *       {@link #gainAt} and {@link #lowEndWeight()} are where "more powerful sounds like it" is
 *       actually cashed out, rather than being left as an intention.
 * </ul>
 *
 * <p>The playback rate is {@code firingHz(rpm) / referenceFiringHz}, which is a pitch ratio — the
 * one thing that has to be right, because an engine that revs is an engine whose fundamental moves.
 * Nothing else can substitute for it.
 */
public record EngineVoice(EngineConfiguration configuration, float idleRpm, float redlineRpm, float peakPowerW) {

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

    public EngineVoice {
        if (configuration == null) {
            configuration = EngineConfiguration.V6;
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

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
