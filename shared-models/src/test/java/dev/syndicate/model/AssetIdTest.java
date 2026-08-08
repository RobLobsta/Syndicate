/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The authored identifier form of docs/00_master_index.md#D00-S4.5 (D00-R19). */
@Tag("unit")
class AssetIdTest {

    @Test
    void lowercaseSnakeIds_areAccepted() {
        assertThat(AssetId.of("armor_plate_medium_01").value()).isEqualTo("armor_plate_medium_01");
        assertThat(AssetId.of("abc").value()).isEqualTo("abc");
    }

    @Test
    void uppercaseHyphenAndLeadingDigit_areRejected() {
        assertThatThrownBy(() -> AssetId.of("Armor_Plate")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetId.of("armor-plate")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetId.of("1armor")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooShortAndTooLongIds_areRejected() {
        assertThatThrownBy(() -> AssetId.of("ab")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssetId.of("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AssetId.isValid("a".repeat(64))).isTrue();
    }

    @Test
    void isValid_answersWithoutThrowing() {
        assertThat(AssetId.isValid("armor_plate_01")).isTrue();
        assertThat(AssetId.isValid("Armor")).isFalse();
        assertThat(AssetId.isValid(null)).isFalse();
    }

    @Test
    void ordering_isLexicographicForDeterministicIteration() {
        // G3: asset maps are iterated in sorted order, never hash order.
        assertThat(AssetId.of("armor_a").compareTo(AssetId.of("armor_b"))).isNegative();
    }
}
