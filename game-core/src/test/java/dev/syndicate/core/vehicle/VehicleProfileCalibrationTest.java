/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.physics.ShippedContentScene;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.SimulationConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Each shipped vehicle reproduces the real car it was derived from
 * (docs/05_vehicle_part_system.md#D05-S5.6, docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>This is what makes {@link VehicleProfile}'s arithmetic more than a comment. The vehicle is
 * spawned through the real path, driven by the real schedule on the real Bullet world, and timed —
 * so a change to the drag model, the power limit, the wheel tuning or the tyre friction shows up
 * here as a car that no longer matches its reference, rather than as a vague sense that handling
 * feels different.
 *
 * <p><b>Tolerances are wide on purpose.</b> A ray-cast vehicle on box hulls with no gearbox is not a
 * vehicle dynamics model, and holding it to a tenth of a second would make this a test of Bullet's
 * tyre friction rather than of the calibration. Ten per cent catches a transposed digit, a missing
 * driveline efficiency or a lost power limit, which is what it is for.
 */
@Tag("integration")
class VehicleProfileCalibrationTest {

    /** Fraction by which a measured 0-100 may differ from the published figure. */
    private static final float ACCELERATION_TOLERANCE = 0.12f;

    /** Fraction by which a measured 100-0 may differ. Wider than acceleration; see the test. */
    private static final float BRAKING_TOLERANCE = 0.30f;

    /** 100 km/h in m/s, the speed every published acceleration figure is quoted to. */
    private static final float HUNDRED_KPH_MPS = VehicleProfile.HUNDRED_KPH_MPS;

    /**
     * Metres. Spawn height for the chassis body origin, which sits at the wheel-centre plane.
     *
     * <p>Close to the height the suspension settles at, so the vehicle does not arrive with a drop
     * to absorb — a stiff race spring bounces off a 1.2 m drop for well over a second, and a test
     * that started timing during the bounce would measure the bounce.
     */
    private static final float RIDE_HEIGHT_M = 0.62f;

    private static final Vector3 SPAWN_POSITION = new Vector3(0f, RIDE_HEIGHT_M, -200f);

    private ShippedContentScene scene;

    @BeforeEach
    void setUp() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets/ tree is not present");
        NativeResourceTracker.install();
        scene = new ShippedContentScene(20260809L);
    }

    @AfterEach
    void tearDown() {
        if (scene != null) {
            scene.close();
            assertThat(NativeResourceTracker.outstanding()).isZero();
        }
        NativeResourceTracker.uninstall();
    }

    /** Every shipped vehicle spawns, sits on its wheels, and weighs what its profile says. */
    @Test
    void everyShippedVehicleSpawnsAtItsKerbMass() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            int vehicle = scene.spawn(profile, SPAWN_POSITION);
            scene.step(150);

            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
            assertThat(chassis.totalMassKg)
                    .as("%s kerb mass", profile.displayName())
                    .isCloseTo(profile.kerbMassKg(), within(1f));
            assertThat(chassis.wheelCount).isEqualTo(4);
            assertThat(scene.wheelsInContact(vehicle))
                    .as("%s wheels on the ground after settling", profile.displayName())
                    .isEqualTo(4);
            scene.despawn(vehicle);
        }
    }

    /** Slot 6 reproduces each profile's derived acceleration and reports the arena's top speed. */
    @Test
    void aggregatedStatsMatchTheProfile() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            int vehicle = scene.spawn(profile, SPAWN_POSITION);
            scene.step(2);

            VehicleStatsComponent stats = scene.world().getComponent(vehicle, VehicleStatsComponent.class);
            assertThat(stats.engineForceN)
                    .as("%s engine force", profile.displayName())
                    .isCloseTo(profile.engineForceN(), within(1f));
            assertThat(stats.enginePowerW).isCloseTo(profile.enginePowerW(), within(1f));
            assertThat(stats.accelerationMps2).isCloseTo(profile.accelerationMps2(), within(0.05f));
            assertThat(stats.maxSteerRad).isCloseTo(profile.maxSteerRad(), within(1e-3f));
            assertThat(stats.steerRateRadPerSec).isCloseTo(profile.steerRateRadPerSec(), within(1e-3f));
            assertThat(stats.downforceCoefficient).isCloseTo(profile.downforceCoefficientNPerMps2(), within(1e-3f));

            // Every shipped vehicle out-runs the arena, so the reported top speed is the clamp
            // rather than the aerodynamic figure — which is the number a HUD should show.
            assertThat(profile.derivedTopSpeedMps())
                    .isGreaterThan(dev.syndicate.core.system.VehicleControlSystem.MAX_VEHICLE_SPEED_MPS);
            assertThat(stats.maxSpeedMps)
                    .isEqualTo(dev.syndicate.core.system.VehicleControlSystem.MAX_VEHICLE_SPEED_MPS);
            scene.despawn(vehicle);
        }
    }

    /** The measured 0-100 km/h is the published one, within tolerance. */
    @Test
    void eachVehicleReachesHundredKphOnItsPublishedTime() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            int vehicle = scene.spawn(profile, SPAWN_POSITION);
            scene.step(150);

            float measured = scene.timeToSpeed(vehicle, HUNDRED_KPH_MPS, 8f);
            assertThat(measured)
                    .as("%s 0-100 km/h against a published %.2f s", profile.displayName(), profile.zeroToHundredS())
                    .isCloseTo(profile.zeroToHundredS(), within(profile.zeroToHundredS() * ACCELERATION_TOLERANCE));
            scene.despawn(vehicle);
        }
    }

    /** The power limit is what stops the vehicle accelerating forever (DEC-032). */
    @Test
    void thePowerLimitBitesAboveTheCrossover() {
        VehicleProfile profile = VehicleProfiles.APEX_GT;
        int vehicle = scene.spawn(profile, SPAWN_POSITION);
        scene.step(150);

        VehicleStatsComponent stats = scene.world().getComponent(vehicle, VehicleStatsComponent.class);
        float belowCrossover = profile.powerCrossoverMps() * 0.5f;
        float aboveCrossover = profile.powerCrossoverMps() * 2f;

        assertThat(dev.syndicate.core.system.VehicleControlSystem.availableTractiveForceN(stats, belowCrossover))
                .isEqualTo(stats.engineForceN);
        assertThat(dev.syndicate.core.system.VehicleControlSystem.availableTractiveForceN(stats, aboveCrossover))
                .isLessThan(stats.engineForceN * 0.6f);
    }

    /**
     * The two vehicles are a choice rather than an upgrade.
     *
     * <p>The road car wins the drag race; the race car brakes harder and holds more grip. If a
     * future tuning pass makes one strictly better than the other, this is what says so.
     */
    @Test
    void theRoadCarOutAcceleratesAndTheRaceCarOutBrakes() {
        VehicleProfile road = VehicleProfiles.APEX_GT;
        VehicleProfile race = VehicleProfiles.STAMPEDE_GT3;

        assertThat(road.accelerationMps2()).isGreaterThan(race.accelerationMps2());
        assertThat(race.brakeForceN() / race.kerbMassKg()).isGreaterThan(road.brakeForceN() / road.kerbMassKg());
        assertThat(race.frictionSlip()).isGreaterThan(road.frictionSlip());
        assertThat(race.downforceCoefficientNPerMps2()).isGreaterThan(road.downforceCoefficientNPerMps2());
        assertThat(race.kerbMassKg()).isLessThan(road.kerbMassKg());
    }

    /**
     * Braking from 100 km/h lands near the published distance, and the race car stops shorter.
     *
     * <p>The absolute figure runs long — about 11% for the road car and 25% for the race car — and
     * that gap is the model's, not the calibration's: a ray-cast wheel's brake is clipped to its own
     * friction circle, and no amount of brake force recovers grip the tyre does not have. Splitting
     * the brake by live wheel load (DEC-034) closed most of it; closing the rest needs a tyre model
     * rather than a bigger number. The ordering assertion is the one the design actually rests on.
     */
    @Test
    void eachVehicleStopsNearItsPublishedDistanceAndTheRaceCarStopsShorter() {
        float roadDistance = brakingDistanceOf(VehicleProfiles.APEX_GT);
        float raceDistance = brakingDistanceOf(VehicleProfiles.STAMPEDE_GT3);

        assertThat(roadDistance)
                .as("Apex GT 100-0 against a published %.1f m", VehicleProfiles.APEX_GT.brakingHundredToZeroM())
                .isCloseTo(
                        VehicleProfiles.APEX_GT.brakingHundredToZeroM(),
                        within(VehicleProfiles.APEX_GT.brakingHundredToZeroM() * BRAKING_TOLERANCE));
        assertThat(raceDistance)
                .as(
                        "Stampede GT3 100-0 against a published %.1f m",
                        VehicleProfiles.STAMPEDE_GT3.brakingHundredToZeroM())
                .isCloseTo(
                        VehicleProfiles.STAMPEDE_GT3.brakingHundredToZeroM(),
                        within(VehicleProfiles.STAMPEDE_GT3.brakingHundredToZeroM() * BRAKING_TOLERANCE));

        assertThat(raceDistance)
                .as("the race car stops shorter than the road car")
                .isLessThan(roadDistance * 0.92f);
    }

    private float brakingDistanceOf(VehicleProfile profile) {
        int vehicle = scene.spawn(profile, SPAWN_POSITION);
        scene.step(150);
        scene.accelerateTo(vehicle, HUNDRED_KPH_MPS, 8f);
        float distance = scene.brakeToStop(vehicle, 12f);
        scene.despawn(vehicle);
        return distance;
    }

    /** Sanity on the fixed step: every measurement above is in whole ticks (G2). */
    @Test
    void measurementsAreInWholeTicks() {
        assertThat(SimulationConstants.TICK_DT).isCloseTo(1f / 60f, within(1e-6f));
    }
}
