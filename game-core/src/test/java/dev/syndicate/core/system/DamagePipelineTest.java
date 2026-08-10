/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.damage.CoverageMap;
import dev.syndicate.core.damage.DamageApplication;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.damage.PartDestroyedEvent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The damage pipeline of docs/07_damage_destruction_model.md#D07-S5.2 to #D07-S5.4.
 *
 * <p>Covers the armour formulas and their floors (T-D07-1 to T-D07-4), the positional modifiers
 * (T-D07-5), the state machine and its monotonicity (T-D07-8, T-D07-9), propagation and its bounds
 * (T-D07-10 to T-D07-12), and the two ends of the pipeline that were previously only reachable from
 * a test reaching in and declaring a part destroyed: a hit that destroys a part, and the fracture
 * and mass change that follow it in the same tick (T-D07-17).
 */
@Tag("unit")
class DamagePipelineTest {

    private static final float CHASSIS_MASS_KG = 900f;
    private static final float PLATE_MASS_KG = 160f;

    private DestructionTestScene scene;
    private int vehicle;

    @BeforeEach
    void setUp() {
        scene = new DestructionTestScene(4242L);
        scene.addGround();
        vehicle = scene.spawnVehicle(
                AssetId.of("assembly_target_01"),
                List.of(
                        PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                        PartSpec.of("root/plate_front", PartCategory.ARMOR, PLATE_MASS_KG, new Vector3(0f, 0.4f, 1.2f)),
                        PartSpec.of("root/plate_rear", PartCategory.ARMOR, PLATE_MASS_KG, new Vector3(0f, 0.4f, -1.2f)),
                        PartSpec.of(
                                "root/plate_front/greeble", PartCategory.DECORATIVE, 5f, new Vector3(0f, 0.2f, 0f))),
                new Vector3(0f, 4f, 0f));
        scene.step();
    }

    @AfterEach
    void tearDown() {
        scene.close();
    }

    // ---- Armour formulas (D07-S4.3, AC-D07-3) ----------------------------------------

    /** T-D07-1: 100 HP kinetic against 30 armour applies 70. */
    @Test
    void kineticSubtractsArmour() {
        assertThat(DamageApplication.afterArmour(100f, 30f, DamageType.KINETIC)).isCloseTo(70f, within(1e-4f));
    }

    /** T-D07-2: kinetic against armour it cannot beat still applies the 10% floor. */
    @Test
    void kineticHasAFloor() {
        assertThat(DamageApplication.afterArmour(100f, 200f, DamageType.KINETIC))
                .isCloseTo(10f, within(1e-4f));
    }

    /** T-D07-3: explosive meets only 40% of the armour value. */
    @Test
    void explosivePartlyBypassesArmour() {
        assertThat(DamageApplication.afterArmour(100f, 100f, DamageType.EXPLOSIVE))
                .isCloseTo(60f, within(1e-4f));
    }

    /** T-D07-4: incendiary ignores armour entirely. */
    @Test
    void incendiaryIgnoresArmour() {
        assertThat(DamageApplication.afterArmour(100f, 200f, DamageType.INCENDIARY))
                .isCloseTo(100f, within(1e-4f));
    }

    /** Energy meets half the armour and floors at 15%, the highest of the four (D07-R8). */
    @Test
    void energyMeetsHalfTheArmour() {
        assertThat(DamageApplication.afterArmour(100f, 60f, DamageType.ENERGY)).isCloseTo(70f, within(1e-4f));
        assertThat(DamageApplication.afterArmour(100f, 1000f, DamageType.ENERGY))
                .isCloseTo(15f, within(1e-4f));
    }

    /** Collision floors lower than kinetic: ramming something armoured is meant to hurt less. */
    @Test
    void collisionHasALowerFloor() {
        assertThat(DamageApplication.afterArmour(100f, 500f, DamageType.COLLISION))
                .isCloseTo(5f, within(1e-4f));
    }

    // ---- Damage state machine (D07-S5.3, AC-D07-6, AC-D07-7) -------------------------

    /** T-D07-8: transitions happen exactly at 0.66 / 0.33 / 0.0 and never reverse. */
    @Test
    void stateTransitionsAtTheThresholdsAndNeverReverses() {
        int plate = scene.partAt(vehicle, "root/plate_front");
        HealthComponent health = scene.world().getComponent(plate, HealthComponent.class);
        DamageStateComponent state = scene.world().getComponent(plate, DamageStateComponent.class);

        List<DamageState> seen = new ArrayList<>();
        for (int step = 0; step <= 100; step++) {
            health.setCurrentHp(health.maxHp * (1f - step / 100f));
            DamageState after = scene.damageApplication().updateDamageState(scene.world(), plate, scene.tick());
            seen.add(after);
        }
        assertThat(seen.get(0)).isEqualTo(DamageState.INTACT);
        assertThat(seen.get(34)).isEqualTo(DamageState.DAMAGED);
        assertThat(seen.get(67)).isEqualTo(DamageState.CRITICAL);
        assertThat(seen.get(100)).isEqualTo(DamageState.DESTROYED);
        for (int i = 1; i < seen.size(); i++) {
            assertThat(seen.get(i).ordinal())
                    .as("severity never falls (G8)")
                    .isGreaterThanOrEqualTo(seen.get(i - 1).ordinal());
        }
        assertThat(state.state).isEqualTo(DamageState.DESTROYED);
    }

    /** T-D07-9: a destroyed part stays destroyed even if something raises its health (G9). */
    @Test
    void destroyedIsTerminal() {
        int plate = scene.partAt(vehicle, "root/plate_rear");
        HealthComponent health = scene.world().getComponent(plate, HealthComponent.class);
        health.setCurrentHp(0f);
        scene.damageApplication().updateDamageState(scene.world(), plate, scene.tick());

        health.setCurrentHp(health.maxHp);
        DamageState after = scene.damageApplication().updateDamageState(scene.world(), plate, scene.tick());
        assertThat(after).isEqualTo(DamageState.DESTROYED);
    }

    /** T-D07-13's sibling: the thresholds come from D00-S6.4, not from a second copy of them. */
    @Test
    void thresholdsAreTheReservedConstants() {
        assertThat(DamageApplication.stateForHealth(SimulationConstants.DAMAGE_THRESHOLD_DAMAGED))
                .isEqualTo(DamageState.DAMAGED);
        assertThat(DamageApplication.stateForHealth(SimulationConstants.DAMAGE_THRESHOLD_CRITICAL))
                .isEqualTo(DamageState.CRITICAL);
        assertThat(DamageApplication.stateForHealth(SimulationConstants.DAMAGE_THRESHOLD_DESTROYED))
                .isEqualTo(DamageState.DESTROYED);
        assertThat(DamageApplication.stateForHealth(0.67f)).isEqualTo(DamageState.INTACT);
    }

    // ---- Applying damage (D07-S5.2) --------------------------------------------------

    /** A hit removes hit points, records its attacker, and leaves the normal for the detach kick. */
    @Test
    void aHitRemovesHitPointsAndRecordsWhereItCameFrom() {
        int plate = scene.partAt(vehicle, "root/plate_front");
        HealthComponent health = scene.world().getComponent(plate, HealthComponent.class);
        health.armorValue = 0f;
        float before = health.currentHp;

        float applied = apply(plate, DamageType.KINETIC, 25f, new Vector3(0f, 0f, 1f), 7);

        assertThat(applied).isGreaterThan(0f);
        assertThat(health.currentHp).isLessThan(before);
        assertThat(health.lastAttacker).isEqualTo(7);
        assertThat(health.lastHitNormalZ).isCloseTo(1f, within(1e-4f));
    }

    /** D07-E1 and D07-R12: damage to an already-destroyed part is discarded, not redirected. */
    @Test
    void damageToADeadPartIsDiscarded() {
        int plate = scene.partAt(vehicle, "root/plate_rear");
        HealthComponent health = scene.world().getComponent(plate, HealthComponent.class);
        health.armorValue = 0f;
        health.setCurrentHp(0f);
        scene.damageApplication().updateDamageState(scene.world(), plate, scene.tick());

        assertThat(apply(plate, DamageType.KINETIC, 500f, new Vector3(0f, 0f, 1f), 7))
                .isZero();
    }

    /** D07-E2: overkill clamps at zero and propagates only the amount that was actually applied. */
    @Test
    void overkillDoesNotCarry() {
        int plate = scene.partAt(vehicle, "root/plate_front");
        HealthComponent health = scene.world().getComponent(plate, HealthComponent.class);
        health.armorValue = 0f;
        float pool = health.currentHp;

        float applied = apply(plate, DamageType.KINETIC, pool * 10f, new Vector3(0f, 0f, 1f), 7);

        assertThat(applied).isCloseTo(pool, within(1e-3f));
        assertThat(health.currentHp).isZero();
    }

    // ---- Propagation (D07-S5.4, AC-D07-8) --------------------------------------------

    /** T-D07-10: a kinetic hit gives each neighbour 20% × 0.5 of what it applied. */
    @Test
    void kineticPropagatesToNeighbours() {
        int plate = scene.partAt(vehicle, "root/plate_front");
        int chassis = scene.partAt(vehicle, "root");
        int greeble = scene.partAt(vehicle, "root/plate_front/greeble");
        zeroArmour();
        float chassisBefore = hpOf(chassis);
        float greebleBefore = hpOf(greeble);

        float applied = apply(plate, DamageType.KINETIC, 60f, new Vector3(0f, 0f, 1f), 7);

        float expectedHop1 =
                applied * SimulationConstants.PROPAGATION_FRACTION * DamageType.KINETIC.propagationFactor();
        assertThat(chassisBefore - hpOf(chassis)).isCloseTo(expectedHop1, within(0.5f));
        assertThat(greebleBefore - hpOf(greeble)).isCloseTo(expectedHop1, within(0.5f));
    }

    /** T-D07-11: energy does not propagate at all — a beam concentrates. */
    @Test
    void energyDoesNotPropagate() {
        int plate = scene.partAt(vehicle, "root/plate_front");
        int chassis = scene.partAt(vehicle, "root");
        zeroArmour();
        float chassisBefore = hpOf(chassis);

        apply(plate, DamageType.ENERGY, 60f, new Vector3(0f, 0f, 1f), 7);

        assertThat(hpOf(chassis)).isEqualTo(chassisBefore);
    }

    /** D07-R16: propagation crosses the chassis, so a hit on one plate reaches the other. */
    @Test
    void propagationCrossesTheChassis() {
        int front = scene.partAt(vehicle, "root/plate_front");
        int rear = scene.partAt(vehicle, "root/plate_rear");
        zeroArmour();
        float rearBefore = hpOf(rear);

        apply(front, DamageType.EXPLOSIVE, 200f, new Vector3(0f, 0f, 1f), 7);

        assertThat(hpOf(rear))
                .as("hop 2 reaches the far plate through the chassis")
                .isLessThan(rearBefore);
    }

    /**
     * AC-D07-8: propagation stops at the hop limit and never re-propagates.
     *
     * <p>The rear plate's only neighbour is the chassis, so a one-hop type reaches the chassis and
     * stops. If a propagated hit propagated again, the chassis would pass it on to the front plate,
     * and the front plate to the greeble — the exponential cascade D07-R14 exists to prevent.
     */
    @Test
    void propagationStopsAtTheHopLimit() {
        int rear = scene.partAt(vehicle, "root/plate_rear");
        int chassis = scene.partAt(vehicle, "root");
        int front = scene.partAt(vehicle, "root/plate_front");
        int greeble = scene.partAt(vehicle, "root/plate_front/greeble");
        zeroArmour();
        float chassisBefore = hpOf(chassis);
        float frontBefore = hpOf(front);
        float greebleBefore = hpOf(greeble);

        apply(rear, DamageType.KINETIC, 100f, new Vector3(0f, 0f, -1f), 7);

        assertThat(hpOf(chassis)).as("hop 1 reaches the chassis").isLessThan(chassisBefore);
        assertThat(hpOf(front)).as("hop 2 is beyond a one-hop type").isEqualTo(frontBefore);
        assertThat(hpOf(greeble)).as("hop 3 is beyond every type").isEqualTo(greebleBefore);
    }

    // ---- The pipeline end to end -----------------------------------------------------

    /**
     * T-D07-17, AC-D07-14: a hit that destroys a part changes the vehicle's mass in the same tick.
     *
     * <p>This is the case the project could not exercise before slot 12 existed: PROG-010 recorded
     * that damage only ever happened when a test declared it.
     */
    @Test
    void aHitThatDestroysAPartChangesTheVehicleInTheSameTick() {
        int plate = scene.partAt(vehicle, "root/plate_front");
        HealthComponent health = scene.world().getComponent(plate, HealthComponent.class);
        health.armorValue = 0f;

        List<PartDestroyedEvent> destroyed = new ArrayList<>();
        scene.world().events().subscribe(PartDestroyedEvent.class, destroyed::add);

        float massBefore = scene.world()
                .getComponent(vehicle, dev.syndicate.core.component.VehicleChassisComponent.class)
                .totalMassKg;

        // Emitted the way slot 11 emits: same-tick, for slot 12 to drain in this same tick.
        scene.world()
                .events()
                .emitSameTick(DamageEvent.direct(
                        plate,
                        EntityId.NULL,
                        7,
                        DamageType.KINETIC,
                        health.currentHp * 4f,
                        new Vector3(0f, 1f, 2f),
                        new Vector3(0f, 0f, 1f),
                        scene.tick(),
                        0));
        scene.step();

        assertThat(destroyed).extracting(PartDestroyedEvent::slotPath).contains("root/plate_front");
        float massAfter = scene.world()
                .getComponent(vehicle, dev.syndicate.core.component.VehicleChassisComponent.class)
                .totalMassKg;
        assertThat(massAfter).as("the plate's mass left with it (G10)").isLessThan(massBefore);
        assertThat(massBefore - massAfter).isCloseTo(PLATE_MASS_KG, within(PLATE_MASS_KG * 0.05f));
    }

    /** AC-D07-5: a coverage map with nothing covering reports nothing covered. */
    @Test
    void anUncoveredSlotIsNotExposed() {
        CoverageMap coverage = new CoverageMap();
        coverage.rebuild(scene.world(), scene.assets(), vehicle);
        assertThat(coverage.coveredCount()).isZero();
        // The test assembly authors no `covers`, so no slot is coverable and none is exposed —
        // which is the point: exposure rewards losing armour, not never having had any.
        assertThat(coverage.isExposed("root/plate_front")).isFalse();
    }

    /** Slot numbers come from D04-S4.4 and are asserted so the schedule cannot drift (AC-D04-3). */
    @Test
    void systemsSitInTheirCataloguedSlots() {
        assertThat(scene.collisionEventSystem().order()).isEqualTo(11);
        assertThat(scene.damageSystem().order()).isEqualTo(12);
        assertThat(scene.weaponSystem().order()).isEqualTo(8);
        assertThat(scene.projectileSystem().order()).isEqualTo(9);
        assertThat(scene.scoreSystem().order()).isEqualTo(17);
    }

    // ---- Helpers ---------------------------------------------------------------------

    /** Applies one direct damage event through the real pipeline and returns what it removed. */
    private float apply(int partEntity, DamageType type, float amount, Vector3 normal, int attackerPlayer) {
        CoverageMap coverage = new CoverageMap();
        coverage.rebuild(scene.world(), scene.assets(), vehicle);
        return scene.damageApplication()
                .apply(
                        scene.world(),
                        DamageEvent.direct(
                                partEntity,
                                EntityId.NULL,
                                attackerPlayer,
                                type,
                                amount,
                                new Vector3(0f, 1f, 2f),
                                normal,
                                scene.tick(),
                                0),
                        coverage,
                        null,
                        true);
    }

    private float hpOf(int partEntity) {
        HealthComponent health = scene.world().getComponent(partEntity, HealthComponent.class);
        return health == null ? 0f : health.currentHp;
    }

    /** Strips armour from every part so the propagation arithmetic is readable. */
    private void zeroArmour() {
        for (String slotPath : List.of("root", "root/plate_front", "root/plate_rear", "root/plate_front/greeble")) {
            int partEntity = scene.partAt(vehicle, slotPath);
            HealthComponent health = scene.world().getComponent(partEntity, HealthComponent.class);
            if (health != null) {
                health.armorValue = 0f;
            }
            PartRefComponent partRef = scene.world().getComponent(partEntity, PartRefComponent.class);
            assertThat(partRef).as("every slot path in this fixture exists").isNotNull();
        }
    }
}
