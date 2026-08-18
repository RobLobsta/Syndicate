/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.RotorBlock;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.RotorControllerComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A rotorcraft holds height, climbs, descends, and spins when its tail rotor is shot off
 * (docs/06_physics_simulation.md#D06-S5.4, DEC-090).
 *
 * <p>Four claims, and each is a different half of {@link RotorControl}. That neutral collective
 * hovers is the trim; that the stick moves it is the collective range; that losing the main rotor
 * drops it is the part being a part; and that losing the <em>tail</em> rotor spins it is the one
 * behaviour nothing in the code says out loud — it is what remains when the cancelling term is
 * removed, and a test is the only place that can be recorded.
 *
 * <p>Everything is measured against a synthetic assembly rather than the shipped Kestrel, so the
 * flight model is tested even when the art pipeline has not run.
 */
@Tag("physics")
class RotorFlightTest {

    /** Kilograms — a light helicopter's, near the Kestrel's own. */
    private static final float FUSELAGE_MASS_KG = 1400f;

    /** Newtons at full collective. About 1.6× weight, which is a real machine's margin. */
    private static final float MAIN_THRUST_N = 22_000f;

    /** Newtons of tail thrust. Enough to trim the disc's torque and leave authority to steer. */
    private static final float TAIL_THRUST_N = 2_400f;

    private static final float MAIN_RADIUS_M = 4.7f;

    /** Ticks to run. At D00's 1/60 s tick this is five seconds — long enough to settle. */
    private static final int SETTLE_TICKS = 300;

    /** Metres a hover may drift over five seconds before it is not a hover. */
    private static final float HOVER_TOLERANCE_M = 3.0f;

    /** Metres a <em>parked</em> aircraft may move over five seconds before it is not parked. */
    private static final float PARKED_DRIFT_TOLERANCE_M = 4.0f;

    /** Degrees of gradient to park on. Well inside what both shipped arenas generate. */
    private static final float PARKING_SLOPE_DEG = 12f;

    /** Metres a helicopter must gain in five seconds of full collective to have taken off. */
    private static final float TAKEOFF_CLIMB_M = 5.0f;

    private static List<DestructionTestScene.PartSpec> helicopter() {
        return List.of(
                DestructionTestScene.PartSpec.of("root", PartCategory.CHASSIS, FUSELAGE_MASS_KG, new Vector3())
                        .sized(new Vector3(0.8f, 0.9f, 3.0f)),
                DestructionTestScene.PartSpec.of(
                                "root/rotor_main", PartCategory.ROTOR, 60f, new Vector3(0f, 1.6f, 0.5f))
                        .lifting(RotorBlock.Role.MAIN, MAIN_RADIUS_M, MAIN_THRUST_N),
                DestructionTestScene.PartSpec.of(
                                "root/rotor_tail", PartCategory.ROTOR, 12f, new Vector3(0f, 0.9f, -4.2f))
                        .lifting(RotorBlock.Role.TAIL, 0.5f, TAIL_THRUST_N));
    }

    private static int spawn(DestructionTestScene scene, float altitudeM) {
        return scene.spawnVehicle(AssetId.of("assembly_test_heli"), helicopter(), new Vector3(0f, altitudeM, 0f));
    }

    private static float altitudeOf(DestructionTestScene scene, int vehicle) {
        RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);
        return body.body.getCenterOfMassPosition().y;
    }

    private static PlayerInputComponent inputOf(DestructionTestScene scene, int vehicle) {
        return scene.world().getComponent(vehicle, PlayerInputComponent.class);
    }

    /** The spawn path recognises a rotorcraft and collects both discs. */
    @Test
    void aVehicleWithAMainRotorIsARotorcraft() {
        try (DestructionTestScene scene = new DestructionTestScene(11L)) {
            int vehicle = spawn(scene, 40f);
            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);

            assertThat(chassis.isRotorcraft).isTrue();
            assertThat(chassis.rotorCount).isEqualTo(2);
            assertThat(chassis.wheelCount).isZero();

            RotorControllerComponent main = scene.world()
                    .getComponent(scene.partAt(vehicle, "root/rotor_main"), RotorControllerComponent.class);
            assertThat(main).isNotNull();
            assertThat(main.isMain).isTrue();
            assertThat(main.radiusM).isEqualTo(MAIN_RADIUS_M);
            assertThat(main.currentRpm).isGreaterThan(0f);
        }
    }

    /**
     * A helicopter resting on the ground stays in one piece.
     *
     * <p>The case the client found and no flying test covers: at rest the rotor's thrust is still
     * applied, at the hub, above the centre of mass — so any tilt becomes a moment, and a moment on
     * an aircraft sitting on a surface is a rocking motion that can put the disc into the ground.
     * A capture with <em>no input at all</em> caught the Kestrel at 2 of 3 parts with its main rotor
     * lying beside it.
     */
    @Test
    void aHelicopterAtRestOnTheGroundKeepsItsRotor() {
        try (DestructionTestScene scene = new DestructionTestScene(16L)) {
            scene.addGround();
            int vehicle = scene.spawnVehicle(AssetId.of("assembly_test_heli"), helicopter(), new Vector3(0f, 1.2f, 0f));
            int mainRotor = scene.partAt(vehicle, "root/rotor_main");

            scene.step(SETTLE_TICKS);

            assertThat(scene.world()
                            .getComponent(mainRotor, dev.syndicate.core.component.DamageStateComponent.class)
                            .state)
                    .as("a helicopter sitting still does not destroy its own rotor")
                    .isNotEqualTo(dev.syndicate.model.DamageState.DESTROYED);
        }
    }

    /**
     * A helicopter parked on a <em>slope</em> stays parked, and keeps its rotor (DISC-071).
     *
     * <p>The flat-ground case above passes even with the defect present, because the mechanism is
     * the tilt: hover trim thrusts along the aircraft's own up axis, so a level machine's thrust is
     * vertical and a tilted one's has a horizontal component of {@code weight × sin(θ)} with no
     * wheels, no suspension and no rolling resistance to oppose it. Before the fix this aircraft
     * skated 43.9 m down a 12° gradient in five seconds, given no input at all, and on real terrain
     * it rocked far enough to put its own 4.72 m disc into the hillside.
     */
    @Test
    void aHelicopterParkedOnASlopeStaysParked() {
        try (DestructionTestScene scene = new DestructionTestScene(17L)) {
            scene.addGround(PARKING_SLOPE_DEG);
            int vehicle = scene.spawnVehicle(AssetId.of("assembly_test_heli"), helicopter(), new Vector3(0f, 1.2f, 0f));
            int mainRotor = scene.partAt(vehicle, "root/rotor_main");

            scene.step(SETTLE_TICKS);

            assertThat(scene.world().getComponent(mainRotor, DamageStateComponent.class).state)
                    .as("a helicopter parked on a gradient does not destroy its own rotor")
                    .isNotEqualTo(DamageState.DESTROYED);

            Vector3 position = scene.world()
                    .getComponent(vehicle, RigidBodyComponent.class)
                    .body
                    .getCenterOfMassPosition();
            assertThat((float) Math.hypot(position.x, position.z))
                    .as("and it stays where it was parked rather than sliding downhill")
                    .isLessThan(PARKED_DRIFT_TOLERANCE_M);
        }
    }

    /**
     * The other half of DISC-071's fix: a parked helicopter still takes off when told to.
     *
     * <p>Disengaging the hover trim on the ground is only correct if raising the collective still
     * flies the aircraft — a fix that made it a permanent ornament would pass the test above and be
     * worse than the defect it cured. Measured from the same gradient, because a machine that can
     * only leave level ground is not fixed either.
     */
    @Test
    void aParkedHelicopterStillTakesOff() {
        try (DestructionTestScene scene = new DestructionTestScene(18L)) {
            scene.addGround(PARKING_SLOPE_DEG);
            int vehicle = scene.spawnVehicle(AssetId.of("assembly_test_heli"), helicopter(), new Vector3(0f, 1.2f, 0f));

            // Settle onto the slope first. The body is created by the spawn system on the first
            // step, so nothing can be read off the vehicle before then.
            scene.step(SETTLE_TICKS);
            float parked = altitudeOf(scene, vehicle);

            inputOf(scene, vehicle).collective = 1f;
            scene.step(SETTLE_TICKS);

            assertThat(altitudeOf(scene, vehicle) - parked)
                    .as("full collective lifts a parked helicopter off the ground")
                    .isGreaterThan(TAKEOFF_CLIMB_M);
            assertThat(scene.world()
                            .getComponent(scene.partAt(vehicle, "root/rotor_main"), DamageStateComponent.class)
                            .state)
                    .as("and it keeps its rotor on the way up")
                    .isNotEqualTo(DamageState.DESTROYED);
        }
    }

    /** Neutral collective holds height: the trim cancels weight (RotorControl §2). */
    @Test
    void neutralCollectiveHovers() {
        try (DestructionTestScene scene = new DestructionTestScene(12L)) {
            int vehicle = spawn(scene, 40f);
            float start = altitudeOf(scene, vehicle);

            inputOf(scene, vehicle).collective = 0f;
            scene.step(SETTLE_TICKS);

            assertThat(altitudeOf(scene, vehicle))
                    .as("a helicopter at neutral collective holds its height")
                    .isCloseTo(start, offset(HOVER_TOLERANCE_M));
        }
    }

    /** Full collective climbs, and full down descends, and they go opposite ways. */
    @Test
    void collectiveClimbsAndDescends() {
        float climbed;
        float descended;
        try (DestructionTestScene scene = new DestructionTestScene(13L)) {
            int vehicle = spawn(scene, 40f);
            float start = altitudeOf(scene, vehicle);
            inputOf(scene, vehicle).collective = 1f;
            scene.step(SETTLE_TICKS);
            climbed = altitudeOf(scene, vehicle) - start;
        }
        try (DestructionTestScene scene = new DestructionTestScene(13L)) {
            int vehicle = spawn(scene, 120f);
            float start = altitudeOf(scene, vehicle);
            inputOf(scene, vehicle).collective = -1f;
            scene.step(SETTLE_TICKS);
            descended = altitudeOf(scene, vehicle) - start;
        }

        assertThat(climbed).as("full collective climbs").isGreaterThan(HOVER_TOLERANCE_M);
        assertThat(descended).as("collective down descends").isLessThan(-HOVER_TOLERANCE_M);
    }

    /**
     * Destroying the main rotor stops the lift, and the aircraft falls.
     *
     * <p>The point of the rotor being a part rather than a number on the chassis. Nothing in
     * {@link RotorControl} tests for "destroyed and therefore falling": the disc simply stops being
     * summed, and gravity was acting all along.
     */
    @Test
    void losingTheMainRotorEndsTheFlight() {
        try (DestructionTestScene scene = new DestructionTestScene(14L)) {
            int vehicle = spawn(scene, 120f);
            float start = altitudeOf(scene, vehicle);
            inputOf(scene, vehicle).collective = 0f;

            scene.destroyPart(scene.partAt(vehicle, "root/rotor_main"));
            scene.step(SETTLE_TICKS);

            assertThat(altitudeOf(scene, vehicle))
                    .as("a helicopter with no main rotor does not hover")
                    .isLessThan(start - HOVER_TOLERANCE_M);
        }
    }

    /**
     * Destroying the tail rotor leaves the main rotor's torque uncancelled, and the fuselage spins.
     *
     * <p>Measured as yaw rate against a control run that kept its tail, because the absolute figure
     * depends on the inertia tensor the compound happens to produce and the claim is a comparison,
     * not a number.
     */
    @Test
    void losingTheTailRotorSpinsTheFuselage() {
        float withTail;
        float withoutTail;
        try (DestructionTestScene scene = new DestructionTestScene(15L)) {
            int vehicle = spawn(scene, 120f);
            scene.step(SETTLE_TICKS);
            withTail = Math.abs(scene.bodyOf(vehicle).getAngularVelocity().y);
        }
        try (DestructionTestScene scene = new DestructionTestScene(15L)) {
            int vehicle = spawn(scene, 120f);
            scene.destroyPart(scene.partAt(vehicle, "root/rotor_tail"));
            scene.step(SETTLE_TICKS);
            withoutTail = Math.abs(scene.bodyOf(vehicle).getAngularVelocity().y);
        }

        assertThat(withTail).as("a trimmed helicopter holds its heading").isLessThan(0.2f);
        assertThat(withoutTail)
                .as("with nothing to cancel the disc's torque, it spins")
                .isGreaterThan(withTail + 0.5f);
    }
}
