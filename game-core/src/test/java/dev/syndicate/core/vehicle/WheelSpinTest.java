/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.physics.ShippedContentScene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A driven wheel turns, and it turns by the distance the car covered
 * (docs/06_physics_simulation.md#D06-S5.5, docs/04_entity_component_model.md#D04-S4.4 slot 21).
 *
 * <p>The two halves of that sentence are separate claims and this checks both. That a wheel rotates
 * at all is a question about {@code TransformSystem}: a ray-cast wheel's placement lives inside
 * {@code btRaycastVehicle} and not in the slot graph, so a world matrix composed the ordinary way
 * gives a wheel that slides down the road without moving. That it rotates by the <em>right</em>
 * amount is a question about the wheel radius, which {@code VehicleFactory} reads off the collision
 * mesh (DEC-022): a wheel whose radius is wrong still spins, just at a speed that does not match the
 * ground going past, and nothing else in the simulation would notice.
 *
 * <p>Rotation is measured in the chassis's own frame rather than the world's, so the car's pitch
 * under acceleration does not read as wheel rotation.
 */
@Tag("integration")
class WheelSpinTest {

    /**
     * Fraction by which measured roll may differ from distance / radius.
     *
     * <p>Ten per cent is slip, not slack. A driven wheel on full throttle spins faster than the
     * ground goes past — that is what traction is — and Bullet's ray-cast model reproduces it. The
     * number that matters is that the two are within sight of each other, because the failure this
     * catches is a wheel that does not turn at all or turns by an order of magnitude too much.
     */
    private static final float ROLL_TOLERANCE_FRAC = 0.10f;

    @BeforeAll
    static void requireShippedContent() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets are not present");
    }

    /** T-D06-5's cousin: roll the car forward and check the wheels went round the right number of times. */
    @Test
    void wheelsRollThroughTheDistanceTheCarTravelled() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            try (ShippedContentScene scene = new ShippedContentScene(5L)) {
                int vehicle = scene.spawn(profile, new Vector3(0f, 0.05f, 0f));
                scene.step(60);

                VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
                int wheelEntity = chassis.wheelEntities[0];
                WheelControllerComponent wheel =
                        scene.world().getComponent(wheelEntity, WheelControllerComponent.class);

                PlayerInputComponent input = scene.world().getComponent(vehicle, PlayerInputComponent.class);
                input.throttle = 1f;

                Vector3 start = bodyPosition(scene, vehicle);
                float previousAngle = axleAngleOf(scene, vehicle, wheelEntity);
                float rolledRad = 0f;
                for (int i = 0; i < 180; i++) {
                    scene.step();
                    float angle = axleAngleOf(scene, vehicle, wheelEntity);
                    rolledRad += unwrap(angle - previousAngle);
                    previousAngle = angle;
                }
                input.throttle = 0f;

                float travelledM = bodyPosition(scene, vehicle).sub(start).len();
                float expectedRad = travelledM / wheel.radiusM;

                assertThat(travelledM)
                        .as("%s covered ground to roll over", profile.displayName())
                        .isGreaterThan(10f);
                assertThat(Math.abs(rolledRad))
                        .as(
                                "%s front wheel roll over %.1f m at r=%.3f m",
                                profile.displayName(), travelledM, wheel.radiusM)
                        .isBetween(expectedRad * (1f - ROLL_TOLERANCE_FRAC), expectedRad * (1f + ROLL_TOLERANCE_FRAC));
            }
        }
    }

    /** A stationary car's wheels do not turn — the same measurement, run against no motion at all. */
    @Test
    void aParkedCarsWheelsDoNotTurn() {
        try (ShippedContentScene scene = new ShippedContentScene(5L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(120);

            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
            float before = axleAngleOf(scene, vehicle, chassis.wheelEntities[0]);
            scene.step(120);
            float after = axleAngleOf(scene, vehicle, chassis.wheelEntities[0]);

            assertThat(Math.abs(unwrap(after - before)))
                    .as("a parked Eclipse's wheel stays put")
                    .isLessThan(0.01f);
        }
    }

    /**
     * Steering yaws the front wheels in the chassis's frame and leaves the rear ones alone.
     *
     * <p>The other half of what {@code getWheelTransformWS} carries that a slot transform does not.
     */
    @Test
    void steeringTurnsTheFrontWheelsOnly() {
        try (ShippedContentScene scene = new ShippedContentScene(5L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(60);

            PlayerInputComponent input = scene.world().getComponent(vehicle, PlayerInputComponent.class);
            input.steer = 1f;
            scene.step(120);

            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
            for (int i = 0; i < chassis.wheelCount; i++) {
                int wheelEntity = chassis.wheelEntities[i];
                WheelControllerComponent wheel =
                        scene.world().getComponent(wheelEntity, WheelControllerComponent.class);
                // The axle points along the wheel's local X, so a steered wheel's axle has swung out
                // of the chassis's X axis by the steering angle.
                Vector3 axle = new Vector3(1f, 0f, 0f).rot(inChassisFrame(scene, vehicle, wheelEntity));
                float yawRad = (float) Math.abs(Math.atan2(axle.z, Math.abs(axle.x)));
                if (wheel.isSteering) {
                    assertThat(yawRad)
                            .as("steering wheel %d has turned", wheel.wheelIndex)
                            .isGreaterThan(0.1f);
                } else {
                    assertThat(yawRad)
                            .as("non-steering wheel %d has not", wheel.wheelIndex)
                            .isLessThan(0.01f);
                }
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------------

    /**
     * The wheel's roll angle about its axle, in the chassis's frame.
     *
     * <p>Read off {@code TransformComponent.worldMatrix}, which is what {@code TransformSystem}
     * writes and what a renderer would draw — not off {@code btRaycastVehicle} directly, so a
     * regression in the system this exists to cover is a failure here.
     */
    private static float axleAngleOf(ShippedContentScene scene, int vehicle, int wheelEntity) {
        Vector3 up = new Vector3(0f, 1f, 0f).rot(inChassisFrame(scene, vehicle, wheelEntity));
        return (float) Math.atan2(up.z, up.y);
    }

    /** The wheel's world matrix expressed relative to the vehicle body, so body motion cancels. */
    private static Matrix4 inChassisFrame(ShippedContentScene scene, int vehicle, int wheelEntity) {
        RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);
        TransformComponent wheel = scene.world().getComponent(wheelEntity, TransformComponent.class);
        Matrix4 bodyWorld = new Matrix4();
        body.body.getWorldTransform(bodyWorld);
        return bodyWorld.inv().mul(wheel.worldMatrix);
    }

    private static Vector3 bodyPosition(ShippedContentScene scene, int vehicle) {
        RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);
        Matrix4 world = new Matrix4();
        body.body.getWorldTransform(world);
        return world.getTranslation(new Vector3());
    }

    /** Brings an angle difference back into (-pi, pi], so accumulating it counts whole turns. */
    private static float unwrap(float deltaRad) {
        float wrapped = deltaRad;
        while (wrapped > Math.PI) {
            wrapped -= 2f * (float) Math.PI;
        }
        while (wrapped <= -Math.PI) {
            wrapped += 2f * (float) Math.PI;
        }
        return wrapped;
    }
}
