/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.SizeClass;
import dev.syndicate.model.WeaponFamily;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A modular weapon as a tree of sub-parts (docs/17_weapon_system.md#D17-S4.5).
 *
 * <p><b>Why this exists.</b> DEC-080 settled that a weapon is an assembly rather than a part, and
 * until the garage let a player change one, nothing at runtime needed to know its shape: the
 * vehicle's own {@code assembly.json} already listed every sub-part at its slot path, and the
 * runtime just built what it was told. Choosing a weapon in the garage is the first thing that has
 * to <em>construct</em> that subtree, for a weapon that may be fitted to nothing at all — and the
 * one place the tree is written down is the tool's own manifest (D17-R16).
 *
 * <p>The labels are the tool's taxonomy — {@code mount}, {@code receiver}, {@code barrel} — and the
 * root is always the {@code mount} (D17-R4). Sub-parts are sorted by label so the placements this
 * produces are in a fixed order on every machine (G3).
 *
 * @param weaponId the weapon's own id, which is the manifest's file name and not any part's id
 * @param family which of D01-S4.4's eight
 * @param sizeClass what bulk of slot it needs (D17-S4.3)
 * @param totalMassKg the sum of the sub-parts, as the tool measured it
 * @param rootPartTypeId the {@code mount}, the part that occupies the vehicle's hardpoint
 * @param subParts every other sub-part, each naming the label of its parent
 */
public record WeaponDef(
        AssetId weaponId,
        WeaponFamily family,
        SizeClass sizeClass,
        float totalMassKg,
        AssetId rootPartTypeId,
        List<SubPart> subParts) {

    /** The label the tool gives a weapon's root sub-part (D17-R4). */
    public static final String ROOT_LABEL = "mount";

    /**
     * One sub-part hanging off another.
     *
     * @param parentLabel the taxonomy label of the sub-part it attaches to
     * @param label its own taxonomy label, which is also the {@code sub_} slot id's stem
     * @param partTypeId the part the runtime loads for it
     */
    public record SubPart(String parentLabel, String label, AssetId partTypeId) {
        public SubPart {
            Objects.requireNonNull(parentLabel, "parentLabel");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(partTypeId, "partTypeId");
        }

        /** The slot on the parent that holds this sub-part; {@code sub_} plus the label (D17-R42). */
        public String slotId() {
            return "sub_" + label;
        }
    }

    public WeaponDef {
        Objects.requireNonNull(weaponId, "weaponId");
        Objects.requireNonNull(rootPartTypeId, "rootPartTypeId");
        family = family == null ? WeaponFamily.AUTOCANNON : family;
        sizeClass = sizeClass == null ? SizeClass.DEFAULT : sizeClass;
        List<SubPart> sorted = new ArrayList<>(subParts == null ? List.of() : subParts);
        sorted.sort(Comparator.comparing(SubPart::label));
        subParts = List.copyOf(sorted);
    }

    /**
     * The weapon's sub-parts as placements under {@code rootSlotPath}, root first.
     *
     * <p>Returned in the order a parent precedes its children, which is what
     * {@link AssemblyDef}'s slot-path sort already guarantees — but built by descending the tree
     * rather than by trusting a string sort, because a label is not a path and
     * {@code sub_barrel} sorts before {@code sub_receiver} while hanging off it.
     *
     * @param parentSlotPath the slot path of the part that holds the weapon, usually {@code root}
     * @param parentSlotId the hardpoint the weapon's mount occupies
     */
    public List<AssemblyDef.PartPlacement> placements(String parentSlotPath, String parentSlotId) {
        List<AssemblyDef.PartPlacement> out = new ArrayList<>();
        AssemblyDef.PartPlacement root = AssemblyDef.PartPlacement.of(parentSlotPath, parentSlotId, rootPartTypeId);
        out.add(root);
        appendChildren(ROOT_LABEL, root.slotPath(), out);
        return List.copyOf(out);
    }

    private void appendChildren(String parentLabel, String parentPath, List<AssemblyDef.PartPlacement> out) {
        for (SubPart sub : subParts) {
            if (!sub.parentLabel().equals(parentLabel)) {
                continue;
            }
            AssemblyDef.PartPlacement placement =
                    AssemblyDef.PartPlacement.of(parentPath, sub.slotId(), sub.partTypeId());
            out.add(placement);
            appendChildren(sub.label(), placement.slotPath(), out);
        }
    }

    /** Every part type this weapon brings with it, root included. */
    public List<AssetId> partTypeIds() {
        List<AssetId> out = new ArrayList<>();
        out.add(rootPartTypeId);
        subParts.forEach(sub -> out.add(sub.partTypeId()));
        return List.copyOf(out);
    }
}
