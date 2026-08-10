/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.ShippedContentScene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A wheel comes off a moving car, and the car keeps going on the other three
 * (docs/07_damage_destruction_model.md#D07-S5.7, docs/05_vehicle_part_system.md#D05-S5.5).
 *
 * <p>Every structural piece of this was already covered against synthetic box parts in
 * {@code DestructionTestScene}. What is new is that the vehicle is a real one, being driven, with a
 * live {@code btRaycastVehicle} under it — and that is exactly the configuration where the one
 * thing Bullet will not let us do bites. There is no {@code removeWheel}: a detached wheel's native
 * slot stays in the controller for the life of the vehicle (DEV-008). Left alone it keeps casting
 * its suspension ray from a corner with no wheel on it and pushing the car up on a spring that is
 * not there, which looks like a car driving on an invisible wheel. {@code PartDetachment} disarms
 * that slot instead, and this is where that is checked.
 */
@Tag("integration")
class WheelDetachTest {

    /** Metres per second the car is doing when the wheel goes. Fast enough to matter, slow enough to hold. */
    private static final float TEST_SPEED_MPS = 20f;

    @BeforeAll
    static void requireShippedContent() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets are not present");
    }

    @Test
    void aWheelLostAtSpeedBecomesDebrisAndTheCarDrivesOn() {
        try (ShippedContentScene scene = new ShippedContentScene(17L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(60);
            scene.accelerateTo(vehicle, TEST_SPEED_MPS, 12f);

            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
            assertThat(chassis.wheelCount).isEqualTo(4);
            int wheelEntity = chassis.wheelEntities[0];
            WheelControllerComponent wheel = scene.world().getComponent(wheelEntity, WheelControllerComponent.class);
            int nativeIndex = wheel.wheelIndex;

            // Hold the throttle through the detach: the claim is that the car keeps driving, not
            // that it survives being let off the power at the same moment.
            PlayerInputComponent input = scene.world().getComponent(vehicle, PlayerInputComponent.class);
            input.throttle = 1f;

            Vector3 beforeDetach = positionOf(scene, vehicle);
            scene.destroyPart(wheelEntity);
            scene.step(2);

            // ---- It left the vehicle -------------------------------------------------
            assertThat(chassis.wheelCount).as("three wheels left").isEqualTo(3);
            for (int i = 0; i < chassis.wheelCount; i++) {
                assertThat(chassis.wheelEntities[i])
                        .as("the detached wheel is out of the list")
                        .isNotEqualTo(wheelEntity);
            }

            // ---- The surviving wheels still address their own suspension --------------
            for (int i = 0; i < chassis.wheelCount; i++) {
                WheelControllerComponent survivor =
                        scene.world().getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
                assertThat(survivor.wheelIndex)
                        .as("survivor %d keeps its native wheel index", i)
                        .isNotEqualTo(nativeIndex);
            }

            // ---- The empty corner pushes on nothing (DEV-008) -------------------------
            assertThat(chassis.vehicleController.getWheelInfo(nativeIndex).getWheelsSuspensionForce())
                    .as("the vacated suspension applies no force")
                    .isLessThan(1f);

            // ---- It is a body of its own, carrying the car's velocity -----------------
            // The part entity is retired and a DEBRIS entity spawned in its place (D07-S5.7): a
            // detached part stops being part of a vehicle in the same tick it starts being a thing
            // in the world, and the two archetypes do not overlap.
            assertThat(scene.world().isAlive(wheelEntity))
                    .as("the part entity retires once its debris exists")
                    .isFalse();
            RigidBodyComponent debris = debrisBodyFrom(scene, wheelEntity);
            assertThat(debris).as("the wheel became a debris body (D07-S5.7)").isNotNull();
            assertThat(new Vector3(debris.body.getLinearVelocity()).len())
                    .as("debris leaves at something like the speed the car was doing")
                    .isGreaterThan(TEST_SPEED_MPS * 0.5f);

            // ---- And the car drives on ------------------------------------------------
            scene.step(120);
            input.throttle = 0f;
            float travelled = positionOf(scene, vehicle).sub(beforeDetach).len();
            assertThat(travelled).as("the car covered ground on three wheels").isGreaterThan(TEST_SPEED_MPS);
            assertThat(scene.wheelsInContact(vehicle))
                    .as("its three remaining wheels are still on the road")
                    .isEqualTo(3);
            assertThat(positionOf(scene, vehicle).y)
                    .as("it is driving, not launched")
                    .isLessThan(2f);
        }
    }

    /** The debris body spawned for a retired part, found by the back-reference it carries. */
    private static RigidBodyComponent debrisBodyFrom(ShippedContentScene scene, int sourcePartEntity) {
        World world = scene.world();
        Family debris = world.family(ComponentQuery.all(DebrisTagComponent.class, RigidBodyComponent.class));
        int[] entityIds = debris.snapshot();
        for (int i = 0; i < debris.size(); i++) {
            DebrisTagComponent tag = world.getComponent(entityIds[i], DebrisTagComponent.class);
            RigidBodyComponent body = world.getComponent(entityIds[i], RigidBodyComponent.class);
            if (tag != null && tag.sourcePartEntity == sourcePartEntity && body != null && body.body != null) {
                return body;
            }
        }
        return null;
    }

    private static Vector3 positionOf(ShippedContentScene scene, int entity) {
        RigidBodyComponent body = scene.world().getComponent(entity, RigidBodyComponent.class);
        Matrix4 world = new Matrix4();
        body.body.getWorldTransform(world);
        return world.getTranslation(new Vector3());
    }
}
