/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * The six part categories of docs/05_vehicle_part_system.md#D05-S4.2 (D05-R5).
 *
 * <p>A category determines slot compatibility and how a part contributes to vehicle stats. It is
 * not a part type: a part type is one authored definition, a category is a class of them (D00-S6.1).
 */
public enum PartCategory {

    /** The single root part. Destroying it destroys the vehicle; it never detaches. */
    CHASSIS(false, true),

    /**
     * Bodywork: a door, a bonnet, a bootlid, a wing, a bumper. Absorbs damage before what it covers.
     *
     * <p>Called {@code ARMOR} until DEC-073. The rename separates two things that had come to share
     * a word: what a part <em>is</em> (bodywork the vehicle came with) and how much protection it
     * <em>gives</em>, which is {@code armorValue} and which every category carries. It also leaves
     * the word "armour" free for fitted plating, should the product ever offer a choice of it —
     * D01-NG1 says it does not today.
     */
    PANEL(true, true),

    /** Ground contact, drive, steering. Not in the compound shape — wheels are ray casts (D06-S4.3). */
    WHEEL(true, false),

    /**
     * A lifting rotor: what holds a rotorcraft up and what turns it (D05-S4.2, amended for the
     * Kestrel).
     *
     * <p>It is not a {@code WHEEL} and the difference is not cosmetic. A wheel is a ray cast and
     * carries no hull (D06-S4.3); a rotor is real geometry above the vehicle that a shot can reach,
     * so it is <em>in</em> the compound shape. A wheel converts engine force into traction against
     * a surface; a rotor converts it into thrust against nothing, which is why lift is a body force
     * and not a wheel command.
     *
     * <p>It detaches on destroy, and that is the whole point of making it a part rather than a
     * number on the chassis: shoot the main rotor off a helicopter and it stops flying, in the same
     * arithmetic that already makes a wheel-less car stop driving.
     */
    ROTOR(true, true),

    /** Damage output. */
    WEAPON(true, true),

    /** Support effects: ammo feed, radar, cooler, plating booster. */
    UTILITY(true, true),

    /** Cosmetic only; contributes mass and nothing else. */
    DECORATIVE(true, true);

    private final boolean detachesOnDestroy;
    private final boolean inCompoundShape;

    PartCategory(boolean detachesOnDestroy, boolean inCompoundShape) {
        this.detachesOnDestroy = detachesOnDestroy;
        this.inCompoundShape = inCompoundShape;
    }

    /** True when destroying a part of this category detaches it (D05-S4.2). */
    public boolean detachesOnDestroy() {
        return detachesOnDestroy;
    }

    /** True when this category contributes a child hull to the vehicle's compound shape. */
    public boolean isInCompoundShape() {
        return inCompoundShape;
    }
}
