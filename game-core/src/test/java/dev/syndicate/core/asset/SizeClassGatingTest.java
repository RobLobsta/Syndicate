/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SizeClass;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Size-class slot gating (docs/17_weapon_system.md#D17-S4.3).
 *
 * <p>T-D17-6 and T-D17-9.
 */
@Tag("unit")
class SizeClassGatingTest {

    @ParameterizedTest(name = "a {0} slot {2} a {1} part")
    @CsvSource({
        "HEAVY,  LIGHT,  accepts",
        "HEAVY,  MEDIUM, accepts",
        "HEAVY,  HEAVY,  accepts",
        "MEDIUM, LIGHT,  accepts",
        "MEDIUM, MEDIUM, accepts",
        "MEDIUM, HEAVY,  rejects",
        "LIGHT,  LIGHT,  accepts",
        "LIGHT,  MEDIUM, rejects",
        "LIGHT,  HEAVY,  rejects",
    })
    @DisplayName("T-D17-6: a slot accepts its own class and every class below it")
    void acceptsOwnClassAndBelow(SizeClass slotClass, SizeClass partClass, String expected) {
        assertThat(slotClass.accepts(partClass)).isEqualTo("accepts".equals(expected));
    }

    @Test
    @DisplayName("D17-R8: an absent size class is MEDIUM, so content authored before D17 is unchanged")
    void defaultsToMedium() {
        assertThat(SizeClass.parse(null)).isEqualTo(SizeClass.MEDIUM);
        assertThat(SizeClass.parse("")).isEqualTo(SizeClass.MEDIUM);
        assertThat(SizeClass.parse("  ")).isEqualTo(SizeClass.MEDIUM);
        assertThat(SizeClass.DEFAULT).isEqualTo(SizeClass.MEDIUM);
    }

    @Test
    @DisplayName("A misspelled size class is a defect, not a default")
    void unknownNameIsNull() {
        // The whole reason `parse` returns null rather than falling back: "the field is absent" and
        // "the field says HEAVEY" must reach the loader as different answers, or A221 can never fire.
        assertThat(SizeClass.parse("HEAVEY")).isNull();
        assertThat(SizeClass.parse("enormous")).isNull();
        assertThat(SizeClass.parse("heavy")).isEqualTo(SizeClass.HEAVY);
    }

    @Test
    @DisplayName("A slot definition with no size class behaves exactly as it did before D17")
    void slotDefaultsToMedium() {
        SlotDefinition slot = SlotDefinition.of("hardpoint_x", SlotType.HARDPOINT, new Transform(), 200f);
        assertThat(slot.sizeClass()).isEqualTo(SizeClass.MEDIUM);

        SlotDefinition explicitNull =
                new SlotDefinition("hardpoint_y", SlotType.HARDPOINT, new Transform(), 200f, null, List.of(), true);
        assertThat(explicitNull.sizeClass()).isEqualTo(SizeClass.MEDIUM);
    }

    @Test
    @DisplayName("D17-R7: all three gates are independent, and each can fail on its own")
    void allThreeGatesAreIndependent() {
        SlotDefinition flank = new SlotDefinition(
                "hardpoint_flank_l", SlotType.HARDPOINT, new Transform(), 120f, SizeClass.LIGHT, List.of(), true);

        // Right category, right size, right mass.
        assertThat(flank.accepts(PartCategory.WEAPON, 45f, SizeClass.LIGHT)).isTrue();
        // Too bulky, though it is light enough — this is the gate D17 adds.
        assertThat(flank.accepts(PartCategory.WEAPON, 45f, SizeClass.HEAVY)).isFalse();
        // Small but dense: passes the size gate and fails the mass one, which is exactly why the two
        // are separate numbers rather than one.
        assertThat(flank.accepts(PartCategory.WEAPON, 300f, SizeClass.LIGHT)).isFalse();
        // Wrong category entirely.
        assertThat(flank.accepts(PartCategory.PANEL, 45f, SizeClass.LIGHT)).isFalse();
    }

    @Test
    @DisplayName("T-D17-9: an over-class weapon is rejected with A316, and an in-class one is not")
    void validatorReportsA316() {
        // Built through the same helper the other assembly tests use, so this exercises the real
        // validator rather than a re-implementation of its rule.
        SizeClassFixtures fixtures = new SizeClassFixtures();

        List<ValidationIssue> heavyOnLight =
                AssemblyValidator.validate(fixtures.assembly(), fixtures.index(SizeClass.HEAVY, SizeClass.LIGHT));
        assertThat(heavyOnLight).as("a HEAVY weapon on a LIGHT mount").anyMatch(issue -> issue.code()
                .equals("A316"));

        List<ValidationIssue> lightOnHeavy =
                AssemblyValidator.validate(fixtures.assembly(), fixtures.index(SizeClass.LIGHT, SizeClass.HEAVY));
        assertThat(lightOnHeavy).as("a LIGHT weapon on a HEAVY mount").noneMatch(issue -> issue.code()
                .equals("A316"));

        List<ValidationIssue> exact =
                AssemblyValidator.validate(fixtures.assembly(), fixtures.index(SizeClass.MEDIUM, SizeClass.MEDIUM));
        assertThat(exact).as("a MEDIUM weapon on a MEDIUM mount").noneMatch(issue -> issue.code()
                .equals("A316"));
    }
}
