/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Assembly validation (docs/05_vehicle_part_system.md#D05-S5.1,
 * docs/08_asset_pipeline.md#D08-S5.4 A3xx).
 */
@Tag("unit")
class AssemblyValidatorTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_raider");
    private static final AssetId CHASSIS = AssetId.of("chassis_medium_01");
    private static final AssetId WHEEL = AssetId.of("wheel_road_01");
    private static final AssetId PLATE = AssetId.of("armor_plate_medium_01");

    /** A one-metre box, the smallest mesh whose hull encloses a volume. */
    private static MeshData box() {
        return new MeshData(new float[] {
            -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 0.5f,
            -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
        });
    }

    private static Transform at(float x, float y, float z) {
        Transform transform = new Transform();
        transform.position.set(x, y, z);
        return transform;
    }

    /** A four-wheeled chassis with one armour panel — the shape of a valid vehicle. */
    private static InMemoryAssetIndex catalogue() {
        PartType.Builder chassis =
                PartType.builder(CHASSIS, PartCategory.CHASSIS, box()).massKg(1000f);
        chassis.slot(SlotDefinition.of("wheel_fl", SlotType.WHEEL, at(-1f, -0.4f, 1.4f), 200f));
        chassis.slot(SlotDefinition.of("wheel_fr", SlotType.WHEEL, at(1f, -0.4f, 1.4f), 200f));
        chassis.slot(SlotDefinition.of("wheel_rl", SlotType.WHEEL, at(-1f, -0.4f, -1.4f), 200f));
        chassis.slot(SlotDefinition.of("wheel_rr", SlotType.WHEEL, at(1f, -0.4f, -1.4f), 200f));
        chassis.slot(SlotDefinition.of("armor_front", SlotType.ARMOR_PANEL, at(0f, 0f, 2f), 400f));

        return new InMemoryAssetIndex()
                .put(chassis.build())
                .put(PartType.builder(WHEEL, PartCategory.WHEEL, box())
                        .massKg(50f)
                        .build())
                .put(PartType.builder(PLATE, PartCategory.ARMOR, box())
                        .massKg(340f)
                        .build());
    }

    private static List<AssemblyDef.PartPlacement> fourWheels() {
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>();
        for (String slot : new String[] {"wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"}) {
            parts.add(AssemblyDef.PartPlacement.of("root", slot, WHEEL));
        }
        return parts;
    }

    private static AssemblyDef assemblyOf(List<AssemblyDef.PartPlacement> parts) {
        return new AssemblyDef(ASSEMBLY, "medium", CHASSIS, parts, null);
    }

    private static List<String> codes(List<ValidationIssue> issues) {
        return issues.stream().map(ValidationIssue::code).toList();
    }

    @Test
    void aWellFormedAssembly_producesNoFindings() {
        assertThat(AssemblyValidator.validate(assemblyOf(fourWheels()), catalogue()))
                .isEmpty();
    }

    @Test
    void aRootThatIsNotAChassis_isA301() {
        // AC-D05-1 / T-D05-1. The one structural rule an assembly file cannot express — the chassis
        // is a field, so there is always exactly one root — is that the named type really is one.
        InMemoryAssetIndex assets = catalogue();
        AssemblyDef assembly = new AssemblyDef(ASSEMBLY, "medium", WHEEL, fourWheels(), null);

        assertThat(codes(AssemblyValidator.validate(assembly, assets))).contains("A301");
    }

    @Test
    void anUnloadedChassis_isA107_andStopsTheRest() {
        // Nothing downstream can be resolved without a root to hang it from, so this is the one
        // finding that ends the pass rather than adding to it.
        List<ValidationIssue> issues = AssemblyValidator.validate(
                new AssemblyDef(ASSEMBLY, "medium", AssetId.of("chassis_missing"), fourWheels(), null), catalogue());

        assertThat(codes(issues)).containsExactly("A107");
    }

    @Test
    void aSlotPathThatDoesNotMatchItsParent_isA303() {
        // D08-R11. The engine walks the slot tree by string prefix, so a path that disagrees with
        // its parent is not the tree the author drew.
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels());
        parts.add(new AssemblyDef.PartPlacement(
                "root/somewhere_else", "root", "armor_front", PLATE, AssemblyDef.Overrides.NONE));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A303");
    }

    @Test
    void aSlotTheParentDoesNotOffer_isA304() {
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels());
        parts.add(AssemblyDef.PartPlacement.of("root", "hardpoint_nonexistent", PLATE));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A304");
    }

    @Test
    void aCategoryTheSlotDoesNotAccept_isA305() {
        // D05-S4.3. An armour panel slot takes armour; putting a wheel there would give the vehicle
        // a fifth wheel bolted to its face.
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels());
        parts.add(AssemblyDef.PartPlacement.of("root", "armor_front", WHEEL));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A305");
    }

    @Test
    void aPartHeavierThanItsSlotAllows_isA306() {
        // The rule that stops a light chassis carrying a siege cannon. The armour slot takes 400 kg;
        // this plate is 900.
        InMemoryAssetIndex assets = catalogue();
        assets.put(
                PartType.builder(PLATE, PartCategory.ARMOR, box()).massKg(900f).build());
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels());
        parts.add(AssemblyDef.PartPlacement.of("root", "armor_front", PLATE));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), assets))).contains("A306");
    }

    @Test
    void aSlotFilledTwice_isA307() {
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels());
        parts.add(AssemblyDef.PartPlacement.of("root", "armor_front", PLATE));
        parts.add(AssemblyDef.PartPlacement.of("root", "armor_front", PLATE));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A307");
    }

    @Test
    void aPartWhoseParentIsNotInTheAssembly_isA308() {
        // AC-D05-4. Ascending slot path order is topological, so a parent still absent when its
        // child is reached is not merely out of order — it is not in the assembly.
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels());
        parts.add(AssemblyDef.PartPlacement.of("root/turret", "barrel", PLATE));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A308");
    }

    @Test
    void fewerThanThreeWheels_isA309() {
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>(fourWheels().subList(0, 2));

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A309");
    }

    @Test
    void tooManyParts_isA302() {
        // D05-R1: MAX_PARTS_PER_VEHICLE counts the chassis. Slots are reused deliberately here — the
        // point is the count, and A307 fires alongside it.
        List<AssemblyDef.PartPlacement> parts = new ArrayList<>();
        for (int i = 0; i < SimulationConstants.MAX_PARTS_PER_VEHICLE; i++) {
            parts.add(new AssemblyDef.PartPlacement(
                    "root/wheel_fl_" + i, "root", "wheel_fl", WHEEL, AssemblyDef.Overrides.NONE));
        }

        assertThat(codes(AssemblyValidator.validate(assemblyOf(parts), catalogue())))
                .contains("A302");
    }

    @Test
    void anExpectedMassThatDisagreesWithTheParts_isA310() {
        // D08-R10. `expected` is a checked assertion, not an input: it catches a part's mass changing
        // without its vehicles being re-checked.
        AssemblyDef assembly = new AssemblyDef(
                ASSEMBLY, "medium", CHASSIS, fourWheels(), new AssemblyDef.Expected(900f, 0f, new Vector3()));

        assertThat(codes(AssemblyValidator.validate(assembly, catalogue()))).contains("A310");
    }

    @Test
    void anExpectedComThatDisagreesWithTheParts_isA311() {
        // The four wheels are symmetric about the chassis origin except in Y, so the true COM is
        // slightly below it; claiming the origin is more than a centimetre out.
        AssemblyDef assembly = new AssemblyDef(
                ASSEMBLY,
                "medium",
                CHASSIS,
                fourWheels(),
                new AssemblyDef.Expected(1200f, 0f, new Vector3(0f, 2f, 0f)));

        assertThat(codes(AssemblyValidator.validate(assembly, catalogue()))).contains("A311");
    }

    @Test
    void theComputedAggregate_isTheMassWeightedMeanOfThePartPositions() {
        // D06-S5.7 step 1, computed before any entity exists — the pre-entity twin of the sum
        // MassPropertySystem does over a live vehicle.
        AssemblyLayout layout = AssemblyLayout.resolve(assemblyOf(fourWheels()), catalogue());

        assertThat(layout.totalMassKg()).isEqualTo(1000f + 4 * 50f);
        Vector3 com = layout.comLocal(new Vector3());
        assertThat(com.x).isZero();
        assertThat(com.z).isZero();
        // 200 kg of wheels 0.4 m below a 1000 kg chassis at the origin: -0.4 * 200 / 1200.
        assertThat(com.y).isEqualTo(-0.4f * 200f / 1200f, within(1e-4f));
    }
}
