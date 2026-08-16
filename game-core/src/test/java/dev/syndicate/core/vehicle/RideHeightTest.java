/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.physics.ShippedContentScene;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A settled vehicle sits on the road with its wheels in its arches.
 *
 * <p>Trivial to state and easy to get wrong in a way nothing else notices. The shipped content
 * authored every wheel slot at {@code y: 0} until this test existed, which left the whole car body
 * 0.61 m in the air with its wheels dangling below it. Every calibration figure still passed —
 * acceleration and braking do not care how high the body is — so only a render would have shown it,
 * and there was no render.
 *
 * <p>Every tolerance here is a centimetre, and that is not optimism: a settled car on this content
 * parks its chassis origin within a millimetre of the road and holds it there, each axle at exactly
 * its own tyre's radius. Two things had to be true first. A slot's Y is the suspension's <em>top
 * mount</em> — Bullet's {@code connectionPointCS0} — so it belongs at the axle plus whatever the
 * static load leaves of the spring, not at the axle the art measured. And the ground had to stop
 * being an 800 m convex box, because Bullet ray-tests one of those to about 14 cm and a ray-cast
 * wheel is nothing but that ray (DISC-017). Before those two fixes the corners wandered through
 * 10 cm, which read as a suspension tuning problem and was not one.
 */
@Tag("unit")
class RideHeightTest {

    /**
     * The axles the preparation pipeline measured off the source art, in chassis-local metres.
     *
     * <p>Front pair first, then rear. The Eclipse's are unchanged from the retired dissection's;
     * the Stampede's moved about 3 cm outboard when a wheel's placement became the centre of the
     * wheel part rather than of an island that included its brake hub.
     */
    private static final Map<String, List<Vector3>> ART_AXLES = Map.of(
            "vehicle_eclipse_01",
                    List.of(new Vector3(0.8563f, 0.3559f, 1.4565f), new Vector3(0.8319f, 0.3594f, -1.2441f)),
            "vehicle_stampede_01",
                    List.of(new Vector3(0.8837f, 0.3552f, 1.3904f), new Vector3(0.8832f, 0.3620f, -1.3209f)));

    /**
     * Metres. Track and wheelbase are geometry; they should land where the art says.
     *
     * <p>Measured on a <em>settled</em> car, which is why this is not tighter. Bullet reports a
     * wheel's transform in world space, and a car whose rear axle carries more weight than its
     * front sits nose-up by a fraction of a degree — which walks a front wheel forward by that
     * angle times its axle height. On the Stampede that is 10 mm.
     */
    private static final float PLAN_TOLERANCE_M = 0.015f;

    /** Metres. The <em>mean</em> axle height is the ride height, and it should be the art's. */
    private static final float MEAN_HEIGHT_TOLERANCE_M = 0.01f;

    /**
     * Metres of spread between the highest and lowest corner of a settled car.
     *
     * <p>Not zero even on a perfectly level car: both of these have a larger tyre at the back than
     * at the front, so the rear axles settle 6 mm higher than the front ones by construction. What
     * this bounds is everything on top of that — a car that leans, or one whose wheels are still
     * moving four seconds after it stopped.
     */
    private static final float CORNER_SPREAD_TOLERANCE_M = 0.02f;

    /**
     * Metres the chassis body's origin may sit above or below the road.
     *
     * <p>The chassis mesh is authored with its origin on the ground at the centreline, so a settled
     * car's body origin belongs at zero. It was 0.61 before the slots were re-authored — the exact
     * failure this bounds.
     */
    private static final float BODY_ORIGIN_TOLERANCE_M = 0.01f;

    @BeforeAll
    static void requireShippedContent() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets are not present");
    }

    @Test
    void bothShippedVehiclesSettleOnTheirWheels() {
        for (VehicleProfile profile : VehicleProfiles.all()) {
            try (ShippedContentScene scene = new ShippedContentScene(11L)) {
                int vehicle = scene.spawnUnarmed(profile, new Vector3(0f, 0.05f, 0f));
                scene.step(240);

                VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
                RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);
                // The rigid body's origin is the centre of mass, not the chassis mesh's origin —
                // D06-S5.7 step 2 recentres the compound on the COM so the vehicle rotates about it.
                // Undoing that shift is what turns the body's world transform back into the place
                // the art puts the chassis, which is what this test is about.
                Vector3 bodyPos = new Vector3(chassis.comLocal).scl(-1f).mul(matrixOf(body));

                assertThat(bodyPos.y)
                        .as("%s body origin sits on the road, not above it", profile.displayName())
                        .isBetween(-BODY_ORIGIN_TOLERANCE_M, BODY_ORIGIN_TOLERANCE_M);

                List<Vector3> art = ART_AXLES.get(profile.profileId().value());
                assertThat(art)
                        .as("art axles recorded for %s", profile.profileId().value())
                        .isNotNull();
                assertThat(chassis.wheelCount).isEqualTo(4);

                float lowest = Float.MAX_VALUE;
                float highest = -Float.MAX_VALUE;
                float sum = 0f;
                float expectedMean = 0f;
                for (int i = 0; i < chassis.wheelCount; i++) {
                    WheelControllerComponent wheel =
                            scene.world().getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
                    Vector3 axle = new Vector3();
                    new Matrix4(chassis.vehicleController.getWheelTransformWS(wheel.wheelIndex)).getTranslation(axle);

                    Vector3 expected = axle.z > 0f ? art.get(0) : art.get(1);
                    String label = profile.displayName() + " wheel " + wheel.wheelIndex;
                    assertThat(Math.abs(axle.x))
                            .as("%s half-track", label)
                            .isCloseTo(expected.x, org.assertj.core.data.Offset.offset(PLAN_TOLERANCE_M));
                    assertThat(axle.z)
                            .as("%s longitudinal position", label)
                            .isCloseTo(expected.z, org.assertj.core.data.Offset.offset(PLAN_TOLERANCE_M));

                    lowest = Math.min(lowest, axle.y);
                    highest = Math.max(highest, axle.y);
                    sum += axle.y;
                    expectedMean += expected.y;
                }
                assertThat(sum / chassis.wheelCount)
                        .as("%s mean axle height is the art's ride height", profile.displayName())
                        .isCloseTo(
                                expectedMean / chassis.wheelCount,
                                org.assertj.core.data.Offset.offset(MEAN_HEIGHT_TOLERANCE_M));
                assertThat(highest - lowest)
                        .as("%s corner-to-corner spread", profile.displayName())
                        .isLessThan(CORNER_SPREAD_TOLERANCE_M);
            }
        }
    }

    /** The wheel radius comes off the collision mesh (DEC-022), so it should be the real tyre's. */
    @Test
    void wheelRadiiComeFromTheRealTyres() {
        try (ShippedContentScene scene = new ShippedContentScene(3L)) {
            int vehicle = scene.spawnUnarmed(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(2);
            VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
            for (int i = 0; i < chassis.wheelCount; i++) {
                WheelControllerComponent wheel =
                        scene.world().getComponent(chassis.wheelEntities[i], WheelControllerComponent.class);
                // 0.7068 m front and 0.7189 m rear, measured off the art in SESS-014.
                assertThat(wheel.radiusM).isBetween(0.35f, 0.36f);
            }
        }
    }

    private static Matrix4 matrixOf(RigidBodyComponent body) {
        Matrix4 out = new Matrix4();
        body.body.getWorldTransform(out);
        return out;
    }
}
