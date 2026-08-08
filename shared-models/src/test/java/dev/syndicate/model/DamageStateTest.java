/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The monotonic damage state machine (docs/07_damage_destruction_model.md#D07-S5.3, G8, G9). */
@Tag("unit")
class DamageStateTest {

    @Test
    void transitions_areForwardOnly() {
        // G8: damage state is monotonic within a life.
        assertThat(DamageState.INTACT.canTransitionTo(DamageState.DAMAGED)).isTrue();
        assertThat(DamageState.DAMAGED.canTransitionTo(DamageState.DESTROYED)).isTrue();
        assertThat(DamageState.DAMAGED.canTransitionTo(DamageState.INTACT)).isFalse();
        assertThat(DamageState.CRITICAL.canTransitionTo(DamageState.CRITICAL)).isFalse();
    }

    @Test
    void detachment_isTerminal() {
        // G9: a detached part never reattaches within a match.
        for (DamageState next : DamageState.values()) {
            assertThat(DamageState.DETACHED.canTransitionTo(next)).isFalse();
        }
    }

    @Test
    void destroyedAndDetached_noLongerContribute() {
        assertThat(DamageState.DESTROYED.isGone()).isTrue();
        assertThat(DamageState.DETACHED.isGone()).isTrue();
        assertThat(DamageState.CRITICAL.isGone()).isFalse();
    }

    @Test
    void declarationOrder_matchesTheSpecifiedProgression() {
        assertThat(DamageState.values())
                .containsExactly(
                        DamageState.INTACT,
                        DamageState.DAMAGED,
                        DamageState.CRITICAL,
                        DamageState.DESTROYED,
                        DamageState.DETACHED);
    }
}
