/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;

/**
 * What one vehicle's engine is, as numbers rather than as a recording
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R37).
 *
 * <p><b>Two cars must not sound the same, and neither may cost an audio pass.</b> Those pull in
 * opposite directions if a sound is an asset, and not at all if it is a parameterised one. This
 * record holds the numbers that make a particular car's engine *that* engine, and the client's
 * synthesiser turns them into sound:
 *
 * <ul>
 *   <li><b>Configuration.</b> A V8 fires eight times per two revolutions and a V6 six, so at the
 *       same rpm they are a musical fourth apart before anything else is done. It also decides how
 *       evenly each bank fires, which is what a cross-plane V8's burble actually is.
 *   <li><b>Idle and redline.</b> The rev range the engine works across. A road car spinning to
 *       6,500 and a race engine to 9,000 sound different at full throttle even sharing a
 *       configuration, because the second one gets a whole extra fifth of pitch.
 *   <li><b>Power.</b> A more powerful engine is louder, and {@link #gainAt} is where that is
 *       actually cashed out rather than being left as an intention.
 *   <li><b>Induction.</b> The second voice, and on a forced engine the one a listener names the car
 *       by. A geared blower whines whenever the crank turns; an exhaust-driven turbo goes quiet the
 *       moment the driver lifts, and that hole is most of what makes a turbo car sound like one.
 * </ul>
 *
 * <p><b>No sound ids and no pitch ratio</b> (DEC-056). Both were here when an engine was six files
 * played faster or slower, and both are gone now that it is synthesised as it runs: there is no
 * file to name and no reference rpm to pitch away from, because the synthesiser is simply told the
 * rpm. What remains is the description of the engine — which is all this record was ever for.
 */
public record EngineVoice(
        EngineConfiguration configuration, float idleRpm, float redlineRpm, float peakPowerW, Induction induction) {

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

    /** Where in the rev range an engine speed sits, in {@code [0,1]}. */
    public float revFraction(float rpm) {
        return clamp01((rpm - idleRpm) / Math.max(1f, redlineRpm - idleRpm));
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
        float revGain = IDLE_GAIN + (1f - IDLE_GAIN) * revFraction(rpm);
        float powerGain = (float) Math.sqrt(clamp01(peakPowerW / REFERENCE_POWER_W));
        return clamp01(revGain * (0.7f + 0.3f * powerGain));
    }

    /** Whether this engine has a forced-induction voice at all. */
    public boolean hasInductionVoice() {
        return induction.isForced();
    }

    /**
     * Whether lifting off at this moment should fire the blow-off.
     *
     * <p>Both conditions are needed. Without the rev floor a car blows off every time it rolls to a
     * halt, which is the single most obvious way to make a turbo sound like a toy; without the
     * throttle test it never fires at all.
     */
    public boolean shouldRelease(float rpm, float throttle, float previousThrottle) {
        if (!induction.hasRelease()) {
            return false;
        }
        return revFraction(rpm) >= LIFT_MIN_REV_FRACTION && previousThrottle > 0.45f && throttle <= LIFT_THROTTLE;
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
