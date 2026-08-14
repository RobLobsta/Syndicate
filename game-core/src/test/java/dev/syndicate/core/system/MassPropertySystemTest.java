/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.damage.DetachReason;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.core.physics.VehicleCompound;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.PartDetachment;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 15 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/06_physics_simulation.md#D06-S5.7).
 */
@Tag("integration")
class MassPropertySystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_01");

    /** T-D06-6's numbers: a 340 kg plate on a 1600 kg vehicle leaves 1260 kg. */
    private static final float CHASSIS_MASS_KG = 1260f;

    private static final float PLATE_MASS_KG = 340f;
    private static final float PLATE_OFFSET_Z_M = 2f;

    private DestructionTestScene scene;
    private int vehicle;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(1337L);
        vehicle = scene.spawnVehicle(
                ASSEMBLY,
                List.of(
                        PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                        PartSpec.of(
                                "root/panel_front",
                                PartCategory.PANEL,
                                PLATE_MASS_KG,
                                new Vector3(0f, 0f, PLATE_OFFSET_Z_M))),
                new Vector3(0f, 20f, 0f));
    }

    @AfterEach
    void tearDown() {
        scene.close();
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void system_occupiesSlot15OfPostSim() {
        // AC-D04-3: the order is a compile-time constant from the D04-S4.4 catalogue, not registration
        // order. Slot 15 is after both structural systems (13, 14) and before the next tick's step,
        // which is what makes G10 hold.
        assertThat(scene.massPropertySystem().order()).isEqualTo(15);
        assertThat(scene.massPropertySystem().phase()).isEqualTo(Phase.POST_SIM);
    }

    @Test
    void totalMassAndCom_areSummedOverTheLiveParts() {
        // D06-S5.7 step 1. The COM of a 1260 kg chassis at the origin and a 340 kg plate 2 m forward
        // is 340 * 2 / 1600 = 0.425 m forward.
        recompute();

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        assertThat(chassis.totalMassKg).isEqualTo(CHASSIS_MASS_KG + PLATE_MASS_KG, within(0.01f));
        assertThat(chassis.comLocal.z)
                .isEqualTo(PLATE_MASS_KG * PLATE_OFFSET_Z_M / (CHASSIS_MASS_KG + PLATE_MASS_KG), within(1e-4f));
        assertThat(chassis.comLocal.x).isZero();
        assertThat(chassis.comLocal.y).isZero();
    }

    @Test
    void detachingAPlate_dropsTheMassAndMovesTheCom_inTheSameTick() {
        // T-D06-6 / AC-D06-8 / AC-D07-14. The whole point of slot 15 is that this happens before the
        // next physics step: a vehicle is never stepped with the mass it had before it lost a part.
        recompute();
        int plate = scene.partAt(vehicle, "root/panel_front");

        PartDetachment.detach(scene.world(), scene.shapes(), vehicle, plate, DetachReason.DESTROYED, 0L);
        recompute();

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);
        assertThat(chassis.totalMassKg).isEqualTo(CHASSIS_MASS_KG, within(0.01f));
        assertThat(body.massKg).isEqualTo(CHASSIS_MASS_KG, within(0.01f));
        // Only the chassis is left, and its own centre of mass is its origin.
        assertThat(chassis.comLocal.epsilonEquals(0f, 0f, 0f, 1e-4f)).isTrue();
        assertThat(scene.bodyOf(vehicle).getInvMass()).isEqualTo(1f / CHASSIS_MASS_KG, within(1e-6f));
    }

    @Test
    void detachment_doesNotTouchTheVehiclesVelocity() {
        // AC-D06-10 / D05-R23. Changing mass while preserving velocity changes momentum, and that is
        // physically correct for mass that LEAVES: the plate carries its own momentum away as debris.
        // "Correcting" the vehicle's velocity here would create or destroy momentum instead.
        recompute();
        btRigidBody body = scene.bodyOf(vehicle);
        body.setLinearVelocity(new Vector3(12f, 0f, -3f));
        body.setAngularVelocity(new Vector3(0f, 0.8f, 0f));

        PartDetachment.detach(
                scene.world(),
                scene.shapes(),
                vehicle,
                scene.partAt(vehicle, "root/panel_front"),
                DetachReason.DESTROYED,
                0L);
        recompute();

        assertThat(body.getLinearVelocity().epsilonEquals(12f, 0f, -3f, 1e-5f)).isTrue();
        assertThat(body.getAngularVelocity().epsilonEquals(0f, 0.8f, 0f, 1e-5f)).isTrue();
    }

    @Test
    void theCompoundIsRecentredOnTheCom_andTheVehicleDoesNotMove() {
        // AC-D06-9. Bullet treats a compound's local origin as the centre of mass, so the compound is
        // shifted and the body's world transform is shifted the opposite way — the geometry stays
        // exactly where it was, and only what the body rotates about changes.
        VehicleCompound compound = scene.shapes().vehicleCompound(vehicle);
        int chassisChild = compound.childIndexOf("root");
        Vector3 chassisWorldBefore = childWorldPosition(compound, chassisChild);

        recompute();

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        Vector3 chassisLocalAfter = new Vector3();
        compound.compound().getChildTransform(chassisChild).getTranslation(chassisLocalAfter);
        // The chassis child sat at the mesh origin; after recentring it sits at −COM.
        assertThat(chassisLocalAfter.epsilonEquals(
                        -chassis.comLocal.x, -chassis.comLocal.y, -chassis.comLocal.z, 1e-5f))
                .isTrue();

        Vector3 chassisWorldAfter = childWorldPosition(compound, chassisChild);
        assertThat(chassisWorldAfter.epsilonEquals(chassisWorldBefore, 1e-4f))
                .as("geometry moved from %s to %s", chassisWorldBefore, chassisWorldAfter)
                .isTrue();
    }

    @Test
    void anUnchangedStructure_isNotRewrittenEveryTick() {
        // D06-S5.7 runs "whenever structuralVersion changed". The stored mass and COM are the
        // comparison, and the sum is deterministic, so an untouched vehicle produces bit-identical
        // values and nothing is written to Bullet a second time.
        recompute();
        VehicleStatsComponent stats = scene.world().getComponent(vehicle, VehicleStatsComponent.class);
        stats.dirty = false;

        recompute();

        assertThat(stats.dirty)
                .as("a vehicle whose structure did not change must not be re-aggregated")
                .isFalse();
    }

    @Test
    void aStructuralChange_bumpsTheVersionAndMarksStatsDirty() {
        // D05-R12: every cache derived from the graph — compound, aggregated stats, mass properties,
        // coverage — is invalidated by a version change and recomputed in the same tick (G10).
        SlotGraphComponent graph = scene.world().getComponent(vehicle, SlotGraphComponent.class);
        int versionBefore = graph.structuralVersion;

        PartDetachment.detach(
                scene.world(),
                scene.shapes(),
                vehicle,
                scene.partAt(vehicle, "root/panel_front"),
                DetachReason.DESTROYED,
                4L);

        assertThat(graph.structuralVersion).isEqualTo(versionBefore + 1);
        assertThat(scene.world().getComponent(vehicle, VehicleStatsComponent.class).dirty)
                .isTrue();
        assertThat(graph.nodes).isEmpty();
    }

    private void recompute() {
        scene.massPropertySystem().update(scene.world(), SimulationConstants.TICK_DT, scene.tick());
    }

    private Vector3 childWorldPosition(VehicleCompound compound, int childIndex) {
        Matrix4 bodyWorld = new Matrix4();
        scene.bodyOf(vehicle).getWorldTransform(bodyWorld);
        Matrix4 child = new Matrix4(compound.compound().getChildTransform(childIndex));
        return bodyWorld.mul(child).getTranslation(new Vector3());
    }
}
