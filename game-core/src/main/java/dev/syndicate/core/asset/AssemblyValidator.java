/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Checks an assembly against the rules that make it spawnable
 * (docs/05_vehicle_part_system.md#D05-S5.1, docs/08_asset_pipeline.md#D08-S5.4 A3xx).
 *
 * <p>Runs twice in the lifetime of a piece of content: once in the asset pipeline at build time,
 * where it fails the build, and once at load, cheaply, where D08-S5.3 re-checks what the pipeline
 * already passed. The second run is not redundant — it is what catches an asset directory edited
 * after the index was built, which is how content diverges from its validation in practice.
 *
 * <p><b>It reports everything it finds.</b> One pass produces the whole list, because a load that
 * stopped at the first error would make fixing a broken vehicle a sequence of edit-run cycles, one
 * per mistake.
 *
 * <p><b>What is deliberately not checked here.</b> A312 (power budget against a class target) needs
 * {@code assets/balance/classes.json}, which is content that does not exist yet (D05-R32) — a
 * budget checked against an invented target would report failures about a number nobody authored.
 * A314's unlock level and the A2xx part-level rules belong to the part loader rather than the
 * assembly; A5xx belongs to the mesh/manifest cross-check of D08-S5.5.
 */
public final class AssemblyValidator {

    /**
     * Fractional tolerance on {@code expected.totalMassKg} (A310).
     *
     * <p>D08 names this {@code MASS_DELTA_FRAC} but D00-S6.4 never defines it; the one mass
     * tolerance the project does define is {@code MASS_TOLERANCE_FRAC} (2%, G7), and having two
     * different mass tolerances would be a distinction without a difference. Aliased rather than
     * duplicated so there is one number (DEC-020).
     */
    public static final float MASS_DELTA_FRAC = SimulationConstants.MASS_TOLERANCE_FRAC;

    /**
     * Metres of tolerance on {@code expected.comLocal} (A311).
     *
     * <p>D08-R10 names {@code COM_OFFSET_M} and no document defines it. One centimetre: below the
     * collision margin (D06-R13), so a COM drift this small cannot move a contact point, and far
     * enough above float noise on a 64-part sum that it will not trip on rounding (DEC-020).
     */
    public static final float COM_OFFSET_M = 0.01f;

    /** The fewest wheels a vehicle can be driven on (D05-S5.1, A309). */
    public static final int MIN_WHEELS = 3;

    private AssemblyValidator() {
        throw new AssertionError("no instances");
    }

    /**
     * Validates an assembly against the loaded part catalogue.
     *
     * @return every finding, in the order the rules are checked; empty when the assembly is clean
     */
    public static List<ValidationIssue> validate(AssemblyDef assembly, AssetIndex assets) {
        List<ValidationIssue> issues = new ArrayList<>();
        String subject = assembly.assemblyId().value();

        // A301, first half: the root must resolve and must be a chassis. Checked before anything
        // else because AssemblyLayout cannot resolve an assembly without it.
        PartType chassisType = assets.partType(assembly.chassisPartTypeId());
        if (chassisType == null) {
            issues.add(ValidationIssue.error(
                    "A107",
                    subject,
                    "chassis part type " + assembly.chassisPartTypeId().value() + " is not loaded"));
            return issues;
        }
        if (chassisType.category() != PartCategory.CHASSIS) {
            issues.add(ValidationIssue.error(
                    "A301",
                    subject,
                    "root part " + chassisType.partTypeId().value() + " is category " + chassisType.category()
                            + ", not chassis"));
        }

        // A302: the cap includes the chassis (D05-R1).
        if (assembly.partCount() > SimulationConstants.MAX_PARTS_PER_VEHICLE) {
            issues.add(ValidationIssue.error(
                    "A302",
                    subject,
                    assembly.partCount() + " parts exceeds MAX_PARTS_PER_VEHICLE ("
                            + SimulationConstants.MAX_PARTS_PER_VEHICLE + ")"));
        }

        checkPlacements(assembly, assets, issues);

        AssemblyLayout layout = AssemblyLayout.resolve(assembly, assets);
        checkWheelCount(layout, issues);
        checkCoverage(layout, issues);
        checkExpected(assembly, layout, issues);
        return issues;
    }

    /** A303, A304, A305, A306, A307, A308 and the A107 that precedes them. */
    private static void checkPlacements(AssemblyDef assembly, AssetIndex assets, List<ValidationIssue> issues) {
        // Slot path -> the part type that occupies it, built as we descend. Sorted so the traversal
        // and therefore the findings are in the same order on every run (G3).
        TreeMap<String, PartType> occupantByPath = new TreeMap<>();
        occupantByPath.put(SlotChain.ROOT_SLOT_PATH, assets.partType(assembly.chassisPartTypeId()));
        Set<String> occupiedSlots = new TreeSet<>();

        for (AssemblyDef.PartPlacement placement : assembly.parts()) {
            String path = placement.slotPath();

            // A303: the path must be derivable from the parent, or the tree the rest of the engine
            // walks by string prefix is not the tree the author drew.
            String derived = placement.parentSlotPath() + "/" + placement.parentSlotId();
            if (!derived.equals(path)) {
                issues.add(ValidationIssue.error(
                        "A303", path, "slotPath must equal parentSlotPath + \"/\" + parentSlotId (" + derived + ")"));
            }

            // A307: the same slot filled twice. Checked on the (parent, slot) pair rather than on
            // the path so it still fires when A303 has already made the path disagree with it.
            if (!occupiedSlots.add(derived)) {
                issues.add(ValidationIssue.error("A307", path, "slot " + derived + " is occupied twice"));
            }

            PartType type = assets.partType(placement.partTypeId());
            if (type == null) {
                issues.add(ValidationIssue.error(
                        "A107", path, "part type " + placement.partTypeId().value() + " is not loaded"));
                continue;
            }

            // A308: ascending slot path order is topological (D08-R11), so a parent that is still
            // absent when its child is reached is not merely out of order — it is not in the
            // assembly at all, which is exactly "unreachable from root".
            PartType parentType = occupantByPath.get(placement.parentSlotPath());
            if (parentType == null) {
                issues.add(ValidationIssue.error(
                        "A308", path, "parent " + placement.parentSlotPath() + " is not part of this assembly"));
                continue;
            }

            SlotDefinition slot = parentType.slot(placement.parentSlotId());
            if (slot == null) {
                issues.add(ValidationIssue.error(
                        "A304",
                        path,
                        "part " + parentType.partTypeId().value() + " offers no slot " + placement.parentSlotId()));
                continue;
            }
            if (!slot.slotType().acceptsCategory(type.category())) {
                issues.add(ValidationIssue.error(
                        "A305", path, "category " + type.category() + " cannot occupy a " + slot.slotType() + " slot"));
            }
            if (type.massKg() > slot.maxMassKg()) {
                issues.add(ValidationIssue.error(
                        "A306",
                        path,
                        "part mass " + type.massKg() + " kg exceeds the slot's limit of " + slot.maxMassKg() + " kg"));
            }
            occupantByPath.put(path, type);
        }
    }

    /** A309: a vehicle needs three wheels to be drivable at all. */
    private static void checkWheelCount(AssemblyLayout layout, List<ValidationIssue> issues) {
        int wheels = layout.countCategory(PartCategory.WHEEL);
        if (wheels < MIN_WHEELS) {
            issues.add(ValidationIssue.error(
                    "A309",
                    layout.assembly().assemblyId().value(),
                    wheels + " wheels; a vehicle needs at least " + MIN_WHEELS + " to be drivable"));
        }
    }

    /** A313: two armour parts claiming the same slot, which D05-E4 makes an authoring mistake. */
    private static void checkCoverage(AssemblyLayout layout, List<ValidationIssue> issues) {
        Set<String> covered = new HashSet<>();
        for (AssemblyLayout.PlacedPart part : layout.parts()) {
            if (part.type().category() != PartCategory.PANEL || part.slot() == null) {
                continue;
            }
            for (String coveredSlotId : part.slot().covers()) {
                String coveredPath = part.parentSlotPath() + "/" + coveredSlotId;
                if (!covered.add(coveredPath)) {
                    issues.add(ValidationIssue.warn(
                            "A313",
                            part.slotPath(),
                            "slot " + coveredPath + " is covered by more than one armour part; only one "
                                    + "will absorb for it (D05-E4)"));
                }
            }
        }
    }

    /** A310 and A311: the authored assertion against what the parts actually add up to (D08-R10). */
    private static void checkExpected(AssemblyDef assembly, AssemblyLayout layout, List<ValidationIssue> issues) {
        AssemblyDef.Expected expected = assembly.expected();
        if (expected == null) {
            return;
        }
        String subject = assembly.assemblyId().value();
        float computedMass = layout.totalMassKg();
        if (expected.totalMassKg() > 0f
                && Math.abs(computedMass - expected.totalMassKg()) > MASS_DELTA_FRAC * expected.totalMassKg()) {
            issues.add(ValidationIssue.error(
                    "A310",
                    subject,
                    "expected totalMassKg " + expected.totalMassKg() + " but the parts sum to " + computedMass));
        }
        Vector3 computedCom = layout.comLocal(new Vector3());
        if (computedCom.dst(expected.comLocal()) > COM_OFFSET_M) {
            issues.add(ValidationIssue.error(
                    "A311",
                    subject,
                    "expected comLocal " + expected.comLocal() + " but the parts give " + computedCom));
        }
    }

    /** Every blocking finding in a list, which is what a strict load refuses on (D08-S5.4). */
    public static List<ValidationIssue> blocking(List<ValidationIssue> issues) {
        List<ValidationIssue> blocking = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            if (issues.get(i).isBlocking()) {
                blocking.add(issues.get(i));
            }
        }
        return blocking;
    }

    /** A one-line summary of a finding list, for a log line or an assertion message. */
    public static String summarise(AssetId assemblyId, List<ValidationIssue> issues) {
        StringBuilder text = new StringBuilder(assemblyId.value()).append(": ");
        if (issues.isEmpty()) {
            return text.append("valid").toString();
        }
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append(issues.get(i));
        }
        return text.toString();
    }
}
