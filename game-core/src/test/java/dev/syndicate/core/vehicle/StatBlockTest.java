/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.PartCategory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The stat block and slot types of docs/05_vehicle_part_system.md#D05-S4.5 and #D05-S4.3. */
@Tag("unit")
class StatBlockTest {

    /** D05-R15: unset means identity — {@code add = 0}, {@code mul = 1}. */
    @Test
    void unsetStatsAreIdentity() {
        StatBlock block = new StatBlock();
        for (Stat stat : Stat.values()) {
            assertThat(block.add(stat)).as("%s add", stat).isZero();
            assertThat(block.mul(stat)).as("%s mul", stat).isEqualTo(1f);
            assertThat(block.resolve(stat, 42f)).as("%s resolve", stat).isEqualTo(42f);
        }
    }

    /** Additive before multiplicative, fixed here so no call site can pick the other order. */
    @Test
    void resolvesAdditiveBeforeMultiplicative() {
        StatBlock block = new StatBlock();
        block.setAdd(Stat.ENGINE_FORCE_N, 500f);
        block.setMul(Stat.ENGINE_FORCE_N, 2f);

        assertThat(block.resolve(Stat.ENGINE_FORCE_N, 1000f)).isEqualTo(3000f);
    }

    @Test
    void copiesAndResetsEveryTerm() {
        StatBlock source = new StatBlock();
        source.setAdd(Stat.ARMOR_VALUE, 12f);
        source.setMul(Stat.FIRE_INTERVAL_S, 0.8f);

        StatBlock copy = new StatBlock().set(source);
        assertThat(copy).isEqualTo(source);

        copy.reset();
        assertThat(copy).isEqualTo(new StatBlock());
        assertThat(source.add(Stat.ARMOR_VALUE))
                .as("reset must not touch the source")
                .isEqualTo(12f);
    }

    /** D05-S4.3: slot acceptance is read off the enum, not a parallel table that can drift. */
    @Test
    void slotTypesAcceptTheirDocumentedCategories() {
        assertThat(SlotType.ROOT.acceptsCategory(PartCategory.CHASSIS)).isTrue();
        assertThat(SlotType.WHEEL.acceptsCategory(PartCategory.WHEEL)).isTrue();
        assertThat(SlotType.WHEEL.acceptsCategory(PartCategory.WEAPON)).isFalse();
        assertThat(SlotType.HARDPOINT.accepts()).containsExactlyInAnyOrder(PartCategory.WEAPON, PartCategory.UTILITY);
        assertThat(SlotType.PANEL.acceptsCategory(PartCategory.PANEL)).isTrue();
        assertThat(SlotType.TURRET_MOUNT.acceptsCategory(PartCategory.UTILITY)).isFalse();
        assertThat(SlotType.ACCESSORY.accepts()).containsExactly(PartCategory.DECORATIVE);
        assertThat(SlotType.SUBSLOT.accepts()).doesNotContain(PartCategory.CHASSIS, PartCategory.WHEEL);
    }

    /** No slot type may accept a chassis except ROOT — a vehicle has exactly one root (D05-R10). */
    @Test
    void onlyRootAcceptsAChassis() {
        for (SlotType type : SlotType.values()) {
            assertThat(type.acceptsCategory(PartCategory.CHASSIS))
                    .as("%s accepts chassis", type)
                    .isEqualTo(type == SlotType.ROOT);
        }
    }
}
