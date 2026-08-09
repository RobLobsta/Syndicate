/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 7 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>These are the first tests in the project that assert a vehicle <em>moves</em>. They run on the
 * real schedule — spawn, stats, control, Bullet — over a ground plane, because the interesting
 * failures (a wheel index that steers the wrong corner, an engine force applied to a sleeping body,
 * a compound whose origin is not the COM) only appear once the whole chain is present.
 */
@Tag("integration")
class VehicleControlSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_drive_01");

    private static final float CHASSIS_MASS_KG = 1200f;
    private static final float WHEEL_MASS_KG = 40f;
    private static final float ENGINE_FORCE_N = 24_000f;
    private static final float BRAKE_FORCE_N = 30_000f;

    /** Where the chassis body starts: high enough that the wheels are clear of the ground. */
    private static final Vector3 SPAWN_POSITION = new Vector3(0f, 1f, 0f);

    /** A wheel's collision box. Its radius is half the larger of the Y and Z extents (DEC-022). */
    private static final Vector3 WHEEL_HALF_EXTENTS = new Vector3(0.15f, 0.42f, 0.42f);

    private DestructionTestScene scene;
    private int vehicle;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(2024L);
        scene.addGround();
        vehicle = scene.spawnVehicle(ASSEMBLY, fourWheeler(), SPAWN_POSITION);
        // Let it settle on its suspension first, so what the drive tests measure is drive rather
        // than the vehicle dropping the last few centimetres onto its springs.
        scene.step(60);
    }

    @AfterEach
    void tearDown() {
        scene.close();
        assertThat(NativeResourceTracker.outstanding()).isZero();
        NativeResourceTracker.uninstall();
    }

    private static List<PartSpec> fourWheeler() {
        return List.of(
                PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3())
                        .contributing(Stat.ENGINE_FORCE_N, ENGINE_FORCE_N)
                        .contributing(Stat.BRAKE_FORCE_N, BRAKE_FORCE_N),
                wheel("root/wheel_fl", -0.9f, 1.4f),
                wheel("root/wheel_fr", 0.9f, 1.4f),
                wheel("root/wheel_rl", -0.9f, -1.4f),
                wheel("root/wheel_rr", 0.9f, -1.4f));
    }

    /** A wheel whose contact point sits below the chassis hull, so it carries the vehicle. */
    private static PartSpec wheel(String slotPath, float x, float z) {
        return PartSpec.of(slotPath, PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(x, -0.1f, z))
                .sized(WHEEL_HALF_EXTENTS);
    }

    /** The point of Phase 4: throttle makes the vehicle go, and forward is +Z (D00-R16). */
    @Test
    void throttleDrivesTheVehicleForward() {
        float startZ = positionOf().z;
        input().throttle = 1f;
        scene.step(120);

        assertThat(positionOf().z - startZ).isGreaterThan(1f);
        assertThat(speed()).isGreaterThan(1f);
    }

    /** Reverse is the same path with the sign flipped, not a second code path. */
    @Test
    void negativeThrottleDrivesItBackwards() {
        float startZ = positionOf().z;
        input().throttle = -1f;
        scene.step(120);

        assertThat(positionOf().z - startZ).isLessThan(-1f);
    }

    /** The brake stops a moving vehicle (D06-S5.5). */
    @Test
    void brakingSlowsTheVehicle() {
        input().throttle = 1f;
        scene.step(120);
        float movingSpeed = speed();
        assertThat(movingSpeed).isGreaterThan(2f);

        input().throttle = 0f;
        input().brake = 1f;
        scene.step(120);

        assertThat(speed()).isLessThan(movingSpeed * 0.5f);
    }

    /** D06-S5.5: steering is rate-limited toward its target rather than snapped to it. */
    @Test
    void steeringIsRateLimited() {
        VehicleStatsComponent stats = scene.world().getComponent(vehicle, VehicleStatsComponent.class);
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        input().steer = 1f;

        scene.step();
        float afterOneTick = chassis.currentSteerRad;
        assertThat(afterOneTick)
                .isCloseTo(stats.steerRateRadPerSec * SimulationConstants.TICK_DT, within(1e-5f))
                .isLessThan(stats.maxSteerRad);

        // It reaches full lock, and stops there: moveToward never overshoots its target.
        scene.step(120);
        assertThat(chassis.currentSteerRad).isCloseTo(stats.maxSteerRad, within(1e-5f));
    }

    /** Steering returns to centre at the same rate, and settles exactly on zero. */
    @Test
    void steeringReturnsToCentre() {
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        input().steer = 1f;
        scene.step(120);
        assertThat(chassis.currentSteerRad).isGreaterThan(0f);

        input().steer = 0f;
        scene.step(120);
        assertThat(chassis.currentSteerRad).isEqualTo(0f);
    }

    /** Steering a driven vehicle turns it: the wheel indices reach the corners they name. */
    @Test
    void steeringTurnsTheVehicle() {
        input().throttle = 1f;
        scene.step(60);
        input().steer = 1f;
        scene.step(180);

        // A left turn moves it off the Z axis it started on; which way depends on the sign
        // convention, so the assertion is that it left the straight line, not which side.
        assertThat(Math.abs(positionOf().x)).isGreaterThan(0.5f);
    }

    /** DEC-028: a destroyed wheel is commanded to zero rather than skipped, so it stops driving. */
    @Test
    void aDestroyedWheelStopsDrivingAndSteering() {
        input().throttle = 1f;
        input().steer = 1f;
        scene.step(30);

        int wheelPart = scene.partAt(vehicle, "root/wheel_fl");
        WheelControllerComponent wheel = scene.world().getComponent(wheelPart, WheelControllerComponent.class);
        int wheelIndex = wheel.wheelIndex;
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);

        assertThat(chassis.vehicleController.getWheelInfo(wheelIndex).getEngineForce())
                .isNotZero();

        scene.destroyPart(wheelPart);
        // One tick of slot 6 and slot 7 only: a further step would let FractureSystem (13) detach
        // the wheel, and the point here is what control does with a destroyed wheel still attached.
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);
        scene.vehicleControlSystem().update(scene.world(), SimulationConstants.TICK_DT, scene.tick());

        assertThat(chassis.vehicleController.getWheelInfo(wheelIndex).getEngineForce())
                .isZero();
        assertThat(chassis.vehicleController.getWheelInfo(wheelIndex).getSteering())
                .isZero();
        assertThat(chassis.vehicleController.getWheelInfo(wheelIndex).getFrictionSlip())
                .isZero();
    }

    /** D05-E1 / D05-R25: a vehicle with no live wheels is immobile and alive, with no NaN anywhere. */
    @Test
    void aVehicleWithEveryWheelDestroyedDoesNotDrive() {
        for (String slotPath : List.of("root/wheel_fl", "root/wheel_fr", "root/wheel_rl", "root/wheel_rr")) {
            scene.destroyPart(scene.partAt(vehicle, slotPath));
        }
        scene.vehicleStatsSystem().recompute(scene.world(), vehicle);

        input().throttle = 1f;
        input().steer = 1f;
        // Not stepped: detaching four wheels is slot 14's business and would change the vehicle out
        // from under the assertion. Slots 6 and 7 alone are what this test is about.
        scene.vehicleControlSystem().update(scene.world(), SimulationConstants.TICK_DT, scene.tick());

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        assertThat(chassis.currentSteerRad).isZero();
        for (int i = 0; i < chassis.vehicleController.getNumWheels(); i++) {
            assertThat(chassis.vehicleController.getWheelInfo(i).getEngineForce())
                    .isZero();
        }
    }

    /** D06-S5.5: the speed clamp is the second net behind CCD. */
    @Test
    void speedIsClampedAtTheAntiTunnellingLimit() {
        scene.bodyOf(vehicle).setLinearVelocity(new Vector3(0f, 0f, 120f));
        scene.vehicleControlSystem().update(scene.world(), SimulationConstants.TICK_DT, scene.tick());

        assertThat(scene.bodyOf(vehicle).getLinearVelocity().len())
                .isCloseTo(VehicleControlSystem.MAX_VEHICLE_SPEED_MPS, within(1e-3f));
    }

    /** {@code moveToward} never overshoots, in either direction. */
    @Test
    void moveTowardNeverOvershoots() {
        assertThat(VehicleControlSystem.moveToward(0f, 0.5f, 0.1f)).isCloseTo(0.1f, within(1e-6f));
        assertThat(VehicleControlSystem.moveToward(0f, 0.5f, 10f)).isEqualTo(0.5f);
        assertThat(VehicleControlSystem.moveToward(0.5f, -0.5f, 10f)).isEqualTo(-0.5f);
        assertThat(VehicleControlSystem.moveToward(0.5f, -0.5f, 0.2f)).isCloseTo(0.3f, within(1e-6f));
    }

    /** This system runs in SIM at slot 7, between stat aggregation and the Bullet step (D04-S4.4). */
    @Test
    void runsAtSlotSevenInSim() {
        assertThat(scene.vehicleControlSystem().order())
                .isEqualTo(VehicleControlSystem.ORDER)
                .isEqualTo(7);
        assertThat(scene.vehicleControlSystem().phase()).isEqualTo(Phase.SIM);
    }

    private PlayerInputComponent input() {
        return scene.world().getComponent(vehicle, PlayerInputComponent.class);
    }

    private Vector3 positionOf() {
        return scene.world().getComponent(vehicle, TransformComponent.class).position;
    }

    private float speed() {
        return scene.bodyOf(vehicle).getLinearVelocity().len();
    }
}
