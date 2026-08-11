/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.present;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Health to shape key weights (docs/07_damage_destruction_model.md#D07-S5.5, T-D07-13).
 *
 * <p>The worked table in D07-S5.5 is an acceptance criterion (AC-D07-9), not an illustration: it is
 * asserted here value for value, because a rounding difference in the bracketing arithmetic would
 * show as a car that is subtly less dented than the damage it has taken, which nobody would ever
 * find by looking.
 */
@Tag("unit")
class MorphWeightsTest {

    private static final org.assertj.core.data.Offset<Float> TOLERANCE = offset(1e-6f);

    private final float[] weights = new float[MorphWeights.COUNT];

    /** AC-D07-9: exactly the worked values of D07-S5.5. */
    @Test
    void theWorkedValuesOfTheBlueprint() {
        assertWeights(1.00f, 0f, 0f, 0f, 0f);
        assertWeights(0.875f, 0.5f, 0f, 0f, 0f);
        assertWeights(0.75f, 1f, 0f, 0f, 0f);
        assertWeights(0.50f, 0f, 1f, 0f, 0f);
        assertWeights(0.25f, 0f, 0f, 1f, 0f);
        assertWeights(0.00f, 0f, 0f, 0f, 1f);
    }

    /** Exactly two weights are ever non-zero: the pair bracketing the current health. */
    @Test
    void atMostTwoLevelsBlendAtOnce() {
        for (int i = 0; i <= 100; i++) {
            MorphWeights.forHealth(i / 100f, weights);
            int nonZero = 0;
            for (float weight : weights) {
                if (weight > 0f) {
                    nonZero++;
                }
            }
            assertThat(nonZero).as("non-zero weights at health %.2f", i / 100f).isLessThanOrEqualTo(2);
        }
    }

    /** Health outside {@code [0,1]} is clamped rather than extrapolated into a negative weight. */
    @Test
    void healthOutsideTheUnitRangeIsClamped() {
        assertWeights(1.4f, 0f, 0f, 0f, 0f);
        assertWeights(-0.3f, 0f, 0f, 0f, 1f);
    }

    /** The ease converges on the target and stops there rather than overshooting past it. */
    @Test
    void theEaseConvergesAndDoesNotOvershoot() {
        float[] current = new float[MorphWeights.COUNT];
        float[] target = {0f, 1f, 0f, 0f};
        for (int frame = 0; frame < 240; frame++) {
            MorphWeights.moveToward(current, target, 1f / 60f);
        }
        assertThat(current).containsExactly(target, TOLERANCE);

        // One more step at the target must not move: a moveToward that steps unconditionally
        // oscillates around it by one step per frame, which reads as a mesh that will not settle.
        MorphWeights.moveToward(current, target, 1f / 60f);
        assertThat(current).containsExactly(target, TOLERANCE);
    }

    /** The ease takes the blueprint's rate: a full unit of weight in {@code 1/4.0} of a second. */
    @Test
    void theEaseRunsAtTheBlueprintRate() {
        float[] current = new float[MorphWeights.COUNT];
        float[] target = {1f, 0f, 0f, 0f};
        MorphWeights.moveToward(current, target, 0.25f);
        assertThat(current[0]).isEqualTo(1f, TOLERANCE);

        float[] half = new float[MorphWeights.COUNT];
        MorphWeights.moveToward(half, target, 0.125f);
        assertThat(half[0]).isEqualTo(0.5f, TOLERANCE);
    }

    /** D07-R17: a mesh with fewer keys shows the missing deformation on the deepest one it has. */
    @Test
    void missingLevelsFoldOntoTheDeepestAvailable() {
        MorphWeights.forHealth(0f, weights);
        float[] twoKeys = new float[2];
        MorphWeights.renormalise(weights, 2, twoKeys);
        assertThat(twoKeys).containsExactly(new float[] {0f, 1f}, TOLERANCE);

        MorphWeights.forHealth(0.125f, weights);
        float[] oneKey = new float[1];
        MorphWeights.renormalise(weights, 1, oneKey);
        // Half of dmg_75 and half of dmg_100 both land on the single authored key, so the part is
        // fully deformed rather than half-deformed — total weight is preserved.
        assertThat(oneKey[0]).isEqualTo(1f, TOLERANCE);
    }

    /** A mesh with no damage keys never deforms (D07-E6). Nothing is written and nothing throws. */
    @Test
    void aMeshWithNoKeysIsLeftAlone() {
        MorphWeights.forHealth(0f, weights);
        float[] none = new float[0];
        MorphWeights.renormalise(weights, 0, none);
        assertThat(none).isEmpty();
    }

    private void assertWeights(float health, float dmg25, float dmg50, float dmg75, float dmg100) {
        MorphWeights.forHealth(health, weights);
        assertThat(weights)
                .as("weights at health %.3f", health)
                .containsExactly(new float[] {dmg25, dmg50, dmg75, dmg100}, TOLERANCE);
    }
}
