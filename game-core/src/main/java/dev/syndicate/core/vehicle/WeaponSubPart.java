/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import java.util.Locale;

/**
 * The nine sub-part labels a modular weapon is built from
 * (docs/17_weapon_system.md#D17-S4.2, docs/17_weapon_system.md#D17-S5.13).
 *
 * <p>The taxonomy is closed: {@code syndicate_weapon} labels every shell it keeps as exactly one of
 * these, and D17-S5.13 is the table saying what losing each one costs. This enum exists so that
 * table can be written once, in {@link WeaponSubPartDegradation}, rather than as string comparisons
 * scattered through the systems that care.
 *
 * <p><b>The label is read off the slot id, not off the part.</b> {@code syndicate_weapon} hangs each
 * sub-part on a {@code SUBSLOT} named {@code sub_<label>} (D17-S5.8), so the slot graph already
 * carries the taxonomy and no new asset field is needed to recover it. A trailing {@code _l} or
 * {@code _r} distinguishes a mirrored pair — the cannon's two elevation cogs are {@code sub_gear_l}
 * and {@code sub_gear_r} — and says nothing about the label, so it is stripped.
 */
public enum WeaponSubPart {

    /** Bolts to the vehicle's hardpoint, carries the {@code weapon} block, and is the part that fires. */
    MOUNT,

    /** The action. Losing it stops the weapon firing at all (D17-R61). */
    RECEIVER,

    /** Losing it collapses accuracy and halves range (D17-R61). */
    BARREL,

    /** Losing it halves the fire rate (D17-R61). */
    BREECH,

    /** Magazine, drum or belt. Losing it costs the weapon its ammunition capacity (D17-R61). */
    FEED,

    /** Flash hider, brake or shroud. Cosmetic. */
    MUZZLE,

    /** A cog or ring. Losing it freezes the aiming articulation (D17-R15), which is cosmetic. */
    GEAR,

    /** Optic or iron sight. Cosmetic. */
    SIGHT,

    /** Grips, stocks, rails, brackets. Cosmetic. */
    FURNITURE;

    /** The {@code sub_} prefix every weapon sub-slot id carries (D17-S5.8). */
    public static final String SLOT_PREFIX = "sub_";

    /**
     * The label a sub-slot id names, or null when the id is not a weapon sub-slot.
     *
     * <p>Null rather than an exception or a default: a vehicle's own slots — {@code wheel_fl},
     * {@code door_l}, {@code turret_main} — flow through the same walk, and treating one of them as
     * an unrecognised weapon sub-part would be a louder answer than the honest one.
     */
    public static WeaponSubPart fromSlotId(String slotId) {
        if (slotId == null || !slotId.startsWith(SLOT_PREFIX)) {
            return null;
        }
        String label = slotId.substring(SLOT_PREFIX.length());
        // A mirrored pair shares one label: sub_gear_l and sub_gear_r are both GEAR.
        if (label.endsWith("_l") || label.endsWith("_r")) {
            label = label.substring(0, label.length() - 2);
        }
        try {
            return valueOf(label.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notATaxonomyLabel) {
            // A weapon built by hand may hang something the taxonomy does not name. D17-E11's rule
            // applies: a sub-part the table has no row for is a target that degrades nothing.
            return null;
        }
    }
}
