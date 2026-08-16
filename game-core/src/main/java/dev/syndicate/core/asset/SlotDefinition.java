/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.model.SizeClass;
import java.util.List;
import java.util.Objects;

/**
 * One slot a part type offers (docs/08_asset_pipeline.md#D08-S4.2,
 * docs/05_vehicle_part_system.md#D05-S4.3).
 *
 * <p>A slot is authored on the <em>parent</em> part, not on the child that occupies it: the parent
 * decides where a child attaches, what kind of thing may go there, and how heavy it may be. That is
 * what makes an assembly manifest a list of {@code (slotPath, partTypeId)} pairs rather than a list
 * of transforms — the geometry comes from the part types, so re-authoring a chassis moves every
 * vehicle's hardpoints at once.
 *
 * @param slotId unique within the part, {@code ^[a-z][a-z0-9_]{1,31}$} (D08-R6)
 * @param slotType which part categories may occupy it (D05-S4.3)
 * @param localTransform where the child attaches, in the parent part's local space
 * @param maxMassKg the heaviest part the slot will hold; assembly validation rejects a heavier one
 *     (A306), which is what stops a light chassis from carrying a siege cannon
 * @param sizeClass the bulkiest part the slot will hold (D17-S4.3). A slot accepts its own class and
 *     every class below it, so this is a ceiling rather than an equality. Separate from
 *     {@code maxMassKg} because bulk and load are different questions: a dense small object passes
 *     this gate and fails that one.
 * @param covers slot ids on the <em>same</em> part that a part in this slot shields (D05-S5.8). An
 *     armour plate's coverage is authored here rather than derived from geometry, because "does this
 *     plate protect that hardpoint" is a design decision, not a raycast.
 * @param isDetachable whether a part in this slot may leave the vehicle at all
 */
public record SlotDefinition(
        String slotId,
        SlotType slotType,
        Transform localTransform,
        float maxMassKg,
        SizeClass sizeClass,
        List<String> covers,
        boolean isDetachable) {

    public SlotDefinition {
        Objects.requireNonNull(slotId, "slotId");
        Objects.requireNonNull(slotType, "slotType");
        Objects.requireNonNull(localTransform, "localTransform");
        if (slotId.isBlank()) {
            throw new IllegalArgumentException("slotId must not be blank (D08-R6)");
        }
        if (maxMassKg <= 0f) {
            throw new IllegalArgumentException("slot " + slotId + " has maxMassKg " + maxMassKg
                    + "; a slot that can hold nothing is a content error, not an empty slot (A306)");
        }
        // D17-R8: a slot authored before D17 has no size class and behaves as it always did.
        sizeClass = sizeClass == null ? SizeClass.DEFAULT : sizeClass;
        // Copied, because a part type is shared by every instance of that part in the match and a
        // caller that could mutate the offset afterwards would move parts on live vehicles.
        localTransform = new Transform().set(localTransform);
        covers = covers == null ? List.of() : List.copyOf(covers);
    }

    /** A slot with no coverage, no rotation and the default size class, which is the common case. */
    public static SlotDefinition of(String slotId, SlotType slotType, Transform localTransform, float maxMassKg) {
        return new SlotDefinition(slotId, slotType, localTransform, maxMassKg, SizeClass.DEFAULT, List.of(), true);
    }

    /**
     * True when this slot will hold a part of {@code category}, {@code massKg} and {@code partSize}
     * (D17-R7): the slot type, the size class and the mass cap, all three.
     *
     * <p>Here rather than in {@code AssemblyValidator} so that the rule has exactly one statement.
     * The validator reports <em>which</em> of the three failed, which is why it still tests them
     * separately; anything else asking "does this fit" asks here.
     */
    public boolean accepts(dev.syndicate.model.PartCategory category, float massKg, SizeClass partSize) {
        return slotType.acceptsCategory(category) && massKg <= maxMassKg && sizeClass.accepts(partSize);
    }
}
