/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.RotorControllerComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.physics.ShippedContentScene;
import dev.syndicate.model.AssetId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The shipped Kestrel gets off the ground (DEC-090, DEC-093).
 *
 * <p>{@link RotorFlightTest} proves the flight model against a synthetic assembly; this proves the
 * <em>content</em>, which is a different claim and the one that was actually wrong first. The
 * aircraft flew in neither the harness nor the client until this test said which of the four links
 * between a {@code part.json} and a force had come apart — the block parsing, the category, the
 * spawn path's rotor collection, or the control branch.
 */
@Tag("physics")
class KestrelFlightTest {

    private static final AssetId KESTREL = AssetId.of("vehicle_kestrel_01");

    /** Ticks to run. At D00's 1/60 s tick, five seconds. */
    private static final int SETTLE_TICKS = 300;

    @BeforeAll
    static void requireShippedContent() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets are not present");
    }

    private static int spawnKestrel(ShippedContentScene scene, float altitudeM) {
        assertThat(scene.assets().assembly(KESTREL))
                .as("the Kestrel is in the shipped asset index")
                .isNotNull();
        return scene.spawn(KESTREL, new Vector3(0f, altitudeM, 0f));
    }

    /** Every link from the exported part.json through to the chassis flag. */
    @Test
    void theShippedKestrelLoadsAsARotorcraft() {
        try (ShippedContentScene scene = new ShippedContentScene(21L)) {
            int vehicle = spawnKestrel(scene, 60f);
            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);

            assertThat(chassis.isRotorcraft)
                    .as("the exported rotor block reached the spawn path")
                    .isTrue();
            assertThat(chassis.rotorCount).as("both discs were collected").isEqualTo(2);

            float mainThrust = 0f;
            for (int i = 0; i < chassis.rotorCount; i++) {
                RotorControllerComponent rotor =
                        scene.world().getComponent(chassis.rotorEntities[i], RotorControllerComponent.class);
                assertThat(rotor).as("every rotor part carries a controller").isNotNull();
                assertThat(rotor.currentRpm).as("a rotor spawns at speed").isGreaterThan(0f);
                if (rotor.isMain) {
                    mainThrust = thrustOf(scene, chassis.rotorEntities[i]);
                    assertThat(rotor.bladeCount).as("three blades, as measured").isEqualTo(3);
                    assertThat(rotor.radiusM)
                            .as("the swept radius, not the box")
                            .isGreaterThan(4.5f);
                }
            }

            // The claim the whole aircraft rests on: the disc lifts more than the aircraft weighs.
            float weightN = chassis.totalMassKg * RotorControl.GRAVITY_MPS2;
            assertThat(mainThrust)
                    .as("the main rotor out-lifts the airframe's weight, or it never leaves the ground")
                    .isGreaterThan(weightN);
        }
    }

    /** Full collective takes it off the ground; neutral holds it there. */
    @Test
    void theKestrelClimbsOnCollectiveAndThenHolds() {
        try (ShippedContentScene scene = new ShippedContentScene(22L)) {
            int vehicle = spawnKestrel(scene, 30f);
            PlayerInputComponent input = scene.world().getComponent(vehicle, PlayerInputComponent.class);

            float start = altitudeOf(scene, vehicle);
            input.collective = 1f;
            scene.step(SETTLE_TICKS);
            float climbed = altitudeOf(scene, vehicle);
            assertThat(climbed - start).as("full collective climbs").isGreaterThan(10f);

            // Released to neutral, the aircraft settles rather than stopping dead: it is
            // carrying ten metres a second of climb and the damping has to bleed that off, which
            // takes a second or two and covers about fifteen metres. So the claim is that it
            // *settles*, measured by comparing two later samples with each other rather than by
            // comparing the first one against the moment of release.
            input.collective = 0f;
            scene.step(SETTLE_TICKS);
            float settled = altitudeOf(scene, vehicle);
            scene.step(SETTLE_TICKS);
            assertThat(altitudeOf(scene, vehicle))
                    .as("having settled, neutral collective holds height")
                    .isCloseTo(settled, org.assertj.core.data.Offset.offset(2f));
            assertThat(settled)
                    .as("it settled above where it started, not back on the floor")
                    .isGreaterThan(start + 10f);
        }
    }

    /**
     * A helicopter is bound by the same speed clamp as everything else (D06-S5.5, D06-R5).
     *
     * <p>It was not, at first: the rotorcraft branch of {@code VehicleControl.drive} returns before
     * {@code applyBodyForces}, which is where the clamp lived, so the Kestrel was the only thing in
     * the game with no speed bound at all — and the first flight duly recorded 165 km/h against a
     * 144 km/h limit. Airframe drag is not a substitute: it is a force, so a dive adds speed faster
     * than drag removes it.
     */
    @Test
    void theKestrelIsSpeedClampedLikeEverythingElse() {
        try (ShippedContentScene scene = new ShippedContentScene(23L)) {
            int vehicle = spawnKestrel(scene, 200f);
            PlayerInputComponent input = scene.world().getComponent(vehicle, PlayerInputComponent.class);
            input.collective = 1f;
            input.throttle = 1f;
            scene.step(SETTLE_TICKS * 4);

            Vector3 velocity = scene.world()
                    .getComponent(vehicle, RigidBodyComponent.class)
                    .body
                    .getLinearVelocity();
            float horizontalMps = (float) Math.hypot(velocity.x, velocity.z);
            assertThat(horizontalMps)
                    .as("horizontal speed is bounded by the arena's own clamp")
                    .isLessThanOrEqualTo(VehicleControl.MAX_VEHICLE_SPEED_MPS + 0.5f);
            assertThat(Math.abs(velocity.y))
                    .as("and the vertical axis by its own bound")
                    .isLessThanOrEqualTo(VehicleControl.MAX_AIRBORNE_VERTICAL_SPEED_MPS + 0.5f);
        }
    }

    private static float altitudeOf(ShippedContentScene scene, int vehicle) {
        return scene.world()
                .getComponent(vehicle, RigidBodyComponent.class)
                .body
                .getCenterOfMassPosition()
                .y;
    }

    private static float thrustOf(ShippedContentScene scene, int rotorEntity) {
        return scene.world()
                .getComponent(rotorEntity, dev.syndicate.core.component.PartStatsComponent.class)
                .effectiveStats
                .resolve(StatBlock.Stat.ROTOR_THRUST_N, 0f);
    }
}
