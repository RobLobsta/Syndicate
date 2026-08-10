/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.WeaponFamily;

/**
 * What makes a weapon part a particular weapon
 * (docs/08_asset_pipeline.md#D08-S4.2, docs/01_product_game_design.md#D01-S4.4).
 *
 * <p>D01-R8 requires every weapon part to expose its fire interval, damage per shot, damage type,
 * projectile speed, spread, heat and ammunition in its part definition. Five of those already exist
 * as {@code StatBlock} stats, because they are numbers a utility part can buff (D05-S4.5). The rest
 * cannot be stats and are here, for the same reason {@link HandlingBlock} exists (DEC-031): a family
 * is an identity rather than a quantity, and summing two weapons' damage types is meaningless.
 *
 * <p><b>The stats stay authoritative for the numbers.</b> This block carries the family, the
 * ammunition capacity, the blast radius, the range and the muzzle offset; fire interval, damage,
 * spread, heat and projectile speed are read from the part's stats so that degradation (D05-S5.4)
 * and utility multipliers (D05-S5.6 phase 2) reach them. A weapon that authors no stats fires at the
 * defaults below, which are deliberately unimpressive rather than zero — a weapon with a zero fire
 * interval fires every tick.
 *
 * @param family which of the eight D01-S4.4 families this part is
 * @param damageTypeOverride the damage type when it is not the family's own, or null for the
 *     family's. Present because a content author may want an incendiary autocannon without a ninth
 *     family; absent from almost every part.
 * @param ammoCapacity rounds at spawn, or {@link #UNLIMITED_AMMO} for a weapon that never runs dry
 * @param blastRadiusM metres of blast for an explosive shot; 0 for a point hit
 * @param rangeM metres a shot may travel before it expires; 0 takes the family's D01-S4.4 range
 * @param muzzleLocal where shots leave the part, in the part's local space, metres
 */
public record WeaponBlock(
        WeaponFamily family,
        DamageType damageTypeOverride,
        int ammoCapacity,
        float blastRadiusM,
        float rangeM,
        Vector3 muzzleLocal) {

    /** An {@code ammoCapacity} that never depletes. */
    public static final int UNLIMITED_AMMO = -1;

    /** Seconds between shots when a part authors no {@code FIRE_INTERVAL_S} stat. */
    public static final float DEFAULT_FIRE_INTERVAL_S = 1.0f;

    /** Hit points per shot when a part authors no {@code DAMAGE_PER_SHOT} stat. */
    public static final float DEFAULT_DAMAGE_PER_SHOT = 10.0f;

    /** Metres per second when a part authors no {@code PROJECTILE_SPEED_MPS} stat. */
    public static final float DEFAULT_PROJECTILE_SPEED_MPS = 600.0f;

    /** Heat added per shot when a part authors no {@code HEAT_PER_SHOT} stat, as a fraction of full. */
    public static final float DEFAULT_HEAT_PER_SHOT = 0.0f;

    /** Fraction of full heat shed per second. A weapon at full heat cools in four seconds. */
    public static final float HEAT_COOLING_PER_SECOND = 0.25f;

    public WeaponBlock {
        if (family == null) {
            family = WeaponFamily.AUTOCANNON;
        }
        muzzleLocal = new Vector3(muzzleLocal == null ? Vector3.Zero : muzzleLocal);
    }

    /** The damage type this weapon deals: its override, or its family's (D01-R9). */
    public DamageType damageType() {
        return damageTypeOverride == null ? family.damageType() : damageTypeOverride;
    }

    /** Metres a shot may travel: the authored range, or the family's D01-S4.4 figure. */
    public float effectiveRangeM() {
        return rangeM > 0f ? rangeM : family.defaultRangeM();
    }

    /** A minimal weapon of a family: unlimited ammo, no blast, the family's range, muzzle at origin. */
    public static WeaponBlock of(WeaponFamily family) {
        return new WeaponBlock(family, null, UNLIMITED_AMMO, 0f, 0f, Vector3.Zero);
    }
}
