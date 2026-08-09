/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.vehicle.StatBlock.Stat;
import dev.syndicate.model.PartCategory;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The degradation curves and the per-category table that assigns them
 * (docs/05_vehicle_part_system.md#D05-S5.4).
 *
 * <p>Degradation maps a part's health fraction to a multiplier on its stat contributions. It is a
 * pure function of {@code (category, stat, healthFraction)} plus whatever the part authored as an
 * override, so it can be evaluated as many times as a caller likes and never drifts — which is what
 * lets {@code VehicleStatsSystem} recompute effective stats from base every tick rather than
 * decaying the previous value in place (D05-R27).
 *
 * <p><b>Two things the table does not say, decided here (DEC-024).</b>
 *
 * <ul>
 *   <li>A {@code (category, stat)} pair the table does not name does <em>not</em> degrade. The table
 *       enumerates what falls off — including {@code damagePerShot NONE}, which it would not need to
 *       list if silence meant degradation — so silence is read as {@link DegradationProfile#NONE}.
 *       {@code UTILITY} is the exception the table states outright: "its declared stats" degrade
 *       {@code LINEAR} at floor 0.25, whichever stats those turn out to be.
 *   <li>A stat's {@code mul} term fades toward identity rather than toward zero. A multiplier is a
 *       buff, and a buff that has half failed is half as strong, not half of nothing:
 *       {@code mul' = 1 + (mul - 1) · m}. At {@code m = 0} it is exactly 1, which is what
 *       D05-E3 requires of a destroyed utility. Inversion (below) does not enter into it, because
 *       "worse" for a multiplier means "closer to no effect" in either direction.
 * </ul>
 *
 * <p><b>Inverted stats.</b> {@code fireIntervalS} and {@code spreadRad} get <em>worse</em> as health
 * falls, so their additive term is divided by the multiplier rather than multiplied by it. A weapon
 * at 1 HP fires at {@code 1/floor} = 2.5x its base interval (D05-E7).
 */
public final class Degradation {

    /**
     * The smallest multiplier an inverted stat may be divided by (D05-S5.4).
     *
     * <p>{@code max(m, 0.001)} in the blueprint's {@code applyDegradation}. Every floor in the table
     * is well above it, so it only bites for a part whose authored override sets a zero floor —
     * where it turns a division by zero into a 1000x fire interval, which is a visible symptom
     * rather than an infinity that propagates into Bullet three systems later.
     */
    public static final float MIN_MULTIPLIER = 0.001f;

    /** Health at or above which a {@link DegradationProfile#THRESHOLD} stat is untouched. */
    public static final float THRESHOLD_KNEE = 0.66f;

    /** The stats that grow as health falls (D05-S5.4). */
    private static final Set<Stat> INVERTED_STATS = EnumSet.of(Stat.FIRE_INTERVAL_S, Stat.SPREAD_RAD);

    /** No degradation, and no reduction at zero health either. */
    private static final DegradationRule INTACT = new DegradationRule(DegradationProfile.NONE, 1f);

    /** {@code UTILITY}'s blanket rule: every stat it declares degrades on this one (D05-S5.4). */
    private static final DegradationRule UTILITY_RULE = new DegradationRule(DegradationProfile.LINEAR, 0.25f);

    /** The DEGRADATION_TABLE of D05-S5.4, by category then stat. */
    private static final Map<PartCategory, Map<Stat, DegradationRule>> TABLE = buildTable();

    private Degradation() {
        throw new AssertionError("no instances");
    }

    /**
     * The multiplier {@code m ∈ [floor, 1]} a profile produces at a health fraction.
     *
     * @param healthFraction clamped to {@code [0,1]} rather than asserted: it is derived from two
     *     floats a dozen systems write, and a value a hair outside the range must not turn into a
     *     negative engine force
     */
    public static float multiplier(DegradationProfile profile, float healthFraction, float floor) {
        float h = Math.max(0f, Math.min(healthFraction, 1f));
        return switch (profile) {
            case NONE -> 1f;
            case LINEAR -> floor + (1f - floor) * h;
            case THRESHOLD -> h > THRESHOLD_KNEE ? 1f : floor + (1f - floor) * (h / THRESHOLD_KNEE);
            case EXPONENTIAL -> floor + (1f - floor) * h * h;
        };
    }

    /** The multiplier for one {@link DegradationRule}. */
    public static float multiplier(DegradationRule rule, float healthFraction) {
        return multiplier(rule.profile(), healthFraction, rule.floor());
    }

    /**
     * The rule the D05-S5.4 table assigns to a {@code (category, stat)} pair, never null.
     *
     * <p>A pair the table does not name gets {@link DegradationProfile#NONE} at floor 1 — see the
     * class note. {@code UTILITY} gets its blanket rule for every stat.
     */
    public static DegradationRule ruleFor(PartCategory category, Stat stat) {
        if (category == PartCategory.UTILITY) {
            return UTILITY_RULE;
        }
        Map<Stat, DegradationRule> byStat = TABLE.get(category);
        if (byStat == null) {
            return INTACT;
        }
        return byStat.getOrDefault(stat, INTACT);
    }

    /**
     * The rule in force for a part: its authored override if it declares one, the table otherwise.
     *
     * @param overrides the part type's {@code degradationOverrides} (D08-R5), possibly empty
     */
    public static DegradationRule ruleFor(PartCategory category, Stat stat, Map<Stat, DegradationRule> overrides) {
        DegradationRule override = overrides == null ? null : overrides.get(stat);
        return override != null ? override : ruleFor(category, stat);
    }

    /** True when a stat gets worse as health falls, so its multiplier applies reciprocally. */
    public static boolean isInverted(Stat stat) {
        return INVERTED_STATS.contains(stat);
    }

    /**
     * A stat's value after degradation: {@code base · m}, or {@code base / m} when inverted
     * (D05-S5.4 {@code applyDegradation}).
     */
    public static float apply(Stat stat, float baseValue, float multiplier) {
        return isInverted(stat) ? baseValue / Math.max(multiplier, MIN_MULTIPLIER) : baseValue * multiplier;
    }

    /**
     * A scalar the part stores outside its {@link StatBlock} — a wheel's grip, a weapon's fire
     * interval — after the curve for its category.
     *
     * <p>These are resolved against an engine default at spawn ({@code WHEEL_FRICTION_SLIP} and
     * friends, DEC-022), so their base is not recoverable from the stat block's {@code add} term
     * alone and they degrade from the stored scalar instead. T-D05-7 and T-D05-8 are stated in
     * exactly this form.
     */
    public static float degradeScalar(
            PartCategory category,
            Stat stat,
            float baseValue,
            float healthFraction,
            Map<Stat, DegradationRule> overrides) {
        return apply(stat, baseValue, multiplier(ruleFor(category, stat, overrides), healthFraction));
    }

    /**
     * Writes {@code base} degraded at {@code healthFraction} into {@code out} (D05-S5.4).
     *
     * <p>{@code out} may be {@code base}; every stat is read before it is written.
     *
     * @param overrides the part type's authored per-stat rules, or null for none
     */
    public static void degrade(
            StatBlock base,
            StatBlock out,
            PartCategory category,
            float healthFraction,
            Map<Stat, DegradationRule> overrides) {

        for (int i = 0; i < Stat.COUNT; i++) {
            Stat stat = Stat.at(i);
            float m = multiplier(ruleFor(category, stat, overrides), healthFraction);
            out.setAdd(stat, apply(stat, base.add(stat), m));
            // A multiplier fades toward identity, not toward zero: a half-failed buff is half a
            // buff, and a destroyed one is none at all (D05-E3).
            out.setMul(stat, 1f + (base.mul(stat) - 1f) * m);
        }
    }

    private static Map<PartCategory, Map<Stat, DegradationRule>> buildTable() {
        Map<PartCategory, Map<Stat, DegradationRule>> table = new EnumMap<>(PartCategory.class);

        // wheel — grip is felt at once, steering fades gently, the ride goes soft but not broken.
        Map<Stat, DegradationRule> wheel = new EnumMap<>(Stat.class);
        wheel.put(Stat.FRICTION_SLIP, new DegradationRule(DegradationProfile.EXPONENTIAL, 0.35f));
        wheel.put(Stat.MAX_STEER_RAD, new DegradationRule(DegradationProfile.LINEAR, 0.50f));
        wheel.put(Stat.STEER_RATE_RAD_PER_SEC, new DegradationRule(DegradationProfile.LINEAR, 0.50f));
        wheel.put(Stat.SUSPENSION_STIFFNESS, new DegradationRule(DegradationProfile.LINEAR, 0.70f));
        table.put(PartCategory.WHEEL, Map.copyOf(wheel));

        // weapon — a scratched gun still works and a battered one is obviously failing, but a hit
        // is always a hit: damagePerShot is deliberately NONE, for readability (D05-R21).
        Map<Stat, DegradationRule> weapon = new EnumMap<>(Stat.class);
        weapon.put(Stat.FIRE_INTERVAL_S, new DegradationRule(DegradationProfile.THRESHOLD, 0.40f));
        weapon.put(Stat.SPREAD_RAD, new DegradationRule(DegradationProfile.LINEAR, 0.35f));
        weapon.put(Stat.DAMAGE_PER_SHOT, new DegradationRule(DegradationProfile.NONE, 1.00f));
        table.put(PartCategory.WEAPON, Map.copyOf(weapon));

        // armor — protection scales with what is left of the plate. Mass is not a StatBlock stat
        // and so cannot appear here at all, which is one way D05-R20 is structurally true.
        table.put(PartCategory.ARMOR, Map.of(Stat.ARMOR_VALUE, new DegradationRule(DegradationProfile.LINEAR, 0.10f)));

        // chassis — the vehicle gets sluggish, and loses less of its brakes than of its engine.
        Map<Stat, DegradationRule> chassis = new EnumMap<>(Stat.class);
        chassis.put(Stat.ENGINE_FORCE_N, new DegradationRule(DegradationProfile.LINEAR, 0.45f));
        // Power degrades with the force it is the high-speed limit on; a damaged engine that lost
        // launch force but kept full power would accelerate harder at speed than when healthy.
        chassis.put(Stat.ENGINE_POWER_W, new DegradationRule(DegradationProfile.LINEAR, 0.45f));
        chassis.put(Stat.BRAKE_FORCE_N, new DegradationRule(DegradationProfile.LINEAR, 0.60f));
        table.put(PartCategory.CHASSIS, Map.copyOf(chassis));

        // decorative declares no stats at all (D05-R6), so it needs no row; UTILITY is handled by
        // its blanket rule rather than by an enumeration.
        table.put(PartCategory.DECORATIVE, Map.of());
        return Map.copyOf(table);
    }
}
