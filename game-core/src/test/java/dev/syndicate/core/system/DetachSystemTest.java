/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.damage.DetachReason;
import dev.syndicate.core.damage.PartDetachedEvent;
import dev.syndicate.core.damage.VehicleDestroyedEvent;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 14 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/07_damage_destruction_model.md#D07-S5.7).
 */
@Tag("integration")
class DetachSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_01");

    private static final float CHASSIS_MASS_KG = 1200f;
    private static final float PLATE_MASS_KG = 340f;
    private static final float TURRET_MASS_KG = 320f;
    private static final float BARREL_MASS_KG = 90f;
    private static final float WHEEL_MASS_KG = 45f;

    private DestructionTestScene scene;
    private Family debris;
    private int vehicle;

    private final List<PartDetachedEvent> detached = new ArrayList<>();
    private final List<VehicleDestroyedEvent> wrecked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(1337L);
        vehicle = scene.spawnVehicle(
                ASSEMBLY,
                List.of(
                        PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                        PartSpec.of("root/armor_front", PartCategory.ARMOR, PLATE_MASS_KG, new Vector3(0f, 0f, 2f)),
                        PartSpec.of("root/turret", PartCategory.WEAPON, TURRET_MASS_KG, new Vector3(0f, 0.6f, 0f)),
                        PartSpec.of(
                                "root/turret/barrel", PartCategory.WEAPON, BARREL_MASS_KG, new Vector3(0f, 0f, 1.2f)),
                        PartSpec.of("root/wheel_fl", PartCategory.WHEEL, WHEEL_MASS_KG, new Vector3(-1f, -0.4f, 1.4f))),
                new Vector3(0f, 60f, 0f));
        debris = scene.world().family(ComponentQuery.all(DebrisTagComponent.class));
        scene.world().events().subscribe(PartDetachedEvent.class, detached::add);
        scene.world().events().subscribe(VehicleDestroyedEvent.class, wrecked::add);
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
    void system_occupiesSlot14OfPostSim() {
        // AC-D04-3. Slot 14 is after FractureSystem (13), so a part that breaks into shards is
        // already gone when the triggers look at the graph, and before MassPropertySystem (15),
        // which reconciles what left in the same tick (G10).
        assertThat(scene.detachSystem().order()).isEqualTo(14);
        assertThat(scene.detachSystem().phase()).isEqualTo(Phase.POST_SIM);
    }

    @Test
    void t1_aDestroyedPart_detachesAndBecomesOneDebrisBody() {
        // AC-D07-15 (T1). A part with no fracture manifest leaves in one piece (D07-E5).
        int plate = scene.partAt(vehicle, "root/armor_front");
        scene.destroyPart(plate);

        scene.step();

        assertThat(debris.size()).isEqualTo(1);
        assertThat(scene.world().isAlive(plate)).isFalse();
        assertThat(detached)
                .extracting(PartDetachedEvent::slotPath, PartDetachedEvent::reason)
                .containsExactly(tuple("root/armor_front", DetachReason.DESTROYED));
    }

    @Test
    void t1_theDetachedPartsMassLeavesTheVehicleInTheSameTick() {
        // AC-D05-10 / AC-D05-11 / G10. Slot 14 removes the part, slot 15 reconciles, and the next
        // physics step never sees the old mass.
        scene.step();
        float before = scene.world().getComponent(vehicle, VehicleChassisComponent.class).totalMassKg;

        scene.destroyPart(scene.partAt(vehicle, "root/armor_front"));
        scene.step();

        VehicleChassisComponent chassis = scene.world().getComponent(vehicle, VehicleChassisComponent.class);
        assertThat(chassis.totalMassKg).isEqualTo(before - PLATE_MASS_KG, within(0.01f));
        assertThat(scene.world().getComponent(vehicle, RigidBodyComponent.class).massKg)
                .isEqualTo(before - PLATE_MASS_KG, within(0.01f));
    }

    @Test
    void t1_debrisInheritsTheVelocityItHadAtItsOwnPosition() {
        // D05-R23 / D07-S5.7 detachVelocity: v + ω × r, measured at the part rather than at the
        // vehicle's centre of mass. Spinning the body is what tells the two apart — with ω = 0 both
        // give the same answer, which is why this test spins it.
        scene.bodyOf(vehicle).setLinearVelocity(new Vector3(12f, 0f, 0f));
        scene.bodyOf(vehicle).setAngularVelocity(new Vector3(0f, 3f, 0f));
        scene.destroyPart(scene.partAt(vehicle, "root/armor_front"));

        scene.step();

        assertThat(debris.size()).isEqualTo(1);
        VelocityComponent velocity = scene.world().getComponent(debris.snapshot()[0], VelocityComponent.class);
        // The plate sits 2 m ahead on +Z; ω = 3 rad/s about +Y gives it ω × r = (6, 0, 0) on top of
        // the body's own 12 m/s, so a v-only inheritance would read 12 and this reads about 18.
        assertThat(velocity.linear.x).isGreaterThan(15f);
        assertThat(velocity.angular.y).isEqualTo(3f, within(0.2f));
    }

    @Test
    void t3_aDetachedPartTakesItsSubtreeWithIt_andBothBecomeDebris() {
        // AC-D05-14 / T-D05-13 / AC-D07-15 (T3). The turret's barrel is not destroyed; it leaves
        // because its parent did, and it becomes debris in the same tick.
        int turret = scene.partAt(vehicle, "root/turret");
        int barrel = scene.partAt(vehicle, "root/turret/barrel");
        scene.destroyPart(turret);

        scene.step();

        assertThat(debris.size()).isEqualTo(2);
        assertThat(scene.world().isAlive(turret)).isFalse();
        assertThat(scene.world().isAlive(barrel)).isFalse();
        assertThat(detached)
                .extracting(PartDetachedEvent::slotPath, PartDetachedEvent::reason)
                .containsExactlyInAnyOrder(
                        tuple("root/turret", DetachReason.DESTROYED),
                        tuple("root/turret/barrel", DetachReason.PARENT_DETACHED));
    }

    @Test
    void aDetachedWheel_becomesDebrisFromItsOwnPartType() {
        // D06-R19. A wheel is a ray cast and contributes no compound geometry (D06-R6), so unlike
        // every other part its hull has never been built — the asset index is the only source of it.
        int wheel = scene.partAt(vehicle, "root/wheel_fl");
        scene.destroyPart(wheel);

        scene.step();

        assertThat(debris.size()).isEqualTo(1);
        assertThat(scene.world().getComponent(debris.snapshot()[0], RigidBodyComponent.class).massKg)
                .isEqualTo(WHEEL_MASS_KG, within(0.01f));
        assertThat(scene.world().getComponent(vehicle, VehicleChassisComponent.class).wheelCount)
                .isZero();
    }

    @Test
    void aSubtreeDetachedBelowAFracturedPart_becomesDebrisRatherThanNothing() {
        // The gap PROG-005 recorded: FractureSystem (13) turns the fractured part into shards and
        // detaches everything under it, but only the root becomes a world object. Slot 14 finds the
        // rest from the placement PartDetachment recorded on them.
        AssetId manifest = AssetId.of("fracture_turret");
        try (DestructionTestScene fracturing = new DestructionTestScene(99L)) {
            fracturing.registerManifest(manifest, AssetId.of("part_root_turret"), TURRET_MASS_KG, 4);
            int assembly = fracturing.spawnVehicle(
                    ASSEMBLY,
                    List.of(
                            PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                            PartSpec.of("root/turret", PartCategory.WEAPON, TURRET_MASS_KG, new Vector3(0f, 0.6f, 0f))
                                    .fracturing(manifest),
                            PartSpec.of(
                                    "root/turret/barrel",
                                    PartCategory.WEAPON,
                                    BARREL_MASS_KG,
                                    new Vector3(0f, 0f, 1.2f))),
                    new Vector3(0f, 60f, 0f));
            Family fracturedDebris = fracturing.world().family(ComponentQuery.all(DebrisTagComponent.class));
            int barrel = fracturing.partAt(assembly, "root/turret/barrel");
            fracturing.destroyPart(fracturing.partAt(assembly, "root/turret"));

            fracturing.step();

            // Four shards from the turret, plus one body for the barrel that left with it.
            assertThat(fracturedDebris.size()).isEqualTo(5);
            assertThat(fracturing.world().isAlive(barrel)).isFalse();
        }
    }

    @Test
    void t4_aDestroyedChassis_wrecksTheVehicleAndDetachesEverything() {
        // AC-D07-15 (T4) / T-D07-19 / D05-R26. The chassis never detaches; its death takes the
        // vehicle with it, and every remaining part leaves as debris.
        int chassisPart = scene.partAt(vehicle, "root");
        scene.destroyPart(chassisPart);

        scene.step();

        assertThat(wrecked).hasSize(1);
        assertThat(wrecked.get(0).vehicleEntity()).isEqualTo(vehicle);
        // Four parts plus the chassis itself, which becomes a wreck body rather than vanishing.
        assertThat(debris.size()).isEqualTo(5);
        assertThat(scene.world().isAlive(vehicle)).isFalse();
        assertThat(detached).hasSize(4);
        assertThat(detached).extracting(PartDetachedEvent::reason).contains(DetachReason.VEHICLE_WRECKED);
    }

    @Test
    void t4_theWreckedChassisOutlivesItsDebris() {
        // D07-S5.8. A wreck is a landmark; a shard is scenery for a moment.
        scene.destroyPart(scene.partAt(vehicle, "root"));

        scene.step();

        float longest = 0f;
        int[] ids = debris.snapshot();
        for (int i = 0; i < debris.size(); i++) {
            longest = Math.max(longest, scene.world().getComponent(ids[i], LifetimeComponent.class).remainingS);
        }
        // One TICK_DT short of the full lifetime: LifetimeSystem is slot 16 and the wreck was
        // spawned in slot 14, so the debris is counted down once in the tick it appeared.
        assertThat(longest).isEqualTo(DetachSystem.WRECK_LIFETIME_S - SimulationConstants.TICK_DT, within(1e-4f));
        assertThat(DetachSystem.WRECK_LIFETIME_S).isGreaterThan(SimulationConstants.DEBRIS_LIFETIME_S);
    }

    @Test
    void t4_isOneWay() {
        // G9. The vehicle entity is destroyed by the wreck, so a second pass has nothing to wreck —
        // VehicleDestroyed is emitted exactly once (T-D07-19).
        scene.destroyPart(scene.partAt(vehicle, "root"));

        scene.step(4);

        assertThat(wrecked).hasSize(1);
    }

    @Test
    void aHangingPart_staysAttachedUntilItsHangingTicksAreUp() {
        // D07-S5.7 T1 with the hanging branch, and D06-S5.6's HANGING_TICKS bound. The part is
        // destroyed but does not leave; the drama is that it visibly hangs first.
        try (DestructionTestScene hanging = new DestructionTestScene(7L)) {
            int assembly = hanging.spawnVehicle(
                    ASSEMBLY,
                    List.of(
                            PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                            PartSpec.of("root/armor_front", PartCategory.ARMOR, PLATE_MASS_KG, new Vector3(0f, 0f, 2f))
                                    .hanging()),
                    new Vector3(0f, 60f, 0f));
            Family hangingDebris = hanging.world().family(ComponentQuery.all(DebrisTagComponent.class));
            int plate = hanging.partAt(assembly, "root/armor_front");
            hanging.destroyPart(plate);

            hanging.step(DetachSystem.HANGING_TICKS - 1);

            assertThat(hangingDebris.size()).isZero();
            assertThat(hanging.world().getComponent(plate, DamageStateComponent.class).state)
                    .isEqualTo(DamageState.DESTROYED);
            assertThat(hanging.world().getComponent(assembly, SlotGraphComponent.class).nodes)
                    .hasSize(1);

            hanging.step(2);

            assertThat(hangingDebris.size()).isEqualTo(1);
            assertThat(hanging.world().isAlive(plate)).isFalse();
        }
    }

    @Test
    void detachmentIsOneWay_andASecondTriggerOnTheSamePartIsANoOp() {
        // D07-R21 / G9 / D05-E5. Two triggers firing on one part in a tick is normal; the second
        // must not produce a second debris body or a second event.
        int plate = scene.partAt(vehicle, "root/armor_front");
        scene.destroyPart(plate);

        scene.step(3);

        assertThat(debris.size()).isEqualTo(1);
        assertThat(detached).hasSize(1);
    }
}
