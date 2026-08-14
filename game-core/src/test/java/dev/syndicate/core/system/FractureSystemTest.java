/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.FractureDataComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 13 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/07_damage_destruction_model.md#D07-S5.6).
 */
@Tag("integration")
class FractureSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_01");
    private static final AssetId MANIFEST = AssetId.of("fracture_panel_front");

    private static final float CHASSIS_MASS_KG = 1200f;
    private static final float PLATE_MASS_KG = 320f;

    /** Even, so the outward scatter directions cancel and the momentum sum is checkable by hand. */
    private static final int SHARD_COUNT = 8;

    /** D14-S6.4's relative velocity tolerance, the bound PROG-004 applies to momentum inheritance. */
    private static final float VELOCITY_REL = 0.05f;

    private DestructionTestScene scene;
    private Family debris;
    private int vehicle;
    private int plate;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(1337L);
        scene.registerManifest(MANIFEST, AssetId.of("part_root_panel_front"), PLATE_MASS_KG, SHARD_COUNT);
        vehicle = scene.spawnVehicle(
                ASSEMBLY,
                List.of(
                        PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                        PartSpec.of("root/panel_front", PartCategory.PANEL, PLATE_MASS_KG, new Vector3(0f, 0f, 2f))
                                .fracturing(MANIFEST)),
                new Vector3(0f, 40f, 0f));
        plate = scene.partAt(vehicle, "root/panel_front");
        debris = scene.world().family(ComponentQuery.all(DebrisTagComponent.class));
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
    void system_occupiesSlot13OfPostSim() {
        // AC-D04-3. Slot 13 is after DamageSystem (12), which produces the DESTROYED state this
        // system reacts to, and before MassPropertySystem (15), which reconciles what it removes.
        assertThat(scene.fractureSystem().order()).isEqualTo(13);
        assertThat(scene.fractureSystem().phase()).isEqualTo(Phase.POST_SIM);
    }

    @Test
    void aDestroyedPart_becomesOneDebrisBodyPerShard() {
        // AC-D07-17: a fracture never spawns a partial shard set. A destroyed part with half its
        // pieces on the ground contradicts what the player just watched.
        scene.destroyPart(plate);

        scene.step();

        assertThat(debris.size()).isEqualTo(SHARD_COUNT);
        assertThat(scene.world().isAlive(plate)).isFalse();
    }

    @Test
    void shardMasses_comeFromTheManifest_andConserveThePartsMass() {
        // AC-D07-13 / G7. Shard masses are never recomputed at runtime (D07-R19): the manifest is
        // validated at load and re-derived independently by the harness, so conservation is an
        // asset-time guarantee rather than a second implementation free to disagree.
        scene.destroyPart(plate);
        scene.step();

        float total = 0f;
        int[] ids = debris.snapshot();
        for (int i = 0; i < debris.size(); i++) {
            total += scene.world().getComponent(ids[i], RigidBodyComponent.class).massKg;
        }
        assertThat(total).isEqualTo(PLATE_MASS_KG, within(PLATE_MASS_KG * SimulationConstants.MASS_TOLERANCE_FRAC));
    }

    @Test
    void theVehicleLosesThePartsMass_inTheSameTick() {
        // AC-D07-14 / G10. Fracture (13) removes the part, MassPropertySystem (15) reconciles, and
        // the next step never sees the old mass.
        scene.step();
        float before = scene.world().getComponent(vehicle, VehicleChassisComponent.class).totalMassKg;
        assertThat(before).isEqualTo(CHASSIS_MASS_KG + PLATE_MASS_KG, within(0.01f));

        scene.destroyPart(plate);
        scene.step();

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        assertThat(chassis.totalMassKg).isEqualTo(CHASSIS_MASS_KG, within(0.01f));
        assertThat(scene.world().getComponent(vehicle, RigidBodyComponent.class).massKg)
                .isEqualTo(CHASSIS_MASS_KG, within(0.01f));
        assertThat(scene.shapes().vehicleCompound(vehicle).childIndexOf("root/panel_front"))
                .isEqualTo(-1);
    }

    @Test
    void shardMomentum_sumsToThePartsMomentum() {
        // AC-D07-12 / PROG-004. Each shard inherits v_body + ω × r at its OWN position; using the
        // body's linear velocity alone would give every shard the same velocity and throw away the
        // rotational component, which is what makes a spinning vehicle's debris look right. The
        // scatter added on top is bounded at 1.6 m/s per shard and its outward directions cancel.
        scene.bodyOf(vehicle).setLinearVelocity(new Vector3(20f, 0f, 0f));
        scene.destroyPart(plate);

        scene.step();

        Vector3 momentum = new Vector3();
        int[] ids = debris.snapshot();
        for (int i = 0; i < debris.size(); i++) {
            VelocityComponent velocity = scene.world().getComponent(ids[i], VelocityComponent.class);
            float massKg = scene.world().getComponent(ids[i], RigidBodyComponent.class).massKg;
            momentum.mulAdd(velocity.linear, massKg);
        }

        // The parent's velocity at slot 13 is what it was after the step, gravity included.
        Vector3 parent = new Vector3(scene.world().getComponent(vehicle, VelocityComponent.class).linear);
        Vector3 expected = new Vector3(parent).scl(PLATE_MASS_KG);
        assertThat(momentum.dst(expected))
                .as("shard momentum %s against the part's %s", momentum, expected)
                .isLessThan(expected.len() * VELOCITY_REL);
    }

    @Test
    void fractureHappensExactlyOnce() {
        // AC-D07-11 / G9. hasFractured is one-way: a part whose health were somehow restored after
        // fracturing stays a pile of shards.
        scene.destroyPart(plate);
        scene.step();
        int afterFirst = debris.size();

        FractureDataComponent fractureData = scene.world().getComponent(plate, FractureDataComponent.class);
        assertThat(fractureData)
                .as("the part entity is destroyed once it has fractured")
                .isNull();

        scene.step(3);

        assertThat(debris.size()).isEqualTo(afterFirst);
    }

    @Test
    void aFracturedPartLeavesTheSlotGraphAsDetached() {
        // D05-S5.5 step 1: the part leaves the graph and its state moves to DETACHED before it is
        // destroyed, so replication sees the structural change rather than an entity that vanished.
        DamageStateComponent state = scene.world().getComponent(plate, DamageStateComponent.class);
        scene.destroyPart(plate);
        assertThat(state.state).isEqualTo(DamageState.DESTROYED);

        scene.fractureSystem().update(scene.world(), SimulationConstants.TICK_DT, 0L);

        assertThat(state.state).isEqualTo(DamageState.DETACHED);
        assertThat(scene.world().getComponent(vehicle, dev.syndicate.core.component.SlotGraphComponent.class).nodes)
                .isEmpty();
    }

    @Test
    void aPartWhoseManifestIsMissing_vanishesInsteadOfFracturing() {
        // D05-S4.4: a part with no manifest does not fracture, it vanishes. A manifest that failed to
        // load must produce the same outcome rather than an exception in the middle of a tick.
        FractureDataComponent fractureData = scene.world().getComponent(plate, FractureDataComponent.class);
        fractureData.manifestRef = AssetId.of("fracture_never_loaded");
        scene.destroyPart(plate);

        scene.step();

        assertThat(debris.size()).isZero();
        assertThat(scene.world().isAlive(plate)).isFalse();
    }

    @Test
    void theDebrisBudget_isNeverExceeded() {
        // AC-D06-17 / AC-D07-17 / D06-R28. The cap recycles the oldest rather than refusing to spawn,
        // so the newest destruction is always shown complete.
        try (DestructionTestScene big = new DestructionTestScene(4242L)) {
            AssetId manifest = AssetId.of("fracture_dense_plate");
            big.registerManifest(manifest, AssetId.of("part_root_plate_a"), 200f, 200);
            int assembly = big.spawnVehicle(
                    ASSEMBLY,
                    List.of(
                            PartSpec.of("root", PartCategory.CHASSIS, 900f, new Vector3()),
                            PartSpec.of("root/plate_a", PartCategory.PANEL, 200f, new Vector3(0f, 0f, 1.5f))
                                    .fracturing(manifest),
                            PartSpec.of("root/plate_b", PartCategory.PANEL, 200f, new Vector3(0f, 0f, -1.5f))
                                    .fracturing(manifest)),
                    new Vector3(0f, 60f, 0f));
            big.destroyPart(big.partAt(assembly, "root/plate_a"));
            big.destroyPart(big.partAt(assembly, "root/plate_b"));

            big.step();

            assertThat(big.debrisFactory().debrisCount()).isLessThanOrEqualTo(SimulationConstants.MAX_DEBRIS_BODIES);
        }
    }
}
