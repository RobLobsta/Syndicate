/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The eight weapon families of docs/01_product_game_design.md#D01-S4.4 (D01-R7).
 *
 * <p>A family fixes two things content may not override: how the shot is <em>delivered</em>, and its
 * primary damage type. Everything else — fire interval, damage per shot, projectile speed, spread,
 * heat, ammunition — is authored per part (D01-R8), because those are balance and balance is content
 * (NG5).
 *
 * <p>{@link Delivery} is what the simulation actually branches on. A hitscan weapon resolves in the
 * tick it fires and never becomes an entity; a ballistic one becomes a swept segment integrated
 * outside Bullet (D06-S5.9). Carrying that on the family rather than inferring it from
 * {@code projectileSpeedMps == 0} keeps a mistyped speed from silently turning a cannon into a laser.
 *
 * <p>{@link #RAM} is here for completeness of the D01-S4.4 table and is <b>not a weapon part</b>: it
 * is a chassis property, and its damage comes from the relative momentum of a collision resolved in
 * slot 11 (D07-S5.2). A part type that claims this family is a content error.
 */
public enum WeaponFamily {

    /** Fast ballistic workhorse; rate degrades sharply with weapon health. */
    AUTOCANNON(Delivery.BALLISTIC, DamageType.KINETIC, 200f),

    /** Slow heavy ballistic shell; can detach a part in one hit. */
    CANNON(Delivery.BALLISTIC, DamageType.KINETIC, 300f),

    /** Multi-pellet hitscan cone; spreads damage across several parts. */
    SHOTGUN(Delivery.HITSCAN, DamageType.KINETIC, 25f),

    /** Slow projectile with a blast radius; propagates strongly (D07-S5.4). */
    ROCKET(Delivery.BALLISTIC, DamageType.EXPLOSIVE, 150f),

    /** High-arc indirect fire; lands on top hit zones. */
    MORTAR(Delivery.BALLISTIC, DamageType.EXPLOSIVE, 120f),

    /** Short continuous cone; applies burn stacks. */
    FLAMER(Delivery.CONTINUOUS, DamageType.INCENDIARY, 12f),

    /** Hitscan continuous beam; ignores a fraction of armour, heat-limited. */
    LASER(Delivery.CONTINUOUS, DamageType.ENERGY, 100f),

    /** Ramming. A chassis property rather than a part; damage comes from momentum (D07-S5.2). */
    RAM(Delivery.CONTACT, DamageType.COLLISION, 0f);

    /** How a family's shot reaches its target. */
    public enum Delivery {
        /** Integrated as a swept segment outside Bullet, one ray cast per tick (D06-S5.9). */
        BALLISTIC,
        /** A single ray test resolved in the tick of the shot (D06-S5.9). */
        HITSCAN,
        /** A ray test every tick the trigger is held; no cooldown, heat instead. */
        CONTINUOUS,
        /** No shot at all — the damage is a collision (D07-S5.2). */
        CONTACT
    }

    private final Delivery delivery;
    private final DamageType damageType;
    private final float defaultRangeM;

    WeaponFamily(Delivery delivery, DamageType damageType, float defaultRangeM) {
        this.delivery = delivery;
        this.damageType = damageType;
        this.defaultRangeM = defaultRangeM;
    }

    /** How this family's shot reaches its target. */
    public Delivery delivery() {
        return delivery;
    }

    /** The damage type this family deals (D01-R9). */
    public DamageType damageType() {
        return damageType;
    }

    /** Metres. The D01-S4.4 range, used when a part authors none. */
    public float defaultRangeM() {
        return defaultRangeM;
    }

    /** True when a shot of this family exists as an entity between firing and impact. */
    public boolean spawnsProjectile() {
        return delivery == Delivery.BALLISTIC;
    }

    /** True when holding the trigger fires continuously rather than at a fire interval. */
    public boolean isContinuous() {
        return delivery == Delivery.CONTINUOUS;
    }
}
