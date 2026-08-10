/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link AssemblyDef} resolved against the asset index: every part's type, its chassis-local
 * placement, and the aggregate the vehicle spawns with
 * (docs/05_vehicle_part_system.md#D05-S5.2, docs/06_physics_simulation.md#D06-S5.3).
 *
 * <p>Both {@link AssemblyValidator} and the spawn path need the same three things — the resolved
 * part types, the accumulated slot transforms, and the mass/COM/power totals — so they are computed
 * once here rather than twice with two chances to disagree about multiplication order. It is the
 * pre-entity twin of {@link SlotChain}, which does the same accumulation over a live vehicle's slot
 * graph.
 *
 * <p><b>Resolution is total, not fail-fast.</b> A placement whose part type is not in the index is
 * recorded in {@link #unresolved()} and left out of {@link #parts()}, so the validator can report
 * every missing reference in one pass instead of one per load attempt (D08-S5.4's report lists all
 * errors before deciding). A caller that skips validation and spawns anyway simply gets the vehicle
 * without those parts, which is what G18's fallback behaviour degrades to.
 */
public final class AssemblyLayout {

    /**
     * One resolved part, positioned in chassis-local space.
     *
     * @param slotPath its stable identity within the assembly (D05-R11); {@code root} for the chassis
     * @param parentSlotPath the slot path of the part offering its slot; null for the chassis
     * @param slotId the slot's id on that parent; null for the chassis
     * @param type the resolved part type
     * @param slot the parent's slot definition it occupies; null for the chassis
     * @param localTransform its offset from the parent, straight off {@link SlotDefinition}; identity
     *     for the chassis
     * @param chassisLocal the accumulated product from the chassis down to it — what the compound
     *     shape and the mass sum are expressed in
     * @param overrides per-instance authoring from the assembly
     */
    public record PlacedPart(
            String slotPath,
            String parentSlotPath,
            String slotId,
            PartType type,
            SlotDefinition slot,
            Matrix4 localTransform,
            Matrix4 chassisLocal,
            AssemblyDef.Overrides overrides) {

        public PlacedPart {
            localTransform = new Matrix4(localTransform);
            chassisLocal = new Matrix4(chassisLocal);
        }

        /** True for the chassis, the one part with no parent and no slot (D05-R10). */
        public boolean isChassis() {
            return parentSlotPath == null;
        }
    }

    private final AssemblyDef assembly;
    private final List<PlacedPart> parts;
    private final List<String> unresolved;
    private final float totalMassKg;
    private final float powerBudget;
    private final Vector3 comLocal;

    private AssemblyLayout(
            AssemblyDef assembly,
            List<PlacedPart> parts,
            List<String> unresolved,
            float totalMassKg,
            float powerBudget,
            Vector3 comLocal) {
        this.assembly = assembly;
        this.parts = List.copyOf(parts);
        this.unresolved = List.copyOf(unresolved);
        this.totalMassKg = totalMassKg;
        this.powerBudget = powerBudget;
        this.comLocal = comLocal;
    }

    /**
     * Resolves an assembly.
     *
     * <p>Parts are visited in ascending slot path order, which is topological order (D08-R11), so a
     * parent's accumulated transform always exists before its children ask for it — one pass, no
     * recursion, and the same order on every peer (G3).
     *
     * @throws IllegalArgumentException if the chassis part type is not in the index. Unlike an
     *     attached part, a missing chassis leaves nothing to hang the rest of the assembly from.
     */
    public static AssemblyLayout resolve(AssemblyDef assembly, AssetIndex assets) {
        Objects.requireNonNull(assembly, "assembly");
        Objects.requireNonNull(assets, "assets");

        PartType chassisType = assets.partType(assembly.chassisPartTypeId());
        if (chassisType == null) {
            throw new IllegalArgumentException(
                    "assembly " + assembly.assemblyId().value() + " names chassis part type "
                            + assembly.chassisPartTypeId().value() + ", which is not loaded (A107)");
        }

        List<PlacedPart> placed = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Map<String, Matrix4> chassisLocalByPath = new LinkedHashMap<>();

        Matrix4 identity = new Matrix4();
        chassisLocalByPath.put(SlotChain.ROOT_SLOT_PATH, identity);
        placed.add(new PlacedPart(
                SlotChain.ROOT_SLOT_PATH,
                null,
                null,
                chassisType,
                null,
                identity,
                identity,
                AssemblyDef.Overrides.NONE));

        Matrix4 local = new Matrix4();
        for (AssemblyDef.PartPlacement placement : assembly.parts()) {
            PartType type = assets.partType(placement.partTypeId());
            Matrix4 parent = chassisLocalByPath.get(placement.parentSlotPath());
            SlotDefinition slot = slotOf(placed, placement);
            if (type == null || parent == null || slot == null) {
                // Left out rather than guessed at: a part with no slot has no position, and placing
                // it at the parent's origin would put geometry inside the parent's hull.
                unresolved.add(placement.slotPath());
                continue;
            }
            slot.localTransform().toMatrix(local);
            Matrix4 chassisLocal = new Matrix4(parent).mul(local);
            chassisLocalByPath.put(placement.slotPath(), chassisLocal);
            placed.add(new PlacedPart(
                    placement.slotPath(),
                    placement.parentSlotPath(),
                    placement.parentSlotId(),
                    type,
                    slot,
                    local,
                    chassisLocal,
                    placement.overrides()));
        }

        float totalMassKg = 0f;
        float powerBudget = 0f;
        Vector3 weighted = new Vector3();
        Vector3 position = new Vector3();
        for (PlacedPart part : placed) {
            totalMassKg += part.type().massKg();
            powerBudget += part.type().powerCost();
            // A part's mass acts at its own centre, not at the slot it hangs from — see
            // PartType.centerOfMassLocal. Transforming the centre by the accumulated slot chain is
            // the same arithmetic MassPropertySystem (15) does over the live vehicle, so a spawned
            // vehicle and a recomputed one agree about where its mass is.
            part.type().centerOfMassLocal(position).mul(part.chassisLocal());
            weighted.add(position.scl(part.type().massKg()));
        }
        Vector3 comLocal = totalMassKg > 0f ? weighted.scl(1f / totalMassKg) : new Vector3();

        return new AssemblyLayout(assembly, placed, unresolved, totalMassKg, powerBudget, comLocal);
    }

    /**
     * The slot a placement occupies, looked up on the part already placed at its parent path.
     *
     * <p>Returns null when the parent is not placed or offers no such slot, which is A304.
     */
    private static SlotDefinition slotOf(List<PlacedPart> placed, AssemblyDef.PartPlacement placement) {
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).slotPath().equals(placement.parentSlotPath())) {
                return placed.get(i).type().slot(placement.parentSlotId());
            }
        }
        return null;
    }

    /** The assembly this layout resolves. */
    public AssemblyDef assembly() {
        return assembly;
    }

    /** Every resolved part in ascending slot path order, the chassis first at {@code root} (G3). */
    public List<PlacedPart> parts() {
        return parts;
    }

    /** The chassis, always present — resolution fails outright without it. */
    public PlacedPart chassis() {
        return parts.get(0);
    }

    /** The resolved part at a slot path, or null. */
    public PlacedPart partAt(String slotPath) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).slotPath().equals(slotPath)) {
                return parts.get(i);
            }
        }
        return null;
    }

    /** Slot paths whose part type or parent slot could not be resolved (A107, A304). */
    public List<String> unresolved() {
        return unresolved;
    }

    /** Kilograms, summed over the resolved parts including wheels (D05-S5.2 step 2). */
    public float totalMassKg() {
        return totalMassKg;
    }

    /** The assembly's balance-budget total (D05-S5.7). */
    public float powerBudget() {
        return powerBudget;
    }

    /** Centre of mass in chassis-local metres, written into {@code out}. */
    public Vector3 comLocal(Vector3 out) {
        return out.set(comLocal);
    }

    /** How many resolved parts have a given category — what the three-wheel rule counts (A309). */
    public int countCategory(PartCategory category) {
        int count = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).type().category() == category) {
                count++;
            }
        }
        return count;
    }

    /** The part type ids in this layout, for diagnostics. */
    public List<AssetId> partTypeIds() {
        List<AssetId> ids = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            ids.add(parts.get(i).type().partTypeId());
        }
        return ids;
    }
}
