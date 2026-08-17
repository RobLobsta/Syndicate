/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.AssetId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading {@code fracture_manifest.json} into the index (docs/08_asset_pipeline.md#D08-S5.3 step 2,
 * docs/09_blender_destruction_tool.md#D09-S4.4).
 *
 * <p>Nothing had ever put a manifest in the index, so {@code FractureSystem}'s lookup always missed
 * and every glass part in every match was destroyed without shards. These are the cases that path
 * has to get right before the authored destruction is worth anything: the geometry has to land in
 * the shard's own space, and a manifest that cannot be trusted has to leave the part detaching
 * whole rather than half-loaded.
 */
@Tag("unit")
class FractureManifestLoadingTest {

    private static final AssetId PART = AssetId.of("panel_test_01");

    /** Where the one shard of these fixtures sits within its part. */
    private static final Vector3 SHARD_CENTRE = new Vector3(0.5f, 0f, 0f);

    private static final float SHARD_HALF_EXTENT = 0.1f;

    @TempDir
    Path root;

    // ---- Fixtures --------------------------------------------------------------------

    /** A part that names a manifest, with no material so the table is not involved. */
    private Path writePart() throws IOException {
        Path directory = Files.createDirectories(root.resolve(PART.value()));
        Files.writeString(
                directory.resolve("part.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "partTypeId": "panel_test_01",
                  "displayName": "Test Plate",
                  "category": "PANEL",
                  "massKg": 20.0,
                  "maxHp": 100.0,
                  "armorValue": 10.0,
                  "slotTypeRequired": "PANEL",
                  "sizeClass": "MEDIUM",
                  "powerCost": 1.0,
                  "breakImpulseN": 1000.0,
                  "stats": {},
                  "slots": [],
                  "assets": {
                    "visualMesh": "mesh.glb",
                    "collisionSource": "mesh.glb",
                    "fractureManifest": "fracture_manifest.json",
                    "shardMesh": "shards.glb"
                  }
                }
                """);
        return directory;
    }

    /**
     * A two-shard manifest of 10 kg each, both boxes, the second a translated copy of the first.
     *
     * <p>{@code shardMassKg} is a parameter so a test can break G7 without editing the rest.
     */
    private void writeManifest(Path directory, float partMassKg, float shardMassKg) throws IOException {
        Files.writeString(
                directory.resolve("fracture_manifest.json"),
                """
                {
                  "schemaVersion": "1.0.0",
                  "toolVersion": "0.1.0",
                  "partTypeId": "panel_test_01",
                  "partMassKg": %s,
                  "shardCount": 2,
                  "shards": [
                    {
                      "id": "panel_test_01_shard_001", "name": "shard_001", "index": 1,
                      "massKg": %s,
                      "centroid": { "x": -0.5, "y": 0.0, "z": 0.0 },
                      "localTransform": {
                        "position": { "x": -0.5, "y": 0.0, "z": 0.0 },
                        "rotation": { "w": 1.0, "x": 0.0, "y": 0.0, "z": 0.0 }
                      },
                      "aabbMin": { "x": -0.6, "y": -0.1, "z": -0.1 },
                      "aabbMax": { "x": -0.4, "y":  0.1, "z":  0.1 }
                    },
                    {
                      "id": "panel_test_01_shard_000", "name": "shard_000", "index": 0,
                      "massKg": %s,
                      "centroid": { "x": 0.5, "y": 0.0, "z": 0.0 },
                      "localTransform": {
                        "position": { "x": 0.5, "y": 0.0, "z": 0.0 },
                        "rotation": { "w": 1.0, "x": 0.0, "y": 0.0, "z": 0.0 }
                      },
                      "aabbMin": { "x": 0.4, "y": -0.1, "z": -0.1 },
                      "aabbMax": { "x": 0.6, "y":  0.1, "z":  0.1 }
                    }
                  ]
                }
                """
                        .formatted(partMassKg, shardMassKg, shardMassKg));
    }

    /** A box of {@link #SHARD_HALF_EXTENT} about {@code centre}, in the part's space. */
    private static MeshData box(Vector3 centre) {
        float h = SHARD_HALF_EXTENT;
        float[] positions = new float[8 * 3];
        int cursor = 0;
        for (int corner = 0; corner < 8; corner++) {
            positions[cursor++] = centre.x + ((corner & 1) == 0 ? -h : h);
            positions[cursor++] = centre.y + ((corner & 2) == 0 ? -h : h);
            positions[cursor++] = centre.z + ((corner & 4) == 0 ? -h : h);
        }
        return new MeshData(positions);
    }

    /** The two shard nodes the manifest above names, in the part's own space. */
    private static AssetLoader.ShardMeshSource shardBoxes(String... nodeNames) {
        Map<String, MeshData> meshes = new LinkedHashMap<>();
        for (String name : nodeNames) {
            meshes.put(name, box("shard_000".equals(name) ? SHARD_CENTRE : new Vector3(SHARD_CENTRE).scl(-1f)));
        }
        return (partTypeId, ref, directory) -> meshes;
    }

    private static final AssetLoader.CollisionMeshSource PART_BOX = (partTypeId, ref, directory) -> box(Vector3.Zero);

    private static AssetLoader loaderWith(AssetLoader.ShardMeshSource shards) {
        return new AssetLoader(PART_BOX, shards);
    }

    // ---- The path that had never run --------------------------------------------------

    @Test
    void aDeclaredManifest_reachesTheIndexUnderThePartsOwnId() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        AssetLoader loader = loaderWith(shardBoxes("shard_000", "shard_001"));
        InMemoryAssetIndex index = new InMemoryAssetIndex();

        loader.loadPart(directory, index);

        // D09-S4.4: a part and its shards are paired by the part's own id, with no second
        // identifier to keep in sync. This lookup is the one FractureSystem performs.
        assertThat(loader.blockingIssues()).isEmpty();
        assertThat(index.partType(PART).fractureManifestRef()).isEqualTo(PART);
        FractureManifest manifest = index.fractureManifest(PART);
        assertThat(manifest).isNotNull();
        assertThat(manifest.shardCount()).isEqualTo(2);
        assertThat(manifest.declaredShardMassKg()).isEqualTo(20f);
    }

    @Test
    void shardsAreSortedByIdRegardlessOfTheOrderTheyWereWrittenIn() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        loaderWith(shardBoxes("shard_000", "shard_001")).loadPart(directory, index);

        // The file lists shard_001 first. G3: FractureSystem draws from FRACTURE_SCATTER once per
        // shard in this order, so two peers that loaded the same file must iterate it the same way.
        assertThat(index.fractureManifest(PART).shards())
                .extracting(ShardDefinition::shardId)
                .containsExactly("panel_test_01_shard_000", "panel_test_01_shard_001");
    }

    @Test
    void shardGeometryIsMovedOntoTheShardsOwnOrigin() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        loaderWith(shardBoxes("shard_000", "shard_001")).loadPart(directory, index);

        ShardDefinition shard = index.fractureManifest(PART).shards().get(0);
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        shard.hullMesh().bounds(min, max);

        // The tool exports every shard in the *part's* frame, and D07-S5.6 then spawns each one at
        // the part's world transform composed with its localTransform. Handing Bullet the part-space
        // vertices as well would apply the offset twice — the failure this assertion is here for.
        assertThat(min.epsilonEquals(-SHARD_HALF_EXTENT, -SHARD_HALF_EXTENT, -SHARD_HALF_EXTENT, 1e-5f))
                .as("hull min %s is centred on the shard's own origin", min)
                .isTrue();
        assertThat(max.epsilonEquals(SHARD_HALF_EXTENT, SHARD_HALF_EXTENT, SHARD_HALF_EXTENT, 1e-5f))
                .isTrue();

        // And composing the two puts it back where the manifest says it was.
        Vector3 placed = new Vector3(min).mul(shard.localTransform(new Matrix4()));
        assertThat(placed.x).isCloseTo(0.4f, within(1e-5f));
    }

    @Test
    void aShardKeepsTheNodeNameItsGeometryCameFrom() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        loaderWith(shardBoxes("shard_000", "shard_001")).loadPart(directory, index);

        // A501 pairs a manifest entry with shards.glb by exact name, and the client resolves the
        // drawable model by the same name the loader resolved the hull by.
        assertThat(index.fractureManifest(PART).shards())
                .extracting(ShardDefinition::meshNodeName)
                .containsExactly("shard_000", "shard_001");
    }

    // ---- Refusals ---------------------------------------------------------------------

    @Test
    void aShardWithNoNodeInTheGlb_abandonsTheWholeManifest() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        AssetLoader loader = loaderWith(shardBoxes("shard_000"));
        InMemoryAssetIndex index = new InMemoryAssetIndex();

        loader.loadPart(directory, index);

        // Shipping the shards that did load would lose 10 kg of the part's mass at the moment a
        // player is watching, which is exactly what G7 refuses. The part detaches whole instead.
        assertThat(loader.issues()).anyMatch(issue -> "A501".equals(issue.code()));
        assertThat(index.fractureManifest(PART)).isNull();
        assertThat(index.partType(PART).fractureManifestRef()).isNull();
    }

    @Test
    void shardsThatDoNotSumToThePartsMass_areRefused() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 7f);
        AssetLoader loader = loaderWith(shardBoxes("shard_000", "shard_001"));
        InMemoryAssetIndex index = new InMemoryAssetIndex();

        loader.loadPart(directory, index);

        assertThat(loader.issues()).anyMatch(issue -> "A504".equals(issue.code()));
        assertThat(index.fractureManifest(PART)).isNull();
    }

    @Test
    void geometryThatDisagreesWithTheManifestsOwnBounds_isRefused() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        // Both nodes present, both boxes — but already moved onto their own origins, which is the
        // shape of a future exporter change rather than of a corrupt file.
        AssetLoader loader = loaderWith(
                (partTypeId, ref, dir) -> Map.of("shard_000", box(Vector3.Zero), "shard_001", box(Vector3.Zero)));
        InMemoryAssetIndex index = new InMemoryAssetIndex();

        loader.loadPart(directory, index);

        assertThat(loader.issues())
                .filteredOn(issue -> "A501".equals(issue.code()))
                .isNotEmpty();
        assertThat(index.fractureManifest(PART)).isNull();
    }

    @Test
    void aDeclaredManifestThatIsNotThere_isReportedAndThePartStillLoads() throws IOException {
        Path directory = writePart();
        AssetLoader loader = loaderWith(shardBoxes("shard_000", "shard_001"));
        InMemoryAssetIndex index = new InMemoryAssetIndex();

        loader.loadPart(directory, index);

        assertThat(loader.issues()).anyMatch(issue -> "A107".equals(issue.code()));
        // G18: the part is still usable, it just detaches whole (D07-E5).
        assertThat(index.partType(PART)).isNotNull();
        assertThat(index.partType(PART).fractureManifestRef()).isNull();
    }

    @Test
    void aManifestWithNoToolVersion_isAFindingButStillLoads() throws IOException {
        Path directory = writePart();
        writeManifest(directory, 20f, 10f);
        Files.writeString(
                directory.resolve("fracture_manifest.json"),
                Files.readString(directory.resolve("fracture_manifest.json"))
                        .replace("\"toolVersion\": \"0.1.0\",", ""));
        AssetLoader loader = loaderWith(shardBoxes("shard_000", "shard_001"));
        InMemoryAssetIndex index = new InMemoryAssetIndex();

        loader.loadPart(directory, index);

        assertThat(loader.issues()).anyMatch(issue -> "A506".equals(issue.code()));
        assertThat(index.fractureManifest(PART)).isNotNull();
    }
}
