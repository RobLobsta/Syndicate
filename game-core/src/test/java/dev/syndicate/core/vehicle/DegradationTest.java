/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.PartCategory;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The degradation curves and their table (docs/05_vehicle_part_system.md#D05-S5.4). */
@Tag("unit")
class DegradationTest {

    /** T-D05-5's tolerance. */
    private static final float TOLERANCE = 1e-6f;

    /** The health fractions T-D05-5 and AC-D05-6 assert at. */
    private static final float[] SAMPLE_HEALTH = {1f, 0.75f, 0.66f, 0.5f, 0.33f, 0.25f, 0.01f};

    /** T-D05-5: every profile matches D05-S5.4 at every sampled health value. */
    @Test
    void curvesMatchTheBlueprintAtEverySampledHealth() {
        float floor = 0.4f;
        for (float h : SAMPLE_HEALTH) {
            assertThat(Degradation.multiplier(DegradationProfile.NONE, h, floor))
                    .isEqualTo(1f);
            assertThat(Degradation.multiplier(DegradationProfile.LINEAR, h, floor))
                    .isCloseTo(floor + (1f - floor) * h, within(TOLERANCE));
            assertThat(Degradation.multiplier(DegradationProfile.EXPONENTIAL, h, floor))
                    .isCloseTo(floor + (1f - floor) * h * h, within(TOLERANCE));

            float expectedThreshold = h > 0.66f ? 1f : floor + (1f - floor) * (h / 0.66f);
            assertThat(Degradation.multiplier(DegradationProfile.THRESHOLD, h, floor))
                    .isCloseTo(expectedThreshold, within(TOLERANCE));
        }
    }

    /** D05-S5.4: THRESHOLD is flat above the knee and falls below it, which is the "sputter" feel. */
    @Test
    void thresholdIsFlatAboveTheKnee() {
        assertThat(Degradation.multiplier(DegradationProfile.THRESHOLD, 1f, 0.4f))
                .isEqualTo(1f);
        assertThat(Degradation.multiplier(DegradationProfile.THRESHOLD, 0.67f, 0.4f))
                .isEqualTo(1f);
        assertThat(Degradation.multiplier(DegradationProfile.THRESHOLD, 0.66f, 0.4f))
                .isCloseTo(1f, within(TOLERANCE));
        assertThat(Degradation.multiplier(DegradationProfile.THRESHOLD, 0.33f, 0.4f))
                .isLessThan(1f);
    }

    /** Every curve reaches exactly its floor at zero health and exactly 1 at full health. */
    @Test
    void everyCurveSpansFloorToOne() {
        for (DegradationProfile profile : DegradationProfile.values()) {
            float atZero = Degradation.multiplier(profile, 0f, 0.35f);
            float atFull = Degradation.multiplier(profile, 1f, 0.35f);
            assertThat(atFull).isCloseTo(1f, within(TOLERANCE));
            assertThat(atZero).isCloseTo(profile == DegradationProfile.NONE ? 1f : 0.35f, within(TOLERANCE));
        }
    }

    /** A health fraction outside [0,1] is clamped, never propagated as a negative multiplier. */
    @Test
    void healthOutsideTheUnitRangeIsClamped() {
        assertThat(Degradation.multiplier(DegradationProfile.LINEAR, -0.5f, 0.2f))
                .isCloseTo(0.2f, within(TOLERANCE));
        assertThat(Degradation.multiplier(DegradationProfile.LINEAR, 3f, 0.2f)).isCloseTo(1f, within(TOLERANCE));
    }

    /** T-D05-8: a wheel at 50% health keeps {@code base·(0.35 + 0.65·0.25)} of its grip. */
    @Test
    void wheelGripFollowsTheExponentialCurve() {
        float base = 10.5f;
        float expected = base * (0.35f + 0.65f * 0.25f);
        assertThat(Degradation.degradeScalar(PartCategory.WHEEL, Stat.FRICTION_SLIP, base, 0.5f, Map.of()))
                .isCloseTo(expected, within(1e-4f));
    }

    /** T-D05-7: a weapon at 50% health fires at {@code base / (0.4 + 0.6·(0.5/0.66))}. */
    @Test
    void weaponFireIntervalGrowsOnTheThresholdCurve() {
        float base = 0.5f;
        float expected = base / (0.4f + 0.6f * (0.5f / 0.66f));
        assertThat(Degradation.degradeScalar(PartCategory.WEAPON, Stat.FIRE_INTERVAL_S, base, 0.5f, Map.of()))
                .isCloseTo(expected, within(1e-4f));
    }

    /** D05-E7: a weapon at 1 HP fires at {@code 1/floor} = 2.5x its base interval. */
    @Test
    void aNearlyDeadWeaponFiresAtTwoAndAHalfTimesItsInterval() {
        float base = 0.5f;
        assertThat(Degradation.degradeScalar(PartCategory.WEAPON, Stat.FIRE_INTERVAL_S, base, 0f, Map.of()))
                .isCloseTo(base / 0.4f, within(1e-4f));
    }

    /** AC-D05-9: inverted stats increase as health falls; everything else decreases. */
    @Test
    void invertedStatsGrowAndOthersShrink() {
        assertThat(Degradation.isInverted(Stat.FIRE_INTERVAL_S)).isTrue();
        assertThat(Degradation.isInverted(Stat.SPREAD_RAD)).isTrue();
        assertThat(Degradation.isInverted(Stat.ENGINE_FORCE_N)).isFalse();

        float previousInterval = 0f;
        float previousForce = Float.MAX_VALUE;
        for (float h : new float[] {1f, 0.75f, 0.5f, 0.25f, 0.01f}) {
            float interval = Degradation.degradeScalar(PartCategory.WEAPON, Stat.FIRE_INTERVAL_S, 0.5f, h, Map.of());
            float force = Degradation.degradeScalar(PartCategory.CHASSIS, Stat.ENGINE_FORCE_N, 20_000f, h, Map.of());
            assertThat(interval).isGreaterThanOrEqualTo(previousInterval);
            assertThat(force).isLessThanOrEqualTo(previousForce);
            previousInterval = interval;
            previousForce = force;
        }
    }

    /** AC-D05-8 / D05-R21: a hit is a hit — damage per shot never degrades. */
    @Test
    void damagePerShotNeverDegrades() {
        for (float h : SAMPLE_HEALTH) {
            assertThat(Degradation.degradeScalar(PartCategory.WEAPON, Stat.DAMAGE_PER_SHOT, 250f, h, Map.of()))
                    .isEqualTo(250f);
        }
    }

    /** DEC-024: a (category, stat) pair the D05-S5.4 table does not name does not degrade. */
    @Test
    void anUnlistedPairIsLeftAlone() {
        assertThat(Degradation.ruleFor(PartCategory.WHEEL, Stat.ENGINE_FORCE_N).profile())
                .isEqualTo(DegradationProfile.NONE);
        assertThat(Degradation.ruleFor(PartCategory.DECORATIVE, Stat.ARMOR_VALUE)
                        .profile())
                .isEqualTo(DegradationProfile.NONE);
        assertThat(Degradation.degradeScalar(PartCategory.WHEEL, Stat.ENGINE_FORCE_N, 1_000f, 0.1f, Map.of()))
                .isEqualTo(1_000f);
    }

    /** D05-S5.4: every stat a utility declares degrades on the one blanket rule. */
    @Test
    void everyUtilityStatUsesTheBlanketRule() {
        for (int i = 0; i < Stat.COUNT; i++) {
            DegradationRule rule = Degradation.ruleFor(PartCategory.UTILITY, Stat.at(i));
            assertThat(rule.profile()).isEqualTo(DegradationProfile.LINEAR);
            assertThat(rule.floor()).isEqualTo(0.25f);
        }
    }

    /** D08-R5: an authored override replaces the table for that stat, and only for that stat. */
    @Test
    void anAuthoredOverrideReplacesTheTableRow() {
        Map<Stat, DegradationRule> overrides =
                Map.of(Stat.ARMOR_VALUE, new DegradationRule(DegradationProfile.NONE, 1f));

        assertThat(Degradation.degradeScalar(PartCategory.PANEL, Stat.ARMOR_VALUE, 45f, 0.1f, overrides))
                .isEqualTo(45f);
        // The table still answers for a stat the override does not name.
        assertThat(Degradation.ruleFor(PartCategory.PANEL, Stat.ARMOR_VALUE, Map.of())
                        .profile())
                .isEqualTo(DegradationProfile.LINEAR);
    }

    /** DEC-024: a multiplier fades toward identity, so a half-failed buff is half a buff. */
    @Test
    void multipliersFadeTowardIdentity() {
        StatBlock base = new StatBlock();
        base.setMul(Stat.FIRE_INTERVAL_S, 0.8f);
        StatBlock effective = new StatBlock();

        Degradation.degrade(base, effective, PartCategory.UTILITY, 1f, Map.of());
        assertThat(effective.mul(Stat.FIRE_INTERVAL_S)).isCloseTo(0.8f, within(TOLERANCE));

        // At zero health the utility contributes nothing at all (D05-E3): the buff is gone, which
        // for a multiplier means exactly 1, not 0.
        Degradation.degrade(base, effective, PartCategory.UTILITY, 0f, Map.of());
        assertThat(effective.mul(Stat.FIRE_INTERVAL_S)).isCloseTo(1f - 0.2f * 0.25f, within(TOLERANCE));
    }

    /** {@code degrade} tolerates {@code out == base}: every stat is read before it is written. */
    @Test
    void degradeInPlaceMatchesDegradeIntoACopy() {
        StatBlock base = new StatBlock();
        base.setAdd(Stat.ENGINE_FORCE_N, 20_000f);
        base.setMul(Stat.BRAKE_FORCE_N, 1.5f);

        StatBlock copy = new StatBlock().set(base);
        StatBlock separate = new StatBlock();
        Degradation.degrade(base, separate, PartCategory.CHASSIS, 0.5f, Map.of());
        Degradation.degrade(copy, copy, PartCategory.CHASSIS, 0.5f, Map.of());

        assertThat(copy).isEqualTo(separate);
    }

    /** A floor outside [0,1] is a content error caught where it is authored, not where it is used. */
    @Test
    void aFloorOutsideTheUnitRangeIsRejected() {
        assertThatThrownBy(() -> new DegradationRule(DegradationProfile.LINEAR, 1.5f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DegradationRule(DegradationProfile.LINEAR, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
