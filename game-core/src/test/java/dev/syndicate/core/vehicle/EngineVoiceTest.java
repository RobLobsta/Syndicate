/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.syndicate.model.EngineConfiguration;
import dev.syndicate.model.Induction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two cars must not sound the same, and a more powerful engine must sound like one
 * (docs/15_vehicle_preparation_pipeline.md#D15-S8).
 *
 * <p>Every assertion here is on a number the audio system will actually play with — a pitch ratio, a
 * gain, a sound id — rather than on a spectrum, because what makes engines sound different is
 * decided long before any samples are mixed.
 */
@Tag("unit")
class EngineVoiceTest {

    private static final float TOP_SPEED_MPS = 90f;

    /** The whole point: the two shipped cars are audibly different engines. */
    @Test
    void theTwoShippedCarsSoundDifferent() {
        EngineVoice eclipse = VehicleProfiles.ECLIPSE.engineVoice();
        EngineVoice stampede = VehicleProfiles.STAMPEDE.engineVoice();

        assertThat(eclipse.soundId()).isNotEqualTo(stampede.soundId());
        assertThat(eclipse.configuration()).isEqualTo(EngineConfiguration.V6);
        assertThat(stampede.configuration()).isEqualTo(EngineConfiguration.V8);

        // At the same road speed the V8 fires a third more often than the V6, which is the
        // difference the ear names first.
        float speedMps = 40f;
        float eclipseHz = eclipse.configuration().firingHzAt(eclipse.rpmFor(speedMps, TOP_SPEED_MPS, 1f));
        float stampedeHz = stampede.configuration().firingHzAt(stampede.rpmFor(speedMps, TOP_SPEED_MPS, 1f));
        assertThat(stampedeHz / eclipseHz).isGreaterThan(1.15f);
    }

    /** D15-S8: a more powerful engine is louder and weightier at the same fraction of its revs. */
    @Test
    void aMorePowerfulEngineIsLouderAndHeavier() {
        EngineVoice small =
                new EngineVoice(EngineConfiguration.I4, 800f, 6500f, 110_000f, Induction.NATURALLY_ASPIRATED);
        EngineVoice big = new EngineVoice(EngineConfiguration.V8, 750f, 7600f, 550_000f, Induction.SUPERCHARGED);

        assertThat(big.gainAt(big.redlineRpm())).isGreaterThan(small.gainAt(small.redlineRpm()));
        assertThat(big.lowEndWeight()).isGreaterThan(small.lowEndWeight());
    }

    /** Power is the same number the physics accelerates the car with, so the two cannot drift. */
    @Test
    void theVoicesPowerIsThePhysicsPower() {
        assertThat(VehicleProfiles.STAMPEDE.engineVoice().peakPowerW())
                .isEqualTo(VehicleProfiles.STAMPEDE.enginePowerW());
        assertThat(VehicleProfiles.STAMPEDE.engineVoice().peakPowerW())
                .isGreaterThan(VehicleProfiles.ECLIPSE.engineVoice().peakPowerW());
    }

    /** The pitch ratio is 1 at the rpm the loop was synthesised at, or every engine is transposed. */
    @Test
    void pitchIsUnityAtTheReferenceRpm() {
        EngineVoice voice = new EngineVoice(EngineConfiguration.V8, 750f, 7600f, 500_000f, Induction.SUPERCHARGED);
        assertThat(voice.pitchAt(EngineVoice.REFERENCE_RPM)).isCloseTo(1f, within(1e-4f));
    }

    /** Revving raises the pitch, monotonically, which is the one thing an engine loop must do. */
    @Test
    void pitchRisesWithRevs() {
        EngineVoice voice = VehicleProfiles.ECLIPSE.engineVoice();
        assertThat(voice.pitchAt(voice.idleRpm())).isLessThan(voice.pitchAt(4000f));
        assertThat(voice.pitchAt(4000f)).isLessThan(voice.pitchAt(voice.redlineRpm()));
    }

    /** Pitch is clamped, so a badly authored rev range is audible rather than a shriek. */
    @Test
    void pitchIsClampedToAUsableRange() {
        EngineVoice voice =
                new EngineVoice(EngineConfiguration.V12, 800f, 9000f, 400_000f, Induction.NATURALLY_ASPIRATED);
        assertThat(voice.pitchAt(1f)).isGreaterThanOrEqualTo(0.25f);
        assertThat(voice.pitchAt(1_000_000f)).isLessThanOrEqualTo(4f);
    }

    /** Revs track road speed across the whole band, because the simulation has no gearbox. */
    @Test
    void revsRiseWithRoadSpeed() {
        EngineVoice voice = VehicleProfiles.STAMPEDE.engineVoice();

        assertThat(voice.rpmFor(0f, TOP_SPEED_MPS, 0f)).isCloseTo(voice.idleRpm(), within(1f));
        assertThat(voice.rpmFor(TOP_SPEED_MPS, TOP_SPEED_MPS, 1f)).isCloseTo(voice.redlineRpm(), within(1f));
        assertThat(voice.rpmFor(20f, TOP_SPEED_MPS, 0f)).isLessThan(voice.rpmFor(60f, TOP_SPEED_MPS, 0f));
    }

    /** A stationary car blips when you press the throttle; one at speed barely moves. */
    @Test
    void throttleLiftsRevsAtRestAndNotAtSpeed() {
        EngineVoice voice = VehicleProfiles.ECLIPSE.engineVoice();

        float blipAtRest = voice.rpmFor(0f, TOP_SPEED_MPS, 1f) - voice.rpmFor(0f, TOP_SPEED_MPS, 0f);
        float blipAtSpeed = voice.rpmFor(TOP_SPEED_MPS * 0.95f, TOP_SPEED_MPS, 1f)
                - voice.rpmFor(TOP_SPEED_MPS * 0.95f, TOP_SPEED_MPS, 0f);

        assertThat(blipAtRest).isGreaterThan(500f);
        assertThat(blipAtSpeed).isLessThan(blipAtRest * 0.2f);
    }

    /** Every configuration has a distinct firing frequency, which is why the axis was chosen. */
    @Test
    void everyConfigurationFiresAtItsOwnRate() {
        float previous = 0f;
        for (EngineConfiguration configuration : EngineConfiguration.values()) {
            float hz = configuration.firingHzAt(EngineVoice.REFERENCE_RPM);
            assertThat(hz).isPositive();
            // I6 and V6 share a cylinder count on purpose — same pitch, different roughness — so
            // the sequence is non-decreasing rather than strictly increasing.
            assertThat(hz).isGreaterThanOrEqualTo(previous);
            previous = hz;
        }
        assertThat(EngineConfiguration.I6.roughness()).isNotEqualTo(EngineConfiguration.V6.roughness());
    }

    /** A voice built with nonsense still produces something playable rather than a divide by zero. */
    @Test
    void aDegenerateVoiceIsMadeSafe() {
        EngineVoice voice = new EngineVoice(null, -100f, -5f, 0f, null);

        assertThat(voice.configuration()).isNotNull();
        assertThat(voice.idleRpm()).isPositive();
        assertThat(voice.redlineRpm()).isGreaterThan(voice.idleRpm());
        assertThat(voice.gainAt(1000f)).isBetween(0f, 1f);
    }
    /**
     * A supercharger is audible off the throttle and a turbo is not.
     *
     * <p>This is the whole reason {@link Induction} exists as an axis rather than as a volume knob.
     * A blower is geared to the crank, so it whines whenever the engine turns; a turbo is spun by
     * exhaust flow, so at the same revs with a closed throttle it has nothing driving it. Two cars
     * that differ only in how they breathe must not sound like the same car at two volumes.
     */
    @Test
    void aSuperchargerIsAudibleOffThrottleAndATurboIsNot() {
        EngineVoice blown = new EngineVoice(EngineConfiguration.V8, 750f, 7600f, 550_000f, Induction.SUPERCHARGED);
        EngineVoice turbo = new EngineVoice(EngineConfiguration.V6, 850f, 8000f, 460_000f, Induction.TURBO);

        float revs = 5000f;
        assertThat(blown.inductionGainAt(revs, 0f))
                .as("a geared blower whines whether or not the driver is on the throttle")
                .isGreaterThan(0.2f);
        assertThat(turbo.inductionGainAt(revs, 0f))
                .as("an exhaust-driven turbo has nothing driving it off the throttle")
                .isLessThan(0.05f);

        // On full throttle both are working, so neither is simply quieter than the other everywhere.
        assertThat(turbo.inductionGainAt(revs, 1f)).isGreaterThan(0.2f);
    }

    /** A naturally aspirated engine has no second voice at all, and asking costs nothing. */
    @Test
    void aNaturallyAspiratedEngineHasNoInductionVoice() {
        EngineVoice voice =
                new EngineVoice(EngineConfiguration.V12, 800f, 9000f, 400_000f, Induction.NATURALLY_ASPIRATED);

        assertThat(voice.hasInductionVoice()).isFalse();
        assertThat(voice.inductionReleaseSoundId()).isNull();
        assertThat(voice.inductionGainAt(6000f, 1f)).isZero();
        assertThat(voice.shouldRelease(6000f, 0f, 1f)).isFalse();
    }

    /**
     * A blow-off needs a real lift from real revs.
     *
     * <p>Both conditions matter. Without the rev floor a car blows off every time it rolls to a
     * halt, which is the most recognisable way to make a turbo sound like a toy; without the throttle
     * transition it never fires at all.
     */
    @Test
    void aReleaseNeedsBothALiftAndTheRevsToJustifyIt() {
        EngineVoice turbo = new EngineVoice(EngineConfiguration.V6, 850f, 8000f, 460_000f, Induction.TURBO);

        assertThat(turbo.shouldRelease(6000f, 0f, 1f))
                .as("hard on it, then off it")
                .isTrue();
        assertThat(turbo.shouldRelease(1000f, 0f, 1f))
                .as("lifting at idle is not a blow-off")
                .isFalse();
        assertThat(turbo.shouldRelease(6000f, 1f, 1f))
                .as("still on the throttle")
                .isFalse();
        assertThat(turbo.shouldRelease(6000f, 0f, 0f))
                .as("never was on the throttle")
                .isFalse();
    }

    /**
     * A car plays the shared start and stop one-shots at its own idle speed.
     *
     * <p>The bank authors them once per configuration at a nominal 800 rpm (D15-R37a), so the only
     * per-car term is this ratio — which is what keeps six configurations from becoming eighteen
     * files and then thirty-six when the roster grows.
     */
    @Test
    void startAndStopArePitchedToACarsOwnIdle() {
        EngineVoice lowIdle = new EngineVoice(EngineConfiguration.V8, 600f, 7600f, 550_000f, Induction.SUPERCHARGED);
        EngineVoice highIdle = new EngineVoice(EngineConfiguration.V8, 1000f, 7600f, 550_000f, Induction.SUPERCHARGED);

        assertThat(lowIdle.transientPitch()).isLessThan(1f);
        assertThat(highIdle.transientPitch()).isGreaterThan(1f);
        assertThat(lowIdle.startSoundId()).isEqualTo("engine_start_v8");
        assertThat(lowIdle.stopSoundId()).isEqualTo("engine_stop_v8");
        assertThat(lowIdle.overrunSoundId()).isEqualTo("engine_overrun_v8");
    }

    /**
     * The two shipped cars differ in how they breathe, not only in how they fire.
     *
     * <p>Both reference cars are forced-induction and for two sessions neither sounded like it. This
     * is the content assertion that keeps that from silently regressing: if somebody rebuilds the
     * profiles and drops the induction, the pair goes back to being one exhaust note at two pitches.
     */
    @Test
    void theShippedPairBreathesDifferently() {
        EngineVoice eclipse = VehicleProfiles.ECLIPSE.engineVoice();
        EngineVoice stampede = VehicleProfiles.STAMPEDE.engineVoice();

        assertThat(eclipse.induction()).isEqualTo(Induction.TURBO);
        assertThat(stampede.induction()).isEqualTo(Induction.SUPERCHARGED);
        assertThat(eclipse.inductionSoundId()).isNotEqualTo(stampede.inductionSoundId());

        // Coasting at speed: the supercharged car is still whining, the turbo car has gone quiet.
        float coastRpm = 5000f;
        assertThat(stampede.inductionGainAt(coastRpm, 0f)).isGreaterThan(eclipse.inductionGainAt(coastRpm, 0f));
    }
}
