/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 6 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/05_vehicle_part_system.md#D05-S5.6).
 */
@Tag("integration")
class VehicleStatsSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_stats_01");

    private static final float CHASSIS_MASS_KG = 1200f;
    private static final float WHEEL_MASS_KG = 40f;
    private static final float ENGINE_FORCE_N = 24_000f;
    private static final float BRAKE_FORCE_N = 6_000f;

    private DestructionTestScene scene;
    private int vehicle;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(4242L);
        vehicle = scene.spawnVehicle(ASSEMBLY, fourWheeler(), new Vector3(0f, 5f, 0f));
        scene.step();
    }

    @AfterEach
    void tearDown() {
        scene.close();
        assertThat(NativeResourceTracker.outstanding()).isZero();
        NativeResourceTracker.uninstall();
    }

    /** A chassis with four driven, steering wheels — the smallest drivable vehicle (D05-E10). */
    private static List<PartSpec> fourWheeler() {
        List<PartSpec> parts = new ArrayList<>();
        parts.add(PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3())
                .contributing(Stat.ENGINE_FORCE_N, ENGINE_FORCE_N)
                .contributing(Stat.BRAKE_FORCE_N, BRAKE_FORCE_N));
        parts.add(wheel("root/wheel_fl", -0.9f, 1.4f));
        parts.add(wheel("root/wheel_fr", 0.9f, 1.4f));
        parts.add(wheel("root/wheel_rl", -0.9f, -1.4f));
        parts.add(wheel("root/wheel_rr", 0.9f, -1.4f));
        return parts;
    }

    private static PartSpec wheel(String slotPath, float x, float z) {
        return PartSpec.of(slotPath, PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(x, -0.4f, z));
    }

    /** D05-S5.6 phase 3: the vehicle's engine and brake force are its parts', summed. */
    @Test
    void vehicleForcesAreTheSumOfItsParts() {
        VehicleStatsComponent stats = statsOf();
        assertThat(stats.engineForceN).isCloseTo(ENGINE_FORCE_N, within(1e-3f));
        assertThat(stats.brakeForceN).isCloseTo(BRAKE_FORCE_N, within(1e-3f));
        assertThat(stats.dirty).isFalse();
    }

    /** DEC-027: a wheel that authors no steering lock still steers, on the engine default. */
    @Test
    void steeringComesFromTheWheelDefaultsWhenContentAuthorsNone() {
        VehicleStatsComponent stats = statsOf();
        assertThat(stats.maxSteerRad).isCloseTo(VehicleStatsSystem.DEFAULT_MAX_STEER_RAD, within(1e-4f));
        assertThat(stats.steerRateRadPerSec)
                .isCloseTo(VehicleStatsSystem.DEFAULT_STEER_RATE_RAD_PER_SEC, within(1e-4f));
    }

    /** AC-D05-17 / D05-R16: speed and acceleration are derived from force, mass and drag. */
    @Test
    void speedAndAccelerationAreDerivedNeverAuthored() {
        VehicleStatsComponent stats = statsOf();
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);

        assertThat(stats.accelerationMps2).isCloseTo(ENGINE_FORCE_N / chassis.totalMassKg, within(1e-3f));

        float rolling = VehicleStatsSystem.CHASSIS_ROLLING_RESISTANCE
                * chassis.totalMassKg
                * Math.abs(SimulationConstants.WORLD_GRAVITY_Y);
        float expected = (float) Math.sqrt((ENGINE_FORCE_N - rolling) / VehicleStatsSystem.CHASSIS_DRAG_COEFFICIENT);
        assertThat(stats.maxSpeedMps).isCloseTo(expected, within(1e-2f));
    }

    /** An engine that cannot overcome its own rolling resistance gets zero, not a NaN. */
    @Test
    void anUnderpoweredVehicleHasZeroTopSpeedRatherThanNaN() {
        try (DestructionTestScene weak = new DestructionTestScene(7L)) {
            int slug = weak.spawnVehicle(
                    AssetId.of("assembly_slug_01"),
                    List.of(
                            PartSpec.of("root", PartCategory.CHASSIS, 4_000f, new Vector3()),
                            PartSpec.of("root/wheel_fl", PartCategory.WHEEL, 40f, new Vector3(-0.9f, -0.4f, 1.4f)),
                            PartSpec.of("root/wheel_fr", PartCategory.WHEEL, 40f, new Vector3(0.9f, -0.4f, 1.4f)),
                            PartSpec.of("root/wheel_rl", PartCategory.WHEEL, 40f, new Vector3(-0.9f, -0.4f, -1.4f))),
                    new Vector3(0f, 5f, 0f));
            weak.step();

            VehicleStatsComponent stats = weak.world().getComponent(slug, VehicleStatsComponent.class);
            assertThat(stats.engineForceN).isZero();
            assertThat(stats.maxSpeedMps).isZero();
            assertThat(Float.isNaN(stats.maxSpeedMps)).isFalse();
        }
    }

    /** T-D05-15 / AC-D05-15: recomputing twice from the same state is bit-identical. */
    @Test
    void aggregationIsPure() {
        VehicleStatsComponent stats = statsOf();
        float engine = stats.engineForceN;
        float brake = stats.brakeForceN;
        float steer = stats.maxSteerRad;
        float speed = stats.maxSpeedMps;
        float budget = stats.powerBudget;

        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        assertThat(stats.engineForceN).isEqualTo(engine);
        assertThat(stats.brakeForceN).isEqualTo(brake);
        assertThat(stats.maxSteerRad).isEqualTo(steer);
        assertThat(stats.maxSpeedMps).isEqualTo(speed);
        assertThat(stats.powerBudget).isEqualTo(budget);
    }

    /** T-D05-16 / AC-D05-16 / D05-R28: a destroyed part contributes exactly zero. */
    @Test
    void aDestroyedChassisContributesNoEngineForce() {
        scene.destroyPart(scene.partAt(vehicle, "root"));
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        VehicleStatsComponent stats = statsOf();
        assertThat(stats.engineForceN).isZero();
        assertThat(stats.accelerationMps2).isZero();
        assertThat(stats.maxSpeedMps).isZero();
    }

    /** T-D05-20 / D05-E9: a destroyed part stays at zero even if its health is written back up. */
    @Test
    void aDestroyedPartStaysZeroWhenItsHealthIsRestored() {
        int chassisPart = scene.partAt(vehicle, "root");
        scene.destroyPart(chassisPart);
        scene.world().getComponent(chassisPart, HealthComponent.class).setCurrentHp(50f);

        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        PartStatsComponent partStats = scene.world().getComponent(chassisPart, PartStatsComponent.class);
        assertThat(partStats.effectiveStats.resolve(Stat.ENGINE_FORCE_N, 0f)).isZero();
    }

    /** D05-S5.4: a damaged chassis degrades its engine force on the LINEAR curve at floor 0.45. */
    @Test
    void aDamagedChassisLosesEngineForceOnTheLinearCurve() {
        damage(scene.partAt(vehicle, "root"), 0.5f);
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        float expected = ENGINE_FORCE_N * (0.45f + 0.55f * 0.5f);
        assertThat(statsOf().engineForceN).isCloseTo(expected, within(1f));
    }

    /** D05-S5.4 and D04-S4.4 row 6: per-wheel grip degrades and is written where control reads it. */
    @Test
    void wheelGripDegradesOnItsOwnHealth() {
        int wheelPart = scene.partAt(vehicle, "root/wheel_fl");
        WheelControllerComponent controller = scene.world().getComponent(wheelPart, WheelControllerComponent.class);
        float base = controller.frictionSlip;

        damage(wheelPart, 0.5f);
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        assertThat(controller.effectiveFrictionSlip).isCloseTo(base * (0.35f + 0.65f * 0.25f), within(1e-3f));
        // Its three healthy siblings are untouched: degradation is per part, not per vehicle.
        WheelControllerComponent sibling =
                scene.world().getComponent(scene.partAt(vehicle, "root/wheel_fr"), WheelControllerComponent.class);
        assertThat(sibling.effectiveFrictionSlip).isCloseTo(sibling.frictionSlip, within(1e-3f));
    }

    /** D05-E1 / T-D05-19: every wheel destroyed leaves the vehicle alive with no steering and no NaN. */
    @Test
    void aVehicleWithNoLiveWheelsHasZeroSteeringAndNoNaN() {
        for (String slotPath : List.of("root/wheel_fl", "root/wheel_fr", "root/wheel_rl", "root/wheel_rr")) {
            scene.destroyPart(scene.partAt(vehicle, slotPath));
        }
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        VehicleStatsComponent stats = statsOf();
        assertThat(stats.maxSteerRad).isZero();
        assertThat(stats.steerRateRadPerSec).isZero();
        assertThat(stats.armorRatingAvg).isZero();
        assertThat(Float.isNaN(stats.engineForceN)).isFalse();
        // The vehicle itself is untouched: immobile is not destroyed (D05-R25).
        assertThat(stats.engineForceN).isGreaterThan(0f);
    }

    /** T-D05-16: an ammo feed shortens fire intervals, and stops doing so when it dies. */
    @Test
    void aUtilityBuffsOtherPartsUntilItIsDestroyed() {
        try (DestructionTestScene buffed = new DestructionTestScene(99L)) {
            int gunner = buffed.spawnVehicle(
                    AssetId.of("assembly_gunner_01"),
                    List.of(
                            PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                            PartSpec.of(
                                    "root/wheel_fl",
                                    PartCategory.WHEEL,
                                    WHEEL_MASS_KG,
                                    new Vector3(-0.9f, -0.4f, 1.4f)),
                            PartSpec.of(
                                    "root/wheel_fr", PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(0.9f, -0.4f, 1.4f)),
                            PartSpec.of(
                                    "root/wheel_rl",
                                    PartCategory.WHEEL,
                                    WHEEL_MASS_KG,
                                    new Vector3(-0.9f, -0.4f, -1.4f)),
                            PartSpec.of("root/gun", PartCategory.WEAPON, 90f, new Vector3(0f, 0.6f, 0f))
                                    .contributing(Stat.FIRE_INTERVAL_S, 0.5f),
                            PartSpec.of("root/feed", PartCategory.UTILITY, 30f, new Vector3(0f, 0.3f, -0.5f))
                                    .multiplying(Stat.FIRE_INTERVAL_S, 0.8f)),
                    new Vector3(0f, 5f, 0f));
            buffed.step();

            WeaponControllerComponent weapon =
                    buffed.world().getComponent(buffed.partAt(gunner, "root/gun"), WeaponControllerComponent.class);
            assertThat(weapon.effectiveFireIntervalS).isCloseTo(0.5f * 0.8f, within(1e-4f));

            buffed.destroyPart(buffed.partAt(gunner, "root/feed"));
            buffed.vehicleStatsSystem().recompute(buffed.world(), gunner);

            assertThat(weapon.effectiveFireIntervalS).isCloseTo(0.5f, within(1e-4f));
        }
    }

    /** This system runs in PRE_SIM at slot 6, ahead of control and of the Bullet step (D04-S4.4). */
    @Test
    void runsAtSlotSixInPreSim() {
        assertThat(scene.vehicleStatsSystem().order())
                .isEqualTo(VehicleStatsSystem.ORDER)
                .isEqualTo(6);
        assertThat(scene.vehicleStatsSystem().phase()).isEqualTo(Phase.PRE_SIM);
    }

    private VehicleStatsComponent statsOf() {
        return scene.world().getComponent(vehicle, VehicleStatsComponent.class);
    }

    private void damage(int partEntity, float healthFraction) {
        HealthComponent health = scene.world().getComponent(partEntity, HealthComponent.class);
        health.setCurrentHp(health.maxHp * healthFraction);
    }
}
