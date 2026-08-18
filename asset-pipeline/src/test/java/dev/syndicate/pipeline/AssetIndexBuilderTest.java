/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.syndicate.model.ExitCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build-time asset gate of docs/08_asset_pipeline.md#D08-S5.2 and #D08-S5.4.
 *
 * <p>Two things are worth asserting about a validator: that it accepts what is correct, and that it
 * rejects each specific thing it exists to reject with the code it is supposed to use. The second
 * matters more — a gate that passes everything is indistinguishable from no gate, and only the code
 * makes a failure actionable.
 */
@Tag("unit")
class AssetIndexBuilderTest {

    @Test
    void theShippedAssetsProduceAnIndex(@TempDir Path temp) {
        AssetIndexBuilder builder = new AssetIndexBuilder();
        ObjectNode index = builder.build(Path.of("..", "assets"));

        assertThat(index.path("materials")).isNotEmpty();
        // Vehicles of twenty-odd real parts each, not a chassis and four wheels. The count
        // is asserted as a floor rather than a figure: re-cutting the art moves it, and a test
        // that pinned it would fail on every art change without saying anything about the index.
        assertThat(index.path("parts")).hasSizeGreaterThan(40);
        assertThat(index.path("vehicles")).hasSize(3);

        // Every part lives in exactly one of the two places D08-R14b allows, and *which* place says
        // what it is for (DEC-075). A part cut from a vehicle's own art is owned by that vehicle and
        // lives under it; a modular weapon is authored separately and lives in the shared library,
        // where anything with a compatible mount can fit it (D17-S1).
        List<String> shared = new ArrayList<>();
        index.path("parts").forEach(part -> {
            String partTypeId = part.path("partTypeId").asText();
            String owner = part.path("ownedBy").asText(null);
            if (owner == null) {
                assertThat(part.path("path").asText())
                        .as("%s is in the shared library", partTypeId)
                        .isEqualTo("parts/" + partTypeId);
                shared.add(partTypeId);
                return;
            }
            // Two owning buckets, both of them "the thing this part was cut from": a vehicle
            // (DEC-075) or a structure (D16-R19). Which one it is says what the part is for, and a
            // part in neither is a modular component in the shared library.
            assertThat(part.path("path").asText())
                    .as("%s lives under its owner", partTypeId)
                    .isIn("vehicles/" + owner + "/parts/" + partTypeId, "structures/" + owner + "/parts/" + partTypeId);
        });

        // The shared library is no longer empty: two modular weapons ship, each as a sub-part tree
        // rooted at its mount (D17-S5.8).
        assertThat(shared)
                .as("the shared library holds the modular weapons and nothing else")
                .isNotEmpty()
                .allMatch(id -> id.startsWith("weapon_"));
        assertThat(shared).contains("weapon_machinegun_01_mount", "weapon_cannon_01_mount");
        // Two arenas: the flat scrapyard and the generated desert. The pipeline validates a terrain
        // block without generating the field — a 601-square grid at index-build time would make a
        // content check as slow as a load (D16-S5.1).
        // Five structures: the rocket turret and the four props cut out of the city alley kit.
        assertThat(index.path("structures")).hasSize(5);
        assertThat(index.path("structures").get(0).path("structureId").asText()).isEqualTo("str_city_block_low_01");
        index.path("structures").forEach(structure -> assertThat(
                        structure.path("footprintRadiusM").asDouble())
                .as(
                        "%s has a footprint placement can space by",
                        structure.path("structureId").asText())
                .isGreaterThan(0d));

        assertThat(index.path("arenas")).hasSize(2);
        assertThat(index.path("arenas").get(0).path("arenaId").asText()).isEqualTo("arena_desert_01");
        assertThat(index.path("arenas").get(1).path("arenaId").asText()).isEqualTo("arena_scrapyard_01");

        // The vehicle summaries carry what the runtime and the balance check both need. Indexed by
        // id rather than by position: the vehicles are sorted, so adding the Kestrel between the
        // Eclipse and the Stampede moved the one this block is about (DEC-090).
        ObjectNode eclipse = vehicleById(index, "vehicle_eclipse_01");
        assertThat(eclipse.path("vehicleTypeId").asText()).isEqualTo("vehicle_eclipse_01");
        assertThat(eclipse.path("wheelCount").asInt()).isEqualTo(4);
        // Close to, not equal: this is the sum of thirty-odd authored masses, and the last binary
        // digit of that sum is not a fact about the vehicle.
        //
        // 1535.4 rather than the Eclipse's 1500 kg kerb mass, because it ships **armed**: a machine
        // gun on each flank — the left one the mirror of the right (D17-R26a) — weighs 35 kg between
        // them, and the index sums what is actually on the vehicle (D17-S1). The kerb figure is
        // asserted separately, against the vehicle's own parts, by VehicleProfileContentTest.
        assertThat(eclipse.path("totalMassKg").asDouble()).isCloseTo(1535.424, within(0.01));
        assertThat(eclipse.path("powerBudget").asDouble()).isCloseTo(131.467, within(0.01));

        // The shipped tree is clean. It was not before the preparation pipeline's output shipped:
        // every part directory named a mesh that did not exist, and the whole of A107 was expected.
        assertThat(builder.blockingFindings()).isEmpty();

        builder.write(index, temp.resolve("asset-index.json"));
        assertThat(temp.resolve("asset-index.json")).exists();
    }

    /** A310: an assembly whose declared mass does not match the sum of its parts is rejected. */
    @Test
    void aWrongDeclaredMassIsCaught(@TempDir Path temp) throws Exception {
        writeMinimalCatalogue(temp, 1500.0, "medium");
        Files.writeString(
                temp.resolve("vehicles/vehicle_test_01/assembly.json"), assembly("vehicle_test_01", "medium", 9999.0));

        AssetIndexBuilder builder = new AssetIndexBuilder();
        builder.build(temp);

        assertThat(builder.findings()).extracting(Finding::code).contains("A310");
    }

    /** A312: a vehicle outside its class's power budget target fails the balance invariant. */
    @Test
    void aVehicleOutsideItsClassBudgetIsCaught(@TempDir Path temp) throws Exception {
        writeMinimalCatalogue(temp, 1500.0, "medium");
        Files.writeString(
                temp.resolve("balance/classes.json"),
                """
                { "schemaVersion": "1.0.0",
                  "classes": [ { "classId": "medium", "powerBudgetTarget": 500.0 } ] }
                """);

        AssetIndexBuilder builder = new AssetIndexBuilder();
        builder.build(temp);

        assertThat(builder.findings()).extracting(Finding::code).contains("A312");
    }

    /**
     * A510: a manifest's transform must be one the part's destruction class receives (D15-S5.7).
     *
     * <p>The gate half of the rule the Blender tools enforce at authoring time. Both exist because
     * nothing at runtime checks: a part dents because its mesh has shape keys and shatters because
     * it declares a manifest, so a manifest that arrived by hand or from a tool version predating
     * the FRACTURE/DEFORM split would otherwise ship (DISC-068).
     */
    @Test
    void aFractureManifestOnAPartThatMustNotShatterIsCaught(@TempDir Path temp) throws Exception {
        writeMinimalCatalogue(temp, 1500.0, "medium");
        // A PANEL defaults to SHEET_METAL, which D15-S5.7 dents rather than shatters.
        writeFracturingPart(temp, "panel_test_01", "PANEL", "FRACTURE", "SHEET_METAL");

        AssetIndexBuilder builder = new AssetIndexBuilder();
        builder.build(temp);

        assertThat(builder.findings()).extracting(Finding::code).contains("A510");
    }

    @Test
    void aManifestPredatingTheTransformSplitIsCaught(@TempDir Path temp) throws Exception {
        writeMinimalCatalogue(temp, 1500.0, "medium");
        // No "transform" field at all: every manifest written before the split looks like this,
        // and it must be regenerated rather than trusted.
        writeFracturingPart(temp, "glass_test_01", "DECORATIVE", null, "GLASS");

        AssetIndexBuilder builder = new AssetIndexBuilder();
        builder.build(temp);

        assertThat(builder.findings())
                .filteredOn(finding -> "A510".equals(finding.code()))
                .isNotEmpty();
    }

    @Test
    void aGlassPartWithAProperlyDeclaredManifestPasses(@TempDir Path temp) throws Exception {
        writeMinimalCatalogue(temp, 1500.0, "medium");
        writeFracturingPart(temp, "glass_test_01", "DECORATIVE", "FRACTURE", "GLASS");

        AssetIndexBuilder builder = new AssetIndexBuilder();
        builder.build(temp);

        assertThat(builder.findings()).extracting(Finding::code).doesNotContain("A510");
    }

    /** A part declaring a fracture manifest, with the manifest's own header under test. */
    private static void writeFracturingPart(
            Path root, String partTypeId, String category, String transform, String destructionClass) throws Exception {

        Path directory = root.resolve("parts").resolve(partTypeId);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("part.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "partTypeId": "%s",
                  "category": "%s",
                  "destructionClass": "%s",
                  "massKg": 20.0,
                  "maxHp": 100.0,
                  "armorValue": 0.0,
                  "materialId": "steel",
                  "powerCost": 1.0,
                  "breakImpulseN": 500.0,
                  "slots": [],
                  "assets": { "fractureManifest": "fracture_manifest.json" }
                }
                """
                        .formatted(partTypeId, category, destructionClass));
        String header = transform == null ? "" : "\"transform\": \"" + transform + "\",";
        Files.writeString(
                directory.resolve("fracture_manifest.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "toolVersion": "0.1.0",
                  %s
                  "destructionClass": "%s",
                  "partTypeId": "%s",
                  "partMassKg": 20.0,
                  "shardCount": 2,
                  "shards": [ { "id": "a", "massKg": 10.0 }, { "id": "b", "massKg": 10.0 } ]
                }
                """
                        .formatted(header, destructionClass, partTypeId));
    }

    /** A103: a schema major this build cannot read is FATAL, whatever else the file says. */
    @Test
    void anUnreadableSchemaVersionIsFatal(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("materials"));
        Files.writeString(
                temp.resolve("materials/materials.json"), "{ \"schemaVersion\": \"9.0.0\", \"materials\": [] }");

        AssetIndexBuilder builder = new AssetIndexBuilder();
        builder.build(temp);

        assertThat(builder.findings()).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("A103");
            assertThat(finding.severity()).isEqualTo(Finding.Severity.FATAL);
        });
    }

    /** A101: a file that is not JSON is FATAL and stops the index being written at all. */
    @Test
    void malformedJsonFailsTheRun(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("materials"));
        Files.writeString(temp.resolve("materials/materials.json"), "{ this is not json");

        assertThat(PipelineMain.run(new String[] {"--assets", temp.toString()})).isEqualTo(ExitCode.ASSETS_INVALID);
        assertThat(temp.resolve("asset-index.json")).doesNotExist();
    }

    /** A missing asset root is ASSETS_NOT_FOUND (66), not a stack trace (D03-S4.4). */
    @Test
    void aMissingAssetRootIsReportedNotThrown(@TempDir Path temp) {
        assertThat(PipelineMain.run(
                        new String[] {"--assets", temp.resolve("nope").toString()}))
                .isEqualTo(ExitCode.ASSETS_NOT_FOUND);
    }

    /** An unknown flag is USAGE (64): a typo is fatal, never a warning (D03-R6). */
    @Test
    void anUnknownFlagIsUsage() {
        assertThat(PipelineMain.run(new String[] {"--nonsense"})).isEqualTo(ExitCode.USAGE);
    }

    /** Strict mode is what makes the gate a gate: the same content, two verdicts. */
    @Test
    void strictModeFailsOnErrorsThatLenientModeTolerates(@TempDir Path temp) throws Exception {
        writeMinimalCatalogue(temp, 1500.0, "medium");
        Files.writeString(
                temp.resolve("vehicles/vehicle_test_01/assembly.json"), assembly("vehicle_test_01", "medium", 9999.0));

        assertThat(PipelineMain.run(new String[] {"--assets", temp.toString()})).isEqualTo(ExitCode.OK);
        assertThat(PipelineMain.run(new String[] {"--assets", temp.toString(), "--strict"}))
                .isEqualTo(ExitCode.ASSETS_INVALID);
    }

    // ---- Fixtures --------------------------------------------------------------------

    /** A catalogue with one material, one chassis, three wheels and one assembly that resolves. */
    private static void writeMinimalCatalogue(Path root, double expectedMassKg, String vehicleClass) throws Exception {
        Files.createDirectories(root.resolve("materials"));
        Files.createDirectories(root.resolve("balance"));
        Files.writeString(
                root.resolve("materials/materials.json"),
                """
                { "schemaVersion": "1.0.0",
                  "materials": [ { "materialId": "steel", "densityKgPerM3": 7850.0 } ] }
                """);
        Files.writeString(
                root.resolve("balance/classes.json"),
                """
                { "schemaVersion": "1.0.0",
                  "classes": [ { "classId": "medium", "powerBudgetTarget": 60.0 } ] }
                """);

        writePart(root, "chassis_test_01", "CHASSIS", 1200.0, 40.0, "wheel_a", "wheel_b", "wheel_c");
        writePart(root, "wheel_test_01", "WHEEL", 100.0, 6.66);

        Files.createDirectories(root.resolve("vehicles/vehicle_test_01"));
        Files.writeString(
                root.resolve("vehicles/vehicle_test_01/assembly.json"),
                assembly("vehicle_test_01", vehicleClass, expectedMassKg));
    }

    private static void writePart(
            Path root, String partTypeId, String category, double massKg, double powerCost, String... slotIds)
            throws Exception {

        Path directory = root.resolve("parts").resolve(partTypeId);
        Files.createDirectories(directory);
        StringBuilder slots = new StringBuilder();
        for (int i = 0; i < slotIds.length; i++) {
            if (i > 0) {
                slots.append(",");
            }
            slots.append("{ \"slotId\": \"")
                    .append(slotIds[i])
                    .append("\", \"slotType\": \"WHEEL\", \"maxMassKg\": 200.0, \"covers\": [] }");
        }
        Files.writeString(
                directory.resolve("part.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "partTypeId": "%s",
                  "category": "%s",
                  "massKg": %s,
                  "maxHp": 1000.0,
                  "armorValue": 10.0,
                  "materialId": "steel",
                  "powerCost": %s,
                  "breakImpulseN": 5000.0,
                  "slots": [ %s ],
                  "assets": {}
                }
                """
                        .formatted(partTypeId, category, massKg, powerCost, slots));
    }

    private static String assembly(String vehicleTypeId, String vehicleClass, double expectedMassKg) {
        return """
                {
                  "schemaVersion": "1.0.0",
                  "vehicleTypeId": "%s",
                  "vehicleClass": "%s",
                  "chassis": "chassis_test_01",
                  "parts": [
                    { "slotPath": "root/wheel_a", "parentSlotPath": "root", "parentSlotId": "wheel_a",
                      "partTypeId": "wheel_test_01" },
                    { "slotPath": "root/wheel_b", "parentSlotPath": "root", "parentSlotId": "wheel_b",
                      "partTypeId": "wheel_test_01" },
                    { "slotPath": "root/wheel_c", "parentSlotPath": "root", "parentSlotId": "wheel_c",
                      "partTypeId": "wheel_test_01" }
                  ],
                  "expected": { "totalMassKg": %s }
                }
                """
                .formatted(vehicleTypeId, vehicleClass, expectedMassKg);
    }

    /** One vehicle summary out of the index by id, so a new vehicle cannot move another's row. */
    private static ObjectNode vehicleById(ObjectNode index, String vehicleTypeId) {
        for (com.fasterxml.jackson.databind.JsonNode vehicle : index.path("vehicles")) {
            if (vehicleTypeId.equals(vehicle.path("vehicleTypeId").asText())) {
                return (ObjectNode) vehicle;
            }
        }
        throw new AssertionError("no vehicle " + vehicleTypeId + " in the index");
    }
}
