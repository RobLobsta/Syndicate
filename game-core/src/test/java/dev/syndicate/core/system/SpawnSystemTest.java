/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.core.vehicle.SpawnRequest;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 5 and the vehicle instantiation it performs
 * (docs/04_entity_component_model.md#D04-S4.4, docs/05_vehicle_part_system.md#D05-S5.2).
 */
@Tag("integration")
class SpawnSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_raider");
    private static final float CHASSIS_MASS_KG = 1000f;
    private static final float TURRET_MASS_KG = 200f;
    private static final float WHEEL_MASS_KG = 50f;

    private DestructionTestScene scene;
    private Family vehicles;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(4242L);
        scene.registerAssembly(ASSEMBLY, raider());
        vehicles = scene.world().family(ComponentQuery.all(VehicleChassisComponent.class));
    }

    @AfterEach
    void tearDown() {
        scene.close();
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    /** Chassis, a turret with a barrel under it, and four wheels — enough to exercise every branch. */
    private static List<PartSpec> raider() {
        return List.of(
                PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                PartSpec.of("root/turret", PartCategory.WEAPON, TURRET_MASS_KG, new Vector3(0f, 0.6f, 0f)),
                PartSpec.of("root/turret/barrel", PartCategory.WEAPON, 40f, new Vector3(0f, 0f, 1.2f)),
                PartSpec.of("root/wheel_fl", PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(-1f, -0.4f, 1.4f)),
                PartSpec.of("root/wheel_fr", PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(1f, -0.4f, 1.4f)),
                PartSpec.of("root/wheel_rl", PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(-1f, -0.4f, -1.4f)),
                PartSpec.of("root/wheel_rr", PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(1f, -0.4f, -1.4f)));
    }

    @Test
    void system_occupiesSlot5OfPreSim() {
        // AC-D04-3. PRE_SIM, before everything that reads a vehicle, so one spawned this tick is
        // stepped this tick rather than sitting inert for one.
        assertThat(scene.spawnSystem().order()).isEqualTo(5);
        assertThat(scene.spawnSystem().phase()).isEqualTo(Phase.PRE_SIM);
    }

    @Test
    void aQueuedRequest_becomesAVehicleOnTheNextTick() {
        scene.spawnQueue().request(ASSEMBLY, new Matrix4().setToTranslation(0f, 5f, 0f), 0, 1);
        assertThat(vehicles.size()).isZero();

        scene.step();

        assertThat(vehicles.size()).isEqualTo(1);
        assertThat(scene.spawnSystem().spawnedCount()).isEqualTo(1);
        assertThat(scene.spawnQueue().isEmpty()).isTrue();
    }

    @Test
    void aRequestNamingAnUnloadedAssembly_isRefusedRatherThanThrown() {
        // D10-S4.6 / G16. An assembly id can reach slot 5 from a client's loadout choice, and a bad
        // one must not be able to abort a tick for everybody else.
        scene.spawnQueue().request(AssetId.of("assembly_does_not_exist"), new Matrix4(), 0, 1);

        scene.step();

        assertThat(vehicles.size()).isZero();
        assertThat(scene.spawnQueue().isEmpty()).isTrue();
    }

    @Test
    void requestsAreDrainedInAscendingSequence_whateverOrderTheyWereMadeIn() {
        // G3 / D04-R24. Entity ids are allocated in drain order, and two peers must allocate the
        // same ids to the same vehicles.
        scene.spawnQueue().enqueue(new SpawnRequest(ASSEMBLY, new Matrix4(), 0, 1, 7L));
        scene.spawnQueue().enqueue(new SpawnRequest(ASSEMBLY, new Matrix4(), 0, 1, 2L));

        List<SpawnRequest> drained = scene.spawnQueue().drain();

        assertThat(drained).extracting(SpawnRequest::sequence).containsExactly(2L, 7L);
    }

    @Test
    void aSpawnedVehicle_hasOnePartEntityPerPlacement_inTheSlotGraph() {
        // D05-R2 / D05-S5.2 step 1. One VEHICLE entity plus one PART entity per part, with the
        // chassis as the root that has no slot node of its own (D05-R10).
        int vehicle = spawnOne();

        SlotGraphComponent graph = scene.world().getComponent(vehicle, SlotGraphComponent.class);
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);

        assertThat(graph.nodes).hasSize(raider().size() - 1);
        assertThat(graph.nodes)
                .extracting(node -> node.slotPath)
                .containsExactlyInAnyOrder(
                        "root/turret",
                        "root/turret/barrel",
                        "root/wheel_fl",
                        "root/wheel_fr",
                        "root/wheel_rl",
                        "root/wheel_rr");
        assertThat(scene.world().isAlive(chassis.chassisPartEntity)).isTrue();
        assertThat(scene.world().getComponent(chassis.chassisPartEntity, PartRefComponent.class).slotPath)
                .isEqualTo("root");
    }

    @Test
    void aNestedPart_isParentedToItsOwnParent_notToTheChassis() {
        // D05-R10: the slot graph is a tree. The barrel hangs off the turret, so detaching the
        // turret has to take the barrel with it (T-D05-13).
        int vehicle = spawnOne();
        SlotGraphComponent graph = scene.world().getComponent(vehicle, SlotGraphComponent.class);

        int turret = scene.partAt(vehicle, "root/turret");
        SlotNode barrel = graph.nodes.stream()
                .filter(node -> node.slotPath.equals("root/turret/barrel"))
                .findFirst()
                .orElseThrow();

        assertThat(barrel.parentEntity).isEqualTo(turret);
        assertThat(graph.parentOf.get(barrel.childEntity)).isEqualTo(turret);
    }

    @Test
    void aNestedPartsPlacement_isTheProductOfTheSlotOffsetsAboveIt() {
        // D05-S4.3. The barrel is 1.2 m forward of a turret that is 0.6 m above the chassis, so it
        // sits at (0, 0.6, 1.2) in chassis-local space — re-authoring the turret slot moves it.
        int vehicle = spawnOne();
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        SlotGraphComponent graph = scene.world().getComponent(vehicle, SlotGraphComponent.class);

        Vector3 barrelLocal = new Vector3();
        SlotChain.of(graph, chassis).transformOf("root/turret/barrel").getTranslation(barrelLocal);

        assertThat(barrelLocal.epsilonEquals(0f, 0.6f, 1.2f, 1e-4f)).isTrue();
    }

    @Test
    void everyPart_carriesItsTypesMassHealthAndState() {
        // D05-S5.2 step 1. The type is the authority for the authored value; the components hold the
        // live one, which damage changes and the type never does.
        int vehicle = spawnOne();
        int turret = scene.partAt(vehicle, "root/turret");

        assertThat(scene.world().getComponent(turret, RigidBodyComponent.class).massKg)
                .isEqualTo(TURRET_MASS_KG, within(0.01f));
        // An attached part is geometry inside the vehicle's compound, not a body of its own (DEC-004).
        assertThat(scene.world().getComponent(turret, RigidBodyComponent.class).body)
                .isNull();
        HealthComponent health = scene.world().getComponent(turret, HealthComponent.class);
        assertThat(health.currentHp).isEqualTo(health.maxHp);
        assertThat(scene.world().getComponent(turret, DamageStateComponent.class).state)
                .isEqualTo(DamageState.INTACT);
    }

    @Test
    void theVehicleBody_carriesTheSummedMassOfEveryPart() {
        // D05-S5.2 step 2. Wheels are included: they are unsprung mass carried by the body, even
        // though they contribute no collision geometry (D06-R6).
        int vehicle = spawnOne();
        float expected = CHASSIS_MASS_KG + TURRET_MASS_KG + 40f + 4 * WHEEL_MASS_KG;

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        RigidBodyComponent body = scene.world().getComponent(vehicle, RigidBodyComponent.class);

        assertThat(chassis.totalMassKg).isEqualTo(expected, within(0.01f));
        assertThat(body.massKg).isEqualTo(expected, within(0.01f));
        assertThat(body.body.getInvMass()).isEqualTo(1f / expected, within(1e-6f));
    }

    @Test
    void massPropertySystem_findsNothingToChangeOnTheSpawnTick() {
        // DEC-021. The factory establishes the mass properties as part of building the body, so slot
        // 15 compares equal and does nothing; the first value it sees differ is a detach's.
        int vehicle = spawnOne();
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        float massAtSpawn = chassis.totalMassKg;
        Vector3 comAtSpawn = new Vector3(chassis.comLocal);

        scene.step(3);

        assertThat(chassis.totalMassKg).isEqualTo(massAtSpawn);
        assertThat(chassis.comLocal.epsilonEquals(comAtSpawn, 1e-6f)).isTrue();
    }

    @Test
    void wheels_areAddedToTheRaycastControllerInAscendingSlotPathOrder() {
        // D05-S5.2 step 3 / G3. The Bullet wheel index is load-bearing: VehicleControlSystem steers
        // and drives by it, so an ordering that depended on attach history would steer the wrong
        // wheels on one peer and not the other.
        int vehicle = spawnOne();
        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);

        assertThat(chassis.wheelCount).isEqualTo(4);
        assertThat(chassis.vehicleController).isNotNull();
        assertThat(chassis.vehicleController.getNumWheels()).isEqualTo(4);
        assertThat(scene.physics().raycastVehicleCount()).isEqualTo(1);

        String[] expectedOrder = {"root/wheel_fl", "root/wheel_fr", "root/wheel_rl", "root/wheel_rr"};
        for (int i = 0; i < expectedOrder.length; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            assertThat(scene.world().getComponent(wheelEntity, PartRefComponent.class).slotPath)
                    .isEqualTo(expectedOrder[i]);
            assertThat(scene.world().getComponent(wheelEntity, WheelControllerComponent.class).wheelIndex)
                    .isEqualTo(i);
        }
    }

    @Test
    void aDestroyedVehicle_releasesItsRaycastController() {
        // G19 / D02-S5.7 rule 5. The controller holds a pointer to the chassis body and is stepped
        // as a world action, so it must leave before the body is disposed. The tearDown assertion on
        // NativeResourceTracker is the other half of this test.
        int vehicle = spawnOne();
        assertThat(scene.physics().raycastVehicleCount()).isEqualTo(1);

        scene.world().destroyEntity(vehicle);
        scene.step();

        assertThat(scene.physics().raycastVehicleCount()).isZero();
    }

    private int spawnOne() {
        scene.spawnQueue().request(ASSEMBLY, new Matrix4().setToTranslation(0f, 5f, 0f), 0, 1);
        scene.step();
        return vehicles.snapshot()[0];
    }
}
