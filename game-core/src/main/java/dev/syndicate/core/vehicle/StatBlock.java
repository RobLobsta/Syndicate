/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import java.util.Arrays;

/**
 * The fixed set of named scalars a part contributes to its vehicle
 * (docs/05_vehicle_part_system.md#D05-S4.5).
 *
 * <p>Every stat carries an {@code add} term and a {@code mul} factor. Unset means identity:
 * {@code add = 0}, {@code mul = 1} (D05-R15). Storing both halves rather than a single number is
 * what lets aggregation be order-independent — sums and products both commute, so the stat total of
 * a vehicle does not depend on the order parts were attached in (G3).
 *
 * <p>The stats are held in two parallel {@code float[Stat.COUNT]} arrays rather than as named
 * fields, because {@code VehicleStatsSystem} aggregates all of them with the same loop and a named
 * field per stat would make that loop a 14-way copy-paste that silently rots when a stat is added.
 *
 * <p>{@code maxSpeedMps} and {@code accelerationMps2} are deliberately absent: D05-R16 derives them
 * from engine force, power, mass and drag, so content cannot author a top speed that contradicts
 * physics.
 *
 * <p>{@code ENGINE_POWER_W} is a fifteenth stat added to D05-S4.5's table in the same commit as this
 * line (DEC-032). Without it a vehicle's tractive force is constant at every speed, which makes the
 * derived top speed of D05-S5.6 phase 4 a function of the <em>launch</em> force — and a car
 * calibrated to a real 0-100 time then reports a top speed several times what it has.
 *
 * <p>{@code MODULE_DURATION_S} and {@code MODULE_COOLDOWN_S} are the sixteenth and seventeenth,
 * added with {@link dev.syndicate.core.asset.ModuleBlock} and amended into D05-S4.5 in the same
 * commit. They are stats rather than fields on that block for the reason every other number is: a
 * damaged cloak must stay lit for less time, and only a stat degrades.
 */
public final class StatBlock {

    /** The stat identifiers of the D05-S4.5 table, in table order. */
    public enum Stat {
        ENGINE_FORCE_N,
        ENGINE_POWER_W,
        BRAKE_FORCE_N,
        MAX_STEER_RAD,
        STEER_RATE_RAD_PER_SEC,
        FRICTION_SLIP,
        SUSPENSION_STIFFNESS,
        ARMOR_VALUE,
        MAX_HP_MUL,
        FIRE_INTERVAL_S,
        DAMAGE_PER_SHOT,
        SPREAD_RAD,
        HEAT_PER_SHOT,
        PROJECTILE_SPEED_MPS,
        SENSOR_RANGE_M,
        MODULE_DURATION_S,
        MODULE_COOLDOWN_S,

        /**
         * Newtons of thrust one rotor makes at full collective (D05-S4.5, extended for the Kestrel).
         *
         * <p>A stat rather than a field on {@link dev.syndicate.core.asset.RotorBlock} for the
         * reason the weapon stats are: this is the number that has to fall when the rotor is shot,
         * and degradation only reaches stats (D05-S5.4). Appended rather than inserted because a
         * stat's ordinal is its index into two float arrays.
         */
        ROTOR_THRUST_N;

        /** How many stats exist. Cached because it is an array length on a hot path. */
        public static final int COUNT = values().length;

        private static final Stat[] VALUES = values();

        /** The stat at an ordinal, without the defensive array copy {@code values()} makes. */
        public static Stat at(int ordinal) {
            return VALUES[ordinal];
        }
    }

    private final float[] add = new float[Stat.COUNT];
    private final float[] mul = new float[Stat.COUNT];

    /** Creates an identity block: every {@code add} 0, every {@code mul} 1. */
    public StatBlock() {
        reset();
    }

    /** The additive term for a stat. */
    public float add(Stat stat) {
        return add[stat.ordinal()];
    }

    /** The multiplicative factor for a stat. */
    public float mul(Stat stat) {
        return mul[stat.ordinal()];
    }

    /** Sets the additive term for a stat. */
    public void setAdd(Stat stat, float value) {
        add[stat.ordinal()] = value;
    }

    /** Sets the multiplicative factor for a stat. */
    public void setMul(Stat stat, float value) {
        mul[stat.ordinal()] = value;
    }

    /**
     * The stat's resolved value against a base: {@code (base + add) * mul}.
     *
     * <p>Additive before multiplicative is the order D05-S5.6 aggregates in; the reverse order gives
     * different numbers for the same content, so it is fixed here rather than at each call site.
     */
    public float resolve(Stat stat, float base) {
        int i = stat.ordinal();
        return (base + add[i]) * mul[i];
    }

    /** Copies every term from {@code other}. Returns {@code this} for chaining. */
    public StatBlock set(StatBlock other) {
        System.arraycopy(other.add, 0, add, 0, Stat.COUNT);
        System.arraycopy(other.mul, 0, mul, 0, Stat.COUNT);
        return this;
    }

    /** Returns to identity. */
    public void reset() {
        Arrays.fill(add, 0f);
        Arrays.fill(mul, 1f);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StatBlock other)) {
            return false;
        }
        return Arrays.equals(add, other.add) && Arrays.equals(mul, other.mul);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(add) + Arrays.hashCode(mul);
    }
}
