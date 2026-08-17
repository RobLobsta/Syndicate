/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.util.Transform;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The sub-part degradation table (docs/17_weapon_system.md#D17-S5.13). */
@Tag("unit")
class WeaponSubPartDegradationTest {

    /** D17-S5.8: every weapon sub-slot is named {@code sub_<label>}. */
    @Test
    void everyTaxonomyLabelIsRecoverableFromItsSlotId() {
        for (WeaponSubPart label : WeaponSubPart.values()) {
            String slotId = WeaponSubPart.SLOT_PREFIX + label.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(WeaponSubPart.fromSlotId(slotId)).isEqualTo(label);
        }
    }

    /** The cannon's two cogs are {@code sub_gear_l} and {@code sub_gear_r}, and both are GEAR. */
    @Test
    void aMirroredPairSharesOneLabel() {
        assertThat(WeaponSubPart.fromSlotId("sub_gear_l")).isEqualTo(WeaponSubPart.GEAR);
        assertThat(WeaponSubPart.fromSlotId("sub_gear_r")).isEqualTo(WeaponSubPart.GEAR);
    }

    /** A vehicle's own slots flow through the same walk and must not be read as weapon sub-parts. */
    @Test
    void aNonWeaponSlotIsNotALabel() {
        assertThat(WeaponSubPart.fromSlotId("wheel_fl")).isNull();
        assertThat(WeaponSubPart.fromSlotId("turret_main")).isNull();
        assertThat(WeaponSubPart.fromSlotId("sub_nonsense")).isNull();
        assertThat(WeaponSubPart.fromSlotId(null)).isNull();
    }

    /** D17-R61: an intact weapon is degraded by nothing. */
    @Test
    void anIntactWeaponIsUnpenalised() {
        WeaponSubPartDegradation.Penalties p = WeaponSubPartDegradation.evaluate(EnumSet.noneOf(WeaponSubPart.class));
        assertThat(p.disabled()).isFalse();
        assertThat(p.spreadMul()).isEqualTo(1f);
        assertThat(p.rangeMul()).isEqualTo(1f);
        assertThat(p.fireIntervalMul()).isEqualTo(1f);
        assertThat(p.feedLost()).isFalse();
    }

    /** D17-R61: barrel gone means spread ×4 and range halved — and the gun still fires (D17-R62). */
    @Test
    void aLostBarrelCollapsesAccuracyAndHalvesRangeWithoutSilencingTheGun() {
        WeaponSubPartDegradation.Penalties p = WeaponSubPartDegradation.evaluate(EnumSet.of(WeaponSubPart.BARREL));
        assertThat(p.spreadMul()).isEqualTo(4f);
        assertThat(p.rangeMul()).isEqualTo(0.5f);
        assertThat(p.disabled()).isFalse();
        assertThat(p.fireIntervalMul()).isEqualTo(1f);
    }

    /** D17-R61: breech gone halves the fire rate, which is twice the interval. */
    @Test
    void aLostBreechHalvesTheFireRate() {
        WeaponSubPartDegradation.Penalties p = WeaponSubPartDegradation.evaluate(EnumSet.of(WeaponSubPart.BREECH));
        assertThat(p.fireIntervalMul()).isEqualTo(2f);
        assertThat(p.disabled()).isFalse();
    }

    /** D17-R61: the receiver is the one sub-part whose loss stops the weapon entirely. */
    @Test
    void aLostReceiverStopsTheWeaponFiring() {
        assertThat(WeaponSubPartDegradation.evaluate(EnumSet.of(WeaponSubPart.RECEIVER))
                        .disabled())
                .isTrue();
    }

    /** D17-R61: the four cosmetic labels change no simulation value. */
    @Test
    void theCosmeticLabelsChangeNothingMechanical() {
        Set<WeaponSubPart> cosmetic =
                EnumSet.of(WeaponSubPart.MUZZLE, WeaponSubPart.GEAR, WeaponSubPart.SIGHT, WeaponSubPart.FURNITURE);
        WeaponSubPartDegradation.Penalties p = WeaponSubPartDegradation.evaluate(cosmetic);
        assertThat(p.disabled()).isFalse();
        assertThat(p.spreadMul()).isEqualTo(1f);
        assertThat(p.rangeMul()).isEqualTo(1f);
        assertThat(p.fireIntervalMul()).isEqualTo(1f);
        assertThat(p.feedLost()).isFalse();
    }

    /** D17-R61: a feed hit costs capacity but leaves what is chambered — never zero (D17-R62). */
    @Test
    void aLostFeedLeavesTheChamberedRoundsAndNeverNothing() {
        assertThat(WeaponSubPartDegradation.chamberedRounds(400)).isEqualTo(20);
        assertThat(WeaponSubPartDegradation.chamberedRounds(4)).isEqualTo(1);
        assertThat(WeaponSubPartDegradation.chamberedRounds(0)).isEqualTo(1);
        // A weapon with no ammunition model has no feed to lose.
        assertThat(WeaponSubPartDegradation.chamberedRounds(-1)).isEqualTo(-1);
    }

    /** The penalties compose: a gun can lose its barrel and its breech in the same fight. */
    @Test
    void lossesCompose() {
        WeaponSubPartDegradation.Penalties p =
                WeaponSubPartDegradation.evaluate(EnumSet.of(WeaponSubPart.BARREL, WeaponSubPart.BREECH));
        assertThat(p.spreadMul()).isEqualTo(4f);
        assertThat(p.rangeMul()).isEqualTo(0.5f);
        assertThat(p.fireIntervalMul()).isEqualTo(2f);
        assertThat(p.disabled()).isFalse();
    }

    /** An unoccupied declared sub-slot is a loss: a detached barrel leaves the graph entirely. */
    @Test
    void anEmptyDeclaredSubSlotCountsAsLost() {
        PartType mount = partTypeWithSlots("gun_mount", "sub_receiver");
        Set<WeaponSubPart> lost =
                WeaponSubPartDegradation.lostBeneath("turret_main", mount, slotPath -> false, slotPath -> null);
        assertThat(lost).containsExactly(WeaponSubPart.RECEIVER);
    }

    /**
     * The walk does not descend past a loss.
     *
     * <p>A receiver carrying a barrel that carries a muzzle: with the receiver gone, the barrel and
     * the muzzle went with it (D07-S5.7), and reporting all three would apply the barrel's accuracy
     * penalty to a weapon that has already stopped firing.
     */
    @Test
    void aLostSubPartTakesItsChildrenWithItRatherThanReportingThemSeparately() {
        PartType mount = partTypeWithSlots("gun_mount", "sub_receiver");
        Set<WeaponSubPart> lost = WeaponSubPartDegradation.lostBeneath(
                "turret_main", mount, slotPath -> false, slotPath -> partTypeWithSlots("gun_receiver", "sub_barrel"));
        assertThat(lost).containsExactly(WeaponSubPart.RECEIVER);
    }

    /** A live sub-part is walked through, so a loss two levels down is still found. */
    @Test
    void aLossBeneathALiveSubPartIsFound() {
        PartType mount = partTypeWithSlots("gun_mount", "sub_receiver");
        PartType receiver = partTypeWithSlots("gun_receiver", "sub_barrel", "sub_breech");
        Set<WeaponSubPart> lost = WeaponSubPartDegradation.lostBeneath(
                "turret_main",
                mount,
                slotPath -> slotPath.equals("turret_main/sub_receiver"),
                slotPath -> slotPath.equals("turret_main/sub_receiver") ? receiver : null);
        assertThat(lost).containsExactlyInAnyOrder(WeaponSubPart.BARREL, WeaponSubPart.BREECH);
    }

    /** A built-in weapon is one part with no sub-slots, and passes through unchanged. */
    @Test
    void aWeaponWithNoSubSlotsLosesNothing() {
        PartType builtIn = partTypeWithSlots("tank_barrel");
        assertThat(WeaponSubPartDegradation.lostBeneath("turret_main", builtIn, slotPath -> false, slotPath -> null))
                .isEmpty();
    }

    /** A WEAPON part type offering the named {@code SUBSLOT}s and nothing else. */
    private static PartType partTypeWithSlots(String partTypeId, String... slotIds) {
        PartType.Builder builder = PartType.builder(AssetId.of(partTypeId), PartCategory.WEAPON, box())
                .slotTypeRequired(SlotType.SUBSLOT)
                .massKg(10f);
        for (String slotId : slotIds) {
            builder.slot(SlotDefinition.of(slotId, SlotType.SUBSLOT, new Transform(), 50f));
        }
        return builder.build();
    }

    private static MeshData box() {
        return new MeshData(new float[] {
            -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f,
            -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
        });
    }
}
