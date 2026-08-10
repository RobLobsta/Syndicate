/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.syndicate.model.EngineConfiguration;
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
        EngineVoice small = new EngineVoice(EngineConfiguration.I4, 800f, 6500f, 110_000f);
        EngineVoice big = new EngineVoice(EngineConfiguration.V8, 750f, 7600f, 550_000f);

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
        EngineVoice voice = new EngineVoice(EngineConfiguration.V8, 750f, 7600f, 500_000f);
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
        EngineVoice voice = new EngineVoice(EngineConfiguration.V12, 800f, 9000f, 400_000f);
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
        EngineVoice voice = new EngineVoice(null, -100f, -5f, 0f);

        assertThat(voice.configuration()).isNotNull();
        assertThat(voice.idleRpm()).isPositive();
        assertThat(voice.redlineRpm()).isGreaterThan(voice.idleRpm());
        assertThat(voice.gainAt(1000f)).isBetween(0f, 1f);
    }
}
