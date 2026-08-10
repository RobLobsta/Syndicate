/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.PartCategory;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading an asset tree off disk (docs/08_asset_pipeline.md#D08-S5.3, #D08-S5.4).
 */
@Tag("unit")
class AssetLoaderTest {

    private static final AssetId CHASSIS = AssetId.of("chassis_medium_01");
    private static final AssetId WHEEL = AssetId.of("wheel_road_01");
    private static final AssetId PLATE = AssetId.of("armor_plate_medium_01");
    private static final AssetId RAIDER = AssetId.of("vehicle_medium_raider_01");

    /**
     * A box in place of the real collision mesh.
     *
     * <p>The hull source lives in {@code mesh.glb} and {@code game-core} has no glTF reader (see
     * {@link AssetLoader}); this is the seam a headless one plugs into, and standing a box in it is
     * exactly what that seam is for.
     */
    private static final AssetLoader.CollisionMeshSource BOXES =
            (partTypeId, ref, directory) -> new MeshData(new float[] {
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f
            });

    private AssetLoader loader;
    private InMemoryAssetIndex index;

    /** The checked-in fixture tree, which mirrors the D08-S4.6 layout. */
    private static Path assetRoot() throws URISyntaxException {
        return Path.of(AssetLoaderTest.class.getResource("/assets").toURI());
    }

    @BeforeEach
    void setUp() throws URISyntaxException {
        loader = new AssetLoader(BOXES);
        index = loader.loadFrom(assetRoot());
    }

    @Test
    void aCleanAssetTree_loadsWithNoBlockingFindings() {
        // D08-S5.4. WARNs never stop anything; a blocking finding is what a strict load refuses on.
        assertThat(loader.blockingIssues()).isEmpty();
    }

    @Test
    void materialsAreLoadedFirst_becauseEverythingElseReferencesThem() {
        // D08-S5.3 step 1. A part's materialId is checked against the table as the part loads, which
        // only works because materials are already in the index.
        MaterialDef steel = index.material(AssetId.of("steel_hardened"));

        assertThat(steel).isNotNull();
        assertThat(steel.densityKgPerM3()).isEqualTo(7850f);
        assertThat(steel.resistanceTo(DamageType.KINETIC)).isEqualTo(0.85f);
        // An unlisted damage type is unmodified, never immune.
        assertThat(new MaterialDef(AssetId.of("steel"), 7850f, null, 0.5f, null).resistanceTo(DamageType.ENERGY))
                .isEqualTo(MaterialDef.NEUTRAL_RESISTANCE);
    }

    @Test
    void aPartsAuthoredFields_surviveTheRoundTrip() {
        PartType plate = index.partType(PLATE);

        assertThat(plate).isNotNull();
        assertThat(plate.category()).isEqualTo(PartCategory.ARMOR);
        assertThat(plate.massKg()).isEqualTo(160f);
        assertThat(plate.maxHp()).isEqualTo(900f);
        assertThat(plate.armorValue()).isEqualTo(45f);
        assertThat(plate.breakImpulseN()).isEqualTo(4000f);
        assertThat(plate.slotTypeRequired()).isEqualTo(SlotType.ARMOR_PANEL);
        // D07-S5.7 T1: this one hangs by a thread before it falls.
        assertThat(plate.hangsBeforeFalling()).isTrue();
        assertThat(plate.materialId()).isEqualTo(AssetId.of("steel_hardened"));
    }

    @Test
    void statKeys_areMatchedOnLettersAndDigitsAlone() {
        // part.json authors camelCase; StatBlock names the enum convention. Normalising both means
        // adding a stat touches one place rather than a translation table as well.
        PartType wheel = index.partType(WHEEL);

        assertThat(wheel.stats().add(StatBlock.Stat.FRICTION_SLIP)).isEqualTo(10.5f);
        assertThat(wheel.stats().add(StatBlock.Stat.SUSPENSION_STIFFNESS)).isEqualTo(22f);
        // Unset means identity, never zero (D05-R15): a stat declaring only `add` keeps mul at 1, or
        // the part would wipe out every other part's contribution.
        assertThat(wheel.stats().mul(StatBlock.Stat.FRICTION_SLIP)).isEqualTo(1f);
        assertThat(wheel.stats().add(StatBlock.Stat.ENGINE_FORCE_N)).isZero();
    }

    @Test
    void slotsAreLoadedWithTheirOffsetsAndCoverage() {
        // D05-S4.3. A slot is authored on the parent, so re-authoring the chassis moves that
        // hardpoint on every vehicle using it.
        PartType chassis = index.partType(CHASSIS);
        SlotDefinition armour = chassis.slot("armor_front");

        assertThat(chassis.slots().keySet())
                .containsExactly("armor_front", "wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr");
        assertThat(armour.slotType()).isEqualTo(SlotType.ARMOR_PANEL);
        assertThat(armour.maxMassKg()).isEqualTo(400f);
        assertThat(armour.localTransform().position.epsilonEquals(0f, 0.1f, 1.8f, 1e-5f))
                .isTrue();
        assertThat(armour.covers()).containsExactly("wheel_fl", "wheel_fr");
    }

    @Test
    void anAssemblyIsLoadedAndValidatedAgainstThePartsFromTheSamePass() {
        // D08-S5.3 step 3. A vehicle referencing a part that failed to load is reported as the
        // assembly problem it is, rather than surfacing later as a vehicle with a hole in it.
        AssemblyDef raider = index.assembly(RAIDER);

        assertThat(raider).isNotNull();
        assertThat(raider.chassisPartTypeId()).isEqualTo(CHASSIS);
        assertThat(raider.partCount()).isEqualTo(6);
        assertThat(raider.parts())
                .extracting(AssemblyDef.PartPlacement::slotPath)
                .containsExactly(
                        "root/armor_front", "root/wheel_fl", "root/wheel_fr", "root/wheel_rl", "root/wheel_rr");
        assertThat(AssemblyValidator.validate(raider, index)).isEmpty();
    }

    @Test
    void perInstanceOverrides_areTypedRatherThanAFreeFormMap() {
        // A map would let a typo — isSteerable — pass validation and produce a vehicle that cannot
        // turn, which is a bug nobody would look for in an asset file.
        AssemblyDef raider = index.assembly(RAIDER);

        assertThat(raider.placementAt("root/wheel_fl").overrides().isSteering()).isTrue();
        assertThat(raider.placementAt("root/wheel_fl").overrides().isDriven()).isFalse();
        assertThat(raider.placementAt("root/wheel_rr").overrides().isDriven()).isTrue();
        // Absent means "the part type decides", which is not the same as false.
        assertThat(raider.placementAt("root/armor_front").overrides().isSteering())
                .isNull();
    }

    @Test
    void theExpectedBlock_matchesWhatThePartsActuallyAddUpTo() {
        // D08-R10. `expected` is the check that catches content drift: a part's mass changing
        // without its vehicles being re-checked.
        AssemblyLayout layout = AssemblyLayout.resolve(index.assembly(RAIDER), index);

        assertThat(layout.totalMassKg()).isEqualTo(1340f, within(0.01f));
        assertThat(layout.powerBudget()).isEqualTo(76.5f, within(0.01f));
        Vector3 com = layout.comLocal(new Vector3());
        assertThat(com.dst(index.assembly(RAIDER).expected().comLocal())).isLessThan(AssemblyValidator.COM_OFFSET_M);
    }

    @Test
    void aMalformedFile_isReportedRatherThanThrown(@TempDir Path root) throws IOException {
        // D08-S5.4's report lists every finding before anything decides what to do about them; a
        // loader that threw on the first bad file would make fixing a content directory a sequence
        // of edit-run cycles.
        Path part = root.resolve("parts").resolve("broken_part_01");
        Files.createDirectories(part);
        Files.writeString(part.resolve("part.json"), "{ this is not json");

        AssetLoader broken = new AssetLoader(BOXES);
        InMemoryAssetIndex empty = broken.loadFrom(root);

        assertThat(empty.partTypes()).isEmpty();
        assertThat(broken.issues()).extracting(ValidationIssue::code).contains("A101");
    }

    @Test
    void aSchemaMajorTheLoaderDoesNotRead_isA103(@TempDir Path root) throws IOException {
        // D08-R6. A major bump means the file means something different, so guessing is worse than
        // refusing.
        Path part = root.resolve("parts").resolve("future_part_01");
        Files.createDirectories(part);
        Files.writeString(
                part.resolve("part.json"),
                "{\"schemaVersion\":\"2.0.0\",\"partTypeId\":\"future_part_01\",\"category\":\"ARMOR\"}");

        AssetLoader future = new AssetLoader(BOXES);
        future.loadFrom(root);

        assertThat(future.issues()).extracting(ValidationIssue::code).contains("A103");
    }

    @Test
    void aPartWhoseIdDoesNotMatchItsDirectory_isA105(@TempDir Path root) throws IOException {
        Path part = root.resolve("parts").resolve("named_one_thing");
        Files.createDirectories(part);
        Files.writeString(
                part.resolve("part.json"),
                "{\"schemaVersion\":\"1.0.0\",\"partTypeId\":\"named_another\",\"category\":\"ARMOR\","
                        + "\"massKg\":10,\"maxHp\":10,\"breakImpulseN\":10}");

        AssetLoader mismatched = new AssetLoader(BOXES);
        mismatched.loadFrom(root);

        assertThat(mismatched.issues()).extracting(ValidationIssue::code).contains("A105");
    }

    @Test
    void aPartWithNoMassNoHitPointsAndNoBreakThreshold_reportsAllThree() {
        // A201, A204, A214 in one pass. A zero break threshold is the subtle one: the part detaches
        // on its first contact, which looks like a physics bug rather than a content one.
        Path root;
        try {
            root = Files.createTempDirectory("syndicate-assets");
            Path part = root.resolve("parts").resolve("empty_part_01");
            Files.createDirectories(part);
            Files.writeString(
                    part.resolve("part.json"),
                    "{\"schemaVersion\":\"1.0.0\",\"partTypeId\":\"empty_part_01\",\"category\":\"ARMOR\"}");
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        AssetLoader sparse = new AssetLoader(BOXES);
        sparse.loadFrom(root);

        assertThat(sparse.issues()).extracting(ValidationIssue::code).contains("A201", "A204", "A214", "A213");
    }
}
