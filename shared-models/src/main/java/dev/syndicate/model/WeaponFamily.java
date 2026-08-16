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
    AUTOCANNON(Delivery.BALLISTIC, DamageType.KINETIC, 200f, 0.12f, 600f, 1f),

    /** Slow heavy ballistic shell; can detach a part in one hit. */
    CANNON(Delivery.BALLISTIC, DamageType.KINETIC, 300f, 12f, 250f, 1f),

    /** Multi-pellet hitscan cone; spreads damage across several parts. */
    SHOTGUN(Delivery.HITSCAN, DamageType.KINETIC, 25f, 0.05f, 400f, 1f),

    /**
     * Slow projectile with a blast radius; propagates strongly (D07-S5.4).
     *
     * <p>Carries momentum and therefore knocks back, but its recoil fraction is <b>zero</b>: a rocket
     * accelerates on its own motor after it has left the tube, so the launcher never takes the round's
     * momentum. This is the one family where recoil and knockback are not the same number, and getting
     * it wrong would have a rocket pod shoving a car backwards as hard as a cannon does.
     */
    ROCKET(Delivery.BALLISTIC, DamageType.EXPLOSIVE, 150f, 8f, 120f, 0f),

    /** High-arc indirect fire; lands on top hit zones. */
    MORTAR(Delivery.BALLISTIC, DamageType.EXPLOSIVE, 120f, 4f, 100f, 1f),

    /** Short continuous cone; applies burn stacks. */
    FLAMER(Delivery.CONTINUOUS, DamageType.INCENDIARY, 12f, 0f, 0f, 0f),

    /** Hitscan continuous beam; ignores a fraction of armour, heat-limited. */
    LASER(Delivery.CONTINUOUS, DamageType.ENERGY, 100f, 0f, 0f, 0f),

    /** Ramming. A chassis property rather than a part; damage comes from momentum (D07-S5.2). */
    RAM(Delivery.CONTACT, DamageType.COLLISION, 0f, 0f, 0f, 0f);

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
    private final float projectileMassKg;
    private final float nominalSpeedMps;
    private final float recoilFraction;

    WeaponFamily(
            Delivery delivery,
            DamageType damageType,
            float defaultRangeM,
            float projectileMassKg,
            float nominalSpeedMps,
            float recoilFraction) {
        this.delivery = delivery;
        this.damageType = damageType;
        this.defaultRangeM = defaultRangeM;
        this.projectileMassKg = projectileMassKg;
        this.nominalSpeedMps = nominalSpeedMps;
        this.recoilFraction = recoilFraction;
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

    /**
     * Kilograms of one shot, which is what makes recoil and knockback a formula rather than a
     * constant (docs/17_weapon_system.md#D17-S5.12).
     *
     * <p>This is the number that separates a cannon from a machine gun physically rather than by
     * fiat: a 12 kg shell at 250 m/s carries 3,000 N·s and a 0.12 kg round at 600 m/s carries 72,
     * so the same formula gives the cannon a shove a player can feel and the machine gun one that is
     * correctly imperceptible. Zero for the families that fire no mass at all.
     */
    public float projectileMassKg() {
        return projectileMassKg;
    }

    /**
     * Metres per second used for momentum when a shot has no travelling entity to read a speed from.
     *
     * <p>A hitscan family's shot arrives in the tick it is fired, so there is no
     * {@code projectileSpeedMps} stat that means anything physical — but a shotgun still kicks. This
     * is the speed its momentum is computed at, and it is used only for that.
     */
    public float nominalSpeedMps() {
        return nominalSpeedMps;
    }

    /**
     * How much of a shot's momentum the firing vehicle takes back, in {@code [0,1]}.
     *
     * <p>One for every family that pushes its shot out with a propellant charge, and <b>zero for
     * {@link #ROCKET}</b>, which does not: a rocket carries its own motor, so the launcher never sees
     * the round's momentum. Knockback on the target is unaffected — the rocket still arrives with all
     * of it.
     */
    public float recoilFraction() {
        return recoilFraction;
    }

    /** Newton-seconds one shot carries at {@code speedMps}, or at the nominal speed when that is 0. */
    public float shotMomentumNs(float speedMps) {
        float speed = speedMps > 0f ? speedMps : nominalSpeedMps;
        return projectileMassKg * speed;
    }
}
