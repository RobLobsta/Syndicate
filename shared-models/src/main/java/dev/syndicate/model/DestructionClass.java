/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

/**
 * How a part fails (docs/15_vehicle_preparation_pipeline.md#D15-S5.7).
 *
 * <p>D15-R32 maps every part label to one of these, and every class to one authoring treatment. The
 * class is a property of <b>what the part is</b>, not of what it is made of: a chassis rail and a
 * door skin can be the same steel and fail completely differently, because one is load-bearing
 * geometry that buckles globally and the other is a panel that dents locally.
 *
 * <p>D15-R33 is the rule that keeps the set small — a part needing different numbers is evidence the
 * taxonomy is missing a class, not that the part needs hand-tuning.
 */
public enum DestructionClass {

    /**
     * Panels: bonnet, boot, doors, wings, bumpers.
     *
     * <p>Subdivided to a fine edge length and given damage shape keys at 25/50/75/100%. A panel
     * crumples locally and keeps its area, so it needs vertex density where the dent is or the dent
     * is a facet.
     */
    SHEET_METAL,

    /**
     * Glazing and lamp lenses.
     *
     * <p>No shape keys at all. Glass does not dent: a deformed windscreen reads as a bug, a
     * shattered one reads instantly. Cell-fractured at authoring time; at runtime it is intact or it
     * is gone.
     */
    GLASS,

    /**
     * The chassis, the engine, anything load-bearing.
     *
     * <p>A coarse lattice and a plasticity yield threshold in newton-seconds, so "the frame buckles
     * before the mounts shear" is a comparison between two numbers in the same unit as
     * {@code breakImpulseN} (D15-R34). Deliberately not finely subdivided — that makes a chassis
     * squish like a sponge, which is the failure mode this class exists to avoid.
     */
    STRUCTURAL,

    /**
     * Calipers, mirrors, lamp housings, wheels.
     *
     * <p>No deformation. It survives, or it leaves whole, or it fractures if a manifest was authored
     * for it.
     */
    RIGID,

    /** Decals and interiors. Untouched: a decal rides its host and an interior is never hit. */
    NONE;

    /** Whether parts of this class carry damage morph targets (AC-D15-10). */
    public boolean hasDamageShapeKeys() {
        return this == SHEET_METAL || this == STRUCTURAL;
    }

    /**
     * The class a part of this category gets when it authors none.
     *
     * <p>{@link PartCategory} is the runtime shape of D15-S4.1's label taxonomy — coarser, because
     * the simulation cares about what a part <em>does</em> and the pipeline cares about what it
     * <em>is</em>. This is the projection between them, and it exists so every part has a
     * destruction treatment without every part.json having to name one.
     *
     * <p>{@code PANEL} maps to {@code SHEET_METAL} rather than {@code STRUCTURAL}: bodywork is hung
     * on the frame, and what a player watches is it crumpling and falling off, not the frame behind
     * it buckling.
     *
     * <p>{@code ROTOR} maps to {@code RIGID} — it detaches whole rather than denting or shattering.
     * A bent rotor blade is not a thing anyone watches for: what reads at speed is the disc leaving
     * the mast, and D15-S5.7 gives {@code RIGID} exactly that and no authored transform.
     */
    public static DestructionClass forCategory(PartCategory category) {
        if (category == null) {
            return RIGID;
        }
        return switch (category) {
            case CHASSIS -> STRUCTURAL;
            case PANEL -> SHEET_METAL;
            case WHEEL, ROTOR, WEAPON, UTILITY -> RIGID;
            case DECORATIVE -> NONE;
        };
    }
}
