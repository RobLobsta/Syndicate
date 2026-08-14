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
