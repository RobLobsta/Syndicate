/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.damage.FiringImpulse;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.VehicleProfiles;
import dev.syndicate.model.WeaponFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Recoil and knockback on the real physics world (docs/17_weapon_system.md#D17-S5.12).
 *
 * <p>T-D17-11 and T-D17-12. These are the tests that make "the cannon should apply impulse" a
 * measured claim rather than a described one: they fire the formula at a real spawned vehicle on a
 * real Bullet world and read what the body did.
 */
@Tag("physics")
class WeaponImpulsePhysicsTest {

    /** Where a bonnet-mounted gun's muzzle sits relative to a vehicle's centre of mass. */
    private static final Vector3 MUZZLE_OFFSET = new Vector3(0f, 0.5f, 2.0f);

    private static Vector3 velocityOf(World world, int vehicleEntity) {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        return new Vector3(body.body.getLinearVelocity());
    }

    private static Vector3 angularVelocityOf(World world, int vehicleEntity) {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        return new Vector3(body.body.getAngularVelocity());
    }

    private static Vector3 muzzleWorld(World world, int vehicleEntity, Vector3 offset) {
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        Vector3 com = new Vector3();
        body.body.getWorldTransform().getTranslation(com);
        return com.add(offset);
    }

    @Test
    @DisplayName("T-D17-11: a cannon shot moves the vehicle that fired it; an autocannon barely does")
    void cannonRecoilIsFeltAndAutocannonRecoilIsNot() {
        try (ShippedContentScene scene = new ShippedContentScene(1L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30); // let it settle on its suspension before measuring anything

            Vector3 forward = new Vector3(0f, 0f, 1f);
            Vector3 before = velocityOf(scene.world(), vehicle);

            FiringImpulse.queueRecoil(
                    scene.physics(),
                    scene.world(),
                    vehicle,
                    WeaponFamily.CANNON,
                    250f,
                    forward,
                    muzzleWorld(scene.world(), vehicle, MUZZLE_OFFSET));
            scene.step();
            float cannonDelta = velocityOf(scene.world(), vehicle).sub(before).len();

            scene.step(60); // let it settle again, so the second measurement starts from rest
            before = velocityOf(scene.world(), vehicle);
            FiringImpulse.queueRecoil(
                    scene.physics(),
                    scene.world(),
                    vehicle,
                    WeaponFamily.AUTOCANNON,
                    600f,
                    forward,
                    muzzleWorld(scene.world(), vehicle, MUZZLE_OFFSET));
            scene.step();
            float autocannonDelta =
                    velocityOf(scene.world(), vehicle).sub(before).len();

            assertThat(cannonDelta)
                    .as("a 3,000 N·s shell on a 1.5 t car is a shove a player can feel")
                    .isGreaterThan(0.5f);
            assertThat(cannonDelta / Math.max(1e-4f, autocannonDelta))
                    .as("the cannon must outkick the autocannon by at least an order of magnitude")
                    .isGreaterThan(10f);
        }
    }

    @Test
    @DisplayName("Recoil pushes the vehicle backwards, against the direction the shot left")
    void recoilActsAgainstTheShot() {
        try (ShippedContentScene scene = new ShippedContentScene(2L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30);

            Vector3 forward = new Vector3(0f, 0f, 1f);
            Vector3 before = velocityOf(scene.world(), vehicle);
            FiringImpulse.queueRecoil(
                    scene.physics(),
                    scene.world(),
                    vehicle,
                    WeaponFamily.CANNON,
                    250f,
                    forward,
                    muzzleWorld(scene.world(), vehicle, MUZZLE_OFFSET));
            scene.step();

            Vector3 delta = velocityOf(scene.world(), vehicle).sub(before);
            assertThat(delta.z).as("firing forwards pushes the car backwards").isNegative();
        }
    }

    @Test
    @DisplayName("D17-R57a: a rocket knocks its target back and does not kick its launcher")
    void rocketIsRecoillessInTheWorld() {
        try (ShippedContentScene scene = new ShippedContentScene(3L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30);

            Vector3 before = velocityOf(scene.world(), vehicle);
            float queued = FiringImpulse.queueRecoil(
                    scene.physics(),
                    scene.world(),
                    vehicle,
                    WeaponFamily.ROCKET,
                    120f,
                    new Vector3(0f, 0f, 1f),
                    muzzleWorld(scene.world(), vehicle, MUZZLE_OFFSET));
            scene.step();

            assertThat(queued).isZero();
            assertThat(velocityOf(scene.world(), vehicle).sub(before).len()).isLessThan(0.05f);
        }
    }

    @Test
    @DisplayName("T-D17-12: an off-centreline hit spins the target; a centreline hit shoves it straight")
    void knockbackOffTheCentrelineImpartsSpin() {
        try (ShippedContentScene scene = new ShippedContentScene(4L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30);

            RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);
            Vector3 com = new Vector3();
            body.body.getWorldTransform().getTranslation(com);

            // Into the front-left wing: a metre off the centreline, which is where a shell that spins
            // a car lands.
            Vector3 corner = new Vector3(com).add(-0.9f, 0f, 1.8f);
            Vector3 shotDirection = new Vector3(0f, 0f, -1f);

            Vector3 spinBefore = angularVelocityOf(scene.world(), vehicle);
            FiringImpulse.queueKnockback(
                    scene.physics(), scene.world(), vehicle, WeaponFamily.CANNON, 250f, shotDirection, corner);
            scene.step();
            float offCentreSpin =
                    angularVelocityOf(scene.world(), vehicle).sub(spinBefore).len();

            scene.step(90);
            body.body.getWorldTransform().getTranslation(com);
            Vector3 centreline = new Vector3(com).add(0f, 0f, 1.8f);
            spinBefore = angularVelocityOf(scene.world(), vehicle);
            FiringImpulse.queueKnockback(
                    scene.physics(), scene.world(), vehicle, WeaponFamily.CANNON, 250f, shotDirection, centreline);
            scene.step();
            float centrelineSpin =
                    angularVelocityOf(scene.world(), vehicle).sub(spinBefore).len();

            assertThat(offCentreSpin)
                    .as("a shell into a front wing has to visibly spin the car")
                    .isGreaterThan(0.1f);
            assertThat(offCentreSpin / Math.max(1e-4f, centrelineSpin))
                    .as("and it has to spin it far more than the same shell on the centreline")
                    .isGreaterThan(5f);
        }
    }

    @Test
    @DisplayName("An impulse is queued, not applied: nothing moves until the world steps")
    void impulsesAreQueuedUntilTheStep() {
        // DEC-012's whole point. Applying mid-schedule would make the result depend on where in the
        // system order the call happened rather than on the tick.
        try (ShippedContentScene scene = new ShippedContentScene(5L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30);

            Vector3 before = velocityOf(scene.world(), vehicle);
            FiringImpulse.queueRecoil(
                    scene.physics(),
                    scene.world(),
                    vehicle,
                    WeaponFamily.CANNON,
                    250f,
                    new Vector3(0f, 0f, 1f),
                    muzzleWorld(scene.world(), vehicle, MUZZLE_OFFSET));

            assertThat(velocityOf(scene.world(), vehicle))
                    .as("still untouched before the step")
                    .isEqualTo(before);

            scene.step();
            assertThat(velocityOf(scene.world(), vehicle)).isNotEqualTo(before);
        }
    }
}
