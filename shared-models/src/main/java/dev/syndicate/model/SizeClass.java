/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * How bulky a part is, and how bulky a slot's mounting is (docs/17_weapon_system.md#D17-S4.3).
 *
 * <p>This is the mechanism by which not every weapon fits every mount. {@code SlotType} (D05-S4.3)
 * answers <em>what kind of thing</em> may attach — a hardpoint takes weapons and utilities — which is
 * true and, on its own, useless: it does not stop a siege cannon being bolted to a light hatchback's
 * wing mirror. Size class answers <em>how big a thing</em>, and the two gates are independent because
 * they are independent questions.
 *
 * <p><b>Mass is a third gate and is not this one</b> (D17-R7). {@code slot.maxMassKg} is about
 * <em>load</em> — what the mounting can carry without tearing out — and size class is about
 * <em>bulk</em> — whether the thing physically fits and looks like it belongs there. A dense, small
 * object passes the size gate and fails the mass one, which is the correct outcome and is why
 * collapsing the two into a single number was rejected.
 *
 * <p>The ordering is the whole contract: <b>a slot accepts its own class and every class below it</b>
 * (D17-R7.2). A {@code HEAVY} turret ring takes a light machine gun; a {@code LIGHT} flank hardpoint
 * does not take a heavy cannon. {@link #ordinal()} is therefore load-bearing and the declaration order
 * must not change.
 */
public enum SizeClass {

    /** Pintle-mounted: a machine gun, a brake hub, a light module. Fits anywhere. */
    LIGHT,

    /**
     * The default, and what every part and slot authored before D17 is treated as (D17-R8). A file
     * that omits the field keeps behaving exactly as it did, which is what let this gate be added
     * without rewriting the shipped content.
     */
    MEDIUM,

    /** Vehicle-scale: a cannon, a turret gun. Fits only a mounting built for it. */
    HEAVY;

    /** What a part or slot is when its file does not say (D17-R8). */
    public static final SizeClass DEFAULT = MEDIUM;

    /**
     * True when a slot of this class will hold a part of {@code partClass} (D17-R7.2).
     *
     * <p>Read on the <em>slot's</em> class: {@code slot.sizeClass().accepts(part.sizeClass())}.
     */
    public boolean accepts(SizeClass partClass) {
        return partClass != null && partClass.ordinal() <= this.ordinal();
    }

    /**
     * Parses a name, returning {@link #DEFAULT} for null or blank rather than throwing.
     *
     * <p>An unrecognised non-blank name returns null so the caller can report it as A221 rather than
     * silently accepting a typo as {@code MEDIUM} — the difference between "the field is absent" and
     * "the field says {@code HEAVEY}" is exactly the difference between a default and a defect.
     */
    public static SizeClass parse(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        for (SizeClass candidate : values()) {
            if (candidate.name().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        return null;
    }
}
