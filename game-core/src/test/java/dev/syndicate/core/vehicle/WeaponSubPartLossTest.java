/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.ShippedContentScene;
import dev.syndicate.core.vehicle.StatBlock.Stat;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Shooting a gun apart, on a real shipped weapon
 * (docs/17_weapon_system.md#D17-S5.13).
 *
 * <p>{@code WeaponSubPartDegradationTest} covers the table against a synthetic slot tree. What this
 * adds is the wiring: that the sub-slots the Blender tool actually authored are named the way the
 * walk expects, that {@code VehicleStatsSystem} (6) finds them on a vehicle built by
 * {@code VehicleFactory}, and that the numbers it writes are the ones {@code WeaponSystem} (8) then
 * reads. Every one of those is a place the table could be correct and change nothing a player sees,
 * which is exactly the failure DISC-051 records.
 */
@Tag("integration")
class WeaponSubPartLossTest {

    @BeforeAll
    static void requireShippedContent() {
        assumeTrue(ShippedContent.isPresent(), "shipped assets are not present");
    }

    /** D17-R61 and D17-R62: the barrel goes, accuracy collapses, range halves, the gun still fires. */
    @Test
    void aBarrelShotOffCollapsesAccuracyAndHalvesRangeButLeavesTheGunFiring() {
        try (ShippedContentScene scene = new ShippedContentScene(23L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30);

            int mount = weaponMountOf(scene.world(), vehicle);
            assertThat(mount)
                    .as("the shipped Eclipse carries a flank machine gun")
                    .isNotEqualTo(-1);

            WeaponControllerComponent weapon = scene.world().getComponent(mount, WeaponControllerComponent.class);
            PartStatsComponent stats = scene.world().getComponent(mount, PartStatsComponent.class);
            float intactRangeM = weapon.effectiveRangeM;
            float intactSpread = stats.effectiveStats.resolve(Stat.SPREAD_RAD, 0f);
            float intactInterval = weapon.effectiveFireIntervalS;
            assertThat(intactRangeM).isGreaterThan(0f);
            assertThat(weapon.disabledBySubPartLoss).isFalse();

            scene.destroyPart(subPartOf(scene.world(), vehicle, mount, "sub_barrel"));
            scene.step(2);

            assertThat(weapon.effectiveRangeM).isCloseTo(intactRangeM * 0.5f, within(1e-3f));
            assertThat(stats.effectiveStats.resolve(Stat.SPREAD_RAD, 0f)).isCloseTo(intactSpread * 4f, within(1e-4f));
            // D17-R62: a weapon that stopped working the moment any piece was hit would collapse the
            // whole sub-part system back into one health pool.
            assertThat(weapon.disabledBySubPartLoss).isFalse();
            assertThat(weapon.effectiveFireIntervalS).isCloseTo(intactInterval, within(1e-4f));
        }
    }

    /** D17-R61: the receiver is the one loss that silences the weapon. */
    @Test
    void aReceiverShotOffSilencesTheWeapon() {
        try (ShippedContentScene scene = new ShippedContentScene(23L)) {
            int vehicle = scene.spawn(VehicleProfiles.ECLIPSE, new Vector3(0f, 0.05f, 0f));
            scene.step(30);
            int mount = weaponMountOf(scene.world(), vehicle);
            WeaponControllerComponent weapon = scene.world().getComponent(mount, WeaponControllerComponent.class);

            scene.destroyPart(subPartOf(scene.world(), vehicle, mount, "sub_receiver"));
            scene.step(2);

            assertThat(weapon.disabledBySubPartLoss).isTrue();
        }
    }

    /** The first weapon mount on a vehicle, in ascending slot path order, or {@code -1}. */
    private static int weaponMountOf(World world, int vehicleEntity) {
        SlotChain chain = chainOf(world, vehicleEntity);
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            if (world.getComponent(entry.getValue(), WeaponControllerComponent.class) != null) {
                return entry.getValue();
            }
        }
        return -1;
    }

    /**
     * The part on the named sub-slot anywhere beneath a mount.
     *
     * <p>Not {@code mountPath + "/" + slotId}: the taxonomy's tree is deeper than one level — the
     * machine gun hangs its receiver off the mount and its barrel off the receiver — and a test that
     * assumed a flat tree would be asserting the shape of the model rather than the shape of the rule.
     */
    private static int subPartOf(World world, int vehicleEntity, int mountEntity, String slotId) {
        SlotChain chain = chainOf(world, vehicleEntity);
        String mountPath = chain.slotPathOf(mountEntity);
        assertThat(mountPath).as("the mount is in the slot graph").isNotNull();
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            String path = entry.getKey();
            if (SlotChain.isAtOrBeneath(path, mountPath) && path.endsWith("/" + slotId)) {
                return entry.getValue();
            }
        }
        throw new AssertionError("no " + slotId + " beneath " + mountPath);
    }

    private static SlotChain chainOf(World world, int vehicleEntity) {
        return SlotChain.of(
                world.getComponent(vehicleEntity, SlotGraphComponent.class),
                world.getComponent(vehicleEntity, VehicleChassisComponent.class));
    }

    private static org.assertj.core.data.Offset<Float> within(float tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }
}
