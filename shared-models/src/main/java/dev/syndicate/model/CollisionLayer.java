/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * Bullet collision layers and their masks (docs/06_physics_simulation.md#D06-S4.4).
 *
 * <p>A pair collides only when each side's mask contains the other's layer. Two of these masks
 * encode gameplay decisions rather than physics ones, and both are load-bearing:
 *
 * <ul>
 *   <li>Debris collides with vehicles but deals no damage (D06-R10), so a player cannot be killed
 *       by their own scrap.
 *   <li>Projectiles pass through debris (D06-R11), so a shard cloud never acts as spaced armour and
 *       outcomes never depend on cosmetic-looking clutter.
 * </ul>
 */
public enum CollisionLayer {
    STATIC(1 << 0),
    VEHICLE(1 << 1),
    PROJECTILE(1 << 2),
    DEBRIS(1 << 3),
    PROP(1 << 4),
    TRIGGER(1 << 5),
    SENSOR_RAY(1 << 6);

    private final int bit;

    CollisionLayer(int bit) {
        this.bit = bit;
    }

    /** This layer's single bit. */
    public int bit() {
        return bit;
    }

    /** The set of layers this one collides with, as a Bullet collision mask (D06-S4.4). */
    public int mask() {
        return switch (this) {
            case STATIC -> VEHICLE.bit | PROJECTILE.bit | DEBRIS.bit | PROP.bit;
            case VEHICLE -> STATIC.bit | VEHICLE.bit | PROJECTILE.bit | PROP.bit | DEBRIS.bit | TRIGGER.bit;
            case PROJECTILE -> STATIC.bit | VEHICLE.bit | PROP.bit;
            case DEBRIS -> STATIC.bit | PROP.bit | DEBRIS.bit | VEHICLE.bit;
            case PROP -> STATIC.bit | VEHICLE.bit | PROJECTILE.bit | DEBRIS.bit | PROP.bit;
            case TRIGGER -> VEHICLE.bit;
            case SENSOR_RAY -> STATIC.bit | VEHICLE.bit | PROP.bit;
        };
    }

    /**
     * True when a body on this layer would collide with one on {@code other}.
     *
     * <p>Applies to pair collision only. {@link #SENSOR_RAY} is not a body layer — it is the filter
     * group Bullet's ray tests use — so its relationship with other layers is deliberately
     * one-directional and this method reports false for it.
     */
    public boolean collidesWith(CollisionLayer other) {
        return (mask() & other.bit) != 0 && (other.mask() & bit) != 0;
    }

    /** True when a ray cast on the {@code SENSOR_RAY} filter group would hit a body on this layer. */
    public boolean isVisibleToSensorRays() {
        return (SENSOR_RAY.mask() & bit) != 0;
    }
}
