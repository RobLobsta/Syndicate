/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.VehicleControl;
import dev.syndicate.core.vehicle.VehicleProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The anti-tunnelling speed clamp against gravity (docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>DISC-063: the first scripted drive of the desert launched off a dune at the grounded top speed
 * and was still airborne 130 frames later, reading exactly 40 m/s in every capture. The clamp scales
 * the <em>whole</em> velocity vector, so a car already at the horizontal limit had every metre per
 * second gravity added taken straight back out again — it could not accelerate downward, so it did
 * not come down.
 *
 * <p>These are the tests that make the fix a measured claim: airborne, the vertical axis is left to
 * gravity and only the horizontal component is bounded.
 */
@Tag("physics")
class AirborneSpeedClampTest {

    /** Metres. High enough that two seconds of free fall does not reach the ground. */
    private static final float LAUNCH_HEIGHT_M = 80f;

    /** Ticks in two seconds at {@code TICK_DT}. */
    private static final int TWO_SECONDS_OF_TICKS = 120;

    private static Vector3 velocityOf(World world, int vehicleEntity) {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        return new Vector3(body.body.getLinearVelocity());
    }

    private static void setVelocity(World world, int vehicleEntity, Vector3 velocity) {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        body.body.activate();
        body.body.setLinearVelocity(velocity);
    }

    /**
     * DISC-063: a falling car gets faster, and the clamp does not hold its <em>total</em> speed.
     *
     * <p>This is the assertion that discriminates, and the obvious one does not. The pre-fix clamp
     * did not stop the car descending — it rescaled the velocity vector to 40 m/s every tick while
     * gravity kept adding to the vertical term, so the vector <b>rotated</b> downward at constant
     * magnitude. The car fell; it simply could never go faster than 40 m/s doing it, which is
     * exactly what every capture in DISC-063 showed. So "does it fall" passes under both behaviours
     * and proves nothing: the signature is total speed pinned at the limit.
     *
     * <p>Two seconds of free fall from 40 m/s horizontal is
     * {@code hypot(40, 19.6) = 44.5 m/s}. Anything above the limit at all can only come from
     * gravity being allowed to do its work.
     */
    @Test
    @DisplayName("airborne, total speed passes the limit as the vehicle falls")
    void anAirborneVehicleAcceleratesPastTheLimitUnderGravity() {
        try (ShippedContentScene scene = new ShippedContentScene(1L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, LAUNCH_HEIGHT_M, 0f));
            // Launched flat out horizontally, which is what coming off a dune windward face looks
            // like to the clamp: already at the limit, with nothing left for gravity to add.
            setVelocity(scene.world(), vehicle, new Vector3(VehicleControl.MAX_VEHICLE_SPEED_MPS, 0f, 0f));
            scene.step(TWO_SECONDS_OF_TICKS);

            assertThat(scene.wheelsInContact(vehicle))
                    .as("still airborne, so the clamp under test is the airborne one")
                    .isZero();
            assertThat(scene.speedOf(vehicle))
                    .as("total speed is not pinned at the anti-tunnelling limit in flight (DISC-063)")
                    .isGreaterThan(VehicleControl.MAX_VEHICLE_SPEED_MPS + 2f);
        }
    }

    /**
     * The horizontal term is still bounded airborne, and — the discriminating half — it does not
     * <em>decay</em> either.
     *
     * <p>Under the pre-fix clamp a launched car's horizontal speed bled away as the fixed-magnitude
     * vector rotated toward straight down, so a jump cost forward progress that no force in the
     * world had taken. Bounded above by the limit and still close to it is what a ballistic car
     * should look like.
     */
    @Test
    @DisplayName("airborne, horizontal speed is held at the limit and does not bleed away")
    void anAirborneVehicleKeepsItsHorizontalSpeedAtTheLimit() {
        try (ShippedContentScene scene = new ShippedContentScene(1L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, LAUNCH_HEIGHT_M, 0f));
            // Well over the limit, in both horizontal axes so the clamp cannot be passing by
            // accident on one of them.
            setVelocity(scene.world(), vehicle, new Vector3(90f, 0f, 90f));
            scene.step(TWO_SECONDS_OF_TICKS);

            Vector3 velocity = velocityOf(scene.world(), vehicle);
            float horizontalMps = (float) Math.hypot(velocity.x, velocity.z);
            assertThat(scene.wheelsInContact(vehicle)).isZero();
            assertThat(horizontalMps)
                    .as("the term that can tunnel a chassis through a wall is still bounded")
                    .isLessThanOrEqualTo(VehicleControl.MAX_VEHICLE_SPEED_MPS + 0.5f);
            assertThat(horizontalMps)
                    .as("and gravity does not steal it by rotating the vector downward (DISC-063)")
                    .isGreaterThan(VehicleControl.MAX_VEHICLE_SPEED_MPS - 2f);
        }
    }

    /**
     * DISC-067: airborne is bounded, not unbounded. The regression the previous test let through.
     *
     * <p>The first drive after the horizontal-only clamp shipped read <b>1167 km/h</b>, because
     * leaving the vertical axis "to gravity" had been implemented as leaving it untouched — and
     * gravity is not the only thing that writes it. A collision impulse at spawn wrote 324 m/s and
     * nothing took it back out.
     *
     * <p>The lesson is in the assertion above, which reads {@code isGreaterThan(MAX + 2)} and is
     * satisfied just as happily by 44 m/s as by 324. A one-sided bound on a quantity whose bug is
     * runaway growth cannot fail.
     */
    @Test
    @DisplayName("airborne, a violent impulse is still bounded on the vertical axis")
    void anAirborneVehicleIsBoundedVertically() {
        try (ShippedContentScene scene = new ShippedContentScene(1L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, LAUNCH_HEIGHT_M, 0f));
            // What a penetration-resolution impulse looks like: far beyond anything gravity or an
            // engine could produce, on the axis the horizontal clamp does not touch.
            setVelocity(scene.world(), vehicle, new Vector3(0f, 400f, 0f));
            scene.step(2);

            assertThat(scene.wheelsInContact(vehicle)).isZero();
            assertThat(Math.abs(velocityOf(scene.world(), vehicle).y))
                    .as("vertical speed is bounded, not merely left alone (DISC-067)")
                    .isLessThanOrEqualTo(VehicleControl.MAX_AIRBORNE_VERTICAL_SPEED_MPS + 0.5f);
            assertThat(scene.speedOf(vehicle))
                    .as("and so total speed cannot run away")
                    .isLessThan(100f);
        }
    }

    /**
     * Grounded, the clamp is unchanged — the whole vector is scaled, exactly as before.
     *
     * <p>This is the assertion that protects the seed-locked regressions of D12-S4.2: the fix is
     * meant to be invisible to anything with a wheel down.
     */
    @Test
    @DisplayName("grounded, total speed is still clamped to the limit")
    void aGroundedVehicleIsClampedOnTotalSpeed() {
        try (ShippedContentScene scene = new ShippedContentScene(1L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30); // settle onto the suspension

            setVelocity(scene.world(), vehicle, new Vector3(90f, 0f, 0f));
            scene.step(5);

            assertThat(scene.wheelsInContact(vehicle))
                    .as("on the ground, so the grounded branch is the one under test")
                    .isGreaterThan(0);
            assertThat(scene.speedOf(vehicle))
                    .as("D06-S5.5's clamp still holds a grounded vehicle at the limit")
                    .isLessThanOrEqualTo(VehicleControl.MAX_VEHICLE_SPEED_MPS + 0.5f);
        }
    }
}
