/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.model.AssetId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A prebuilt vehicle, as authored (docs/08_asset_pipeline.md#D08-S4.4,
 * docs/05_vehicle_part_system.md#D05-R2).
 *
 * <p>An assembly is data, not code: it names one chassis part type and, for every other position, a
 * slot path and the part type that occupies it. Where each part physically sits comes from the
 * <em>parent's</em> {@link SlotDefinition}, so this record carries no transforms at all — moving a
 * chassis hardpoint in the art moves it on every vehicle that uses that chassis.
 *
 * <p><b>The chassis is a field, not a row in {@link #parts()}.</b> D08-S4.4's JSON has a top-level
 * {@code chassis} and lists only the attached parts, while D05-S5.1's validation pseudocode reads
 * the root out of {@code parts} by looking for a null {@code parentSlotPath}. The file format wins
 * (D08 owns the schema per D00-S4.2), and modelling the root as a field rather than a convention
 * makes "exactly one root, and it is a chassis" (A301) partly structural instead of entirely a
 * check — the half that remains is that the named type really is of category {@code chassis}
 * (DEC-021).
 *
 * @param assemblyId the manifest's {@code vehicleTypeId}; what
 *     {@code VehicleChassisComponent.assemblyId} records
 * @param vehicleClass {@code light}, {@code medium} or {@code heavy}; the power-budget class target
 *     is looked up by it (D05-R30)
 * @param chassisPartTypeId the root part, at slot path {@code root}
 * @param parts every other part, sorted by slot path so a parent is always constructed before its
 *     children (D08-R11)
 * @param expected the authored assertion about the computed aggregate, or null when the assembly
 *     declares none (D08-R10)
 */
public record AssemblyDef(
        AssetId assemblyId,
        String vehicleClass,
        AssetId chassisPartTypeId,
        List<PartPlacement> parts,
        Expected expected) {

    public AssemblyDef {
        Objects.requireNonNull(assemblyId, "assemblyId");
        Objects.requireNonNull(chassisPartTypeId, "chassisPartTypeId");
        vehicleClass = vehicleClass == null ? "medium" : vehicleClass;
        // Sorted here, once, rather than at every traversal. A parent's slot path is a prefix of its
        // children's and a prefix sorts first, so ascending slot path order *is* topological order
        // (D08-R11) — the same property SlotChain relies on.
        List<PartPlacement> sorted = new ArrayList<>(parts == null ? List.of() : parts);
        sorted.sort(Comparator.comparing(PartPlacement::slotPath));
        parts = List.copyOf(sorted);
    }

    /**
     * One part's position in the assembly.
     *
     * @param slotPath the {@code /}-joined path from the chassis; must equal
     *     {@code parentSlotPath + "/" + parentSlotId} (D08-R11, A303)
     * @param parentSlotPath the path of the part offering the slot; {@code root} for a part on the
     *     chassis
     * @param parentSlotId the slot's id on that parent
     * @param partTypeId which part type occupies it
     * @param overrides per-instance authoring that the part type cannot know — which wheels steer,
     *     which weapon group a hardpoint fires with
     */
    public record PartPlacement(
            String slotPath, String parentSlotPath, String parentSlotId, AssetId partTypeId, Overrides overrides) {

        public PartPlacement {
            Objects.requireNonNull(slotPath, "slotPath");
            Objects.requireNonNull(parentSlotPath, "parentSlotPath");
            Objects.requireNonNull(parentSlotId, "parentSlotId");
            Objects.requireNonNull(partTypeId, "partTypeId");
            overrides = overrides == null ? Overrides.NONE : overrides;
        }

        /** A placement whose slot path is derived from its parent, which is the only legal form. */
        public static PartPlacement of(String parentSlotPath, String parentSlotId, AssetId partTypeId) {
            return new PartPlacement(
                    parentSlotPath + "/" + parentSlotId, parentSlotPath, parentSlotId, partTypeId, Overrides.NONE);
        }

        /** The same placement with different overrides. */
        public PartPlacement with(Overrides newOverrides) {
            return new PartPlacement(slotPath, parentSlotPath, parentSlotId, partTypeId, newOverrides);
        }

        /** Whether this part hangs directly off the chassis. */
        public boolean isOnChassis() {
            return SlotChain.ROOT_SLOT_PATH.equals(parentSlotPath);
        }
    }

    /**
     * Per-instance authoring from the assembly's {@code overrides} block (D08-S4.4).
     *
     * <p>Typed rather than a free-form map: the three keys the schema shows are the three a spawned
     * part can act on, and a map would let a typo — {@code isSteerable} — pass validation and
     * silently produce a vehicle that cannot turn.
     *
     * @param isSteering whether steering input turns this wheel; null means the part type decides
     * @param isDriven whether engine force is applied at this wheel; null means the part type decides
     * @param weaponGroup which {@code PlayerInput.fireMask} bit fires this weapon; null means group 0
     */
    public record Overrides(Boolean isSteering, Boolean isDriven, Integer weaponGroup) {

        /** No overrides: every field falls back to the part type. */
        public static final Overrides NONE = new Overrides(null, null, null);

        /** A wheel that steers and/or drives. */
        public static Overrides wheel(boolean steering, boolean driven) {
            return new Overrides(steering, driven, null);
        }

        /** A weapon on a given fire group. */
        public static Overrides weapon(int group) {
            return new Overrides(null, null, group);
        }
    }

    /**
     * The authored assertion about this assembly's computed aggregate (D08-R10).
     *
     * <p>Checked, never used as an input. The point is content drift: a part's mass changing without
     * its vehicles being re-checked would otherwise re-balance every assembly that uses it, silently
     * (A310, A311).
     */
    public record Expected(float totalMassKg, float powerBudget, Vector3 comLocal) {

        public Expected {
            comLocal = comLocal == null ? new Vector3() : new Vector3(comLocal);
        }
    }

    /** How many parts the assembly has, chassis included — what A302 caps. */
    public int partCount() {
        return parts.size() + 1;
    }

    /** The placement at a slot path, or null. */
    public PartPlacement placementAt(String slotPath) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).slotPath().equals(slotPath)) {
                return parts.get(i);
            }
        }
        return null;
    }
}
