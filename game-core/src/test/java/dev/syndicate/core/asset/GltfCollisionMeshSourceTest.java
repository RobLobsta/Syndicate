/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static dev.syndicate.core.asset.GltfTestDocuments.triangleDocument;
import static dev.syndicate.core.asset.GltfTestDocuments.writeGltf;
import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.AssetId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolving {@code assets.collisionSource} to geometry (docs/08_asset_pipeline.md#D08-S5.3 step 2).
 *
 * <p>This is the seam DEV-010 opened and this class closes: what the loader passes in is the raw
 * {@code mesh.glb#node=<name>} string from {@code part.json}, and what it needs back is a point set
 * or a null it can report as A503.
 */
@Tag("unit")
class GltfCollisionMeshSourceTest {

    private static final AssetId PART = AssetId.of("chassis_medium_01");

    private final GltfCollisionMeshSource source = new GltfCollisionMeshSource();

    /** A box, because a hull needs a volume and a single triangle has none. */
    private static Path writeBox(Path directory, String fileName, String nodes) throws IOException {
        return writeGltf(directory, fileName, boxDocument(nodes));
    }

    private static String boxDocument(String nodesAndScenes) {
        byte[] positions = GltfTestDocuments.floats(
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f,
                0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
        byte[] indices = GltfTestDocuments.shorts(0, 1, 2, 1, 3, 2, 4, 6, 5, 5, 6, 7);
        return """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 96 },
                    { "buffer": 0, "byteOffset": 96, "byteLength": 24 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 8,  "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 12, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                .formatted(
                        positions.length + indices.length,
                        GltfTestDocuments.dataUri(GltfTestDocuments.concat(positions, indices)),
                        nodesAndScenes);
    }

    @Test
    void aNodeFragment_selectsThatNodeAndNothingElse(@TempDir Path directory) throws IOException {
        writeBox(
                directory,
                "mesh.glb",
                """
                "nodes": [
                  { "name": "chassis_medium_01",     "mesh": 0 },
                  { "name": "chassis_medium_01_col", "mesh": 0, "translation": [10, 0, 0] }
                ],
                "scenes": [ { "nodes": [0, 1] } ],
                "scene": 0
                """);

        MeshData mesh = source.meshFor(PART, "mesh.glb#node=chassis_medium_01_col", directory);

        assertThat(mesh).isNotNull();
        assertThat(mesh.vertexCount()).isEqualTo(8);
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        mesh.bounds(min, max);
        // The collision node, not the visual one: translated ten metres along X.
        assertThat(min.x).isEqualTo(9.5f);
        assertThat(max.x).isEqualTo(10.5f);
    }

    @Test
    void noFragment_usesTheWholeFile(@TempDir Path directory) throws IOException {
        writeBox(
                directory,
                "mesh.glb",
                """
                "nodes": [ { "name": "chassis_medium_01", "mesh": 0 } ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        assertThat(source.meshFor(PART, "mesh.glb", directory).vertexCount()).isEqualTo(8);
    }

    @Test
    void noReferenceAtAll_fallsBackToMeshGlb(@TempDir Path directory) throws IOException {
        writeBox(
                directory,
                "mesh.glb",
                """
                "nodes": [ { "name": "chassis_medium_01", "mesh": 0 } ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        assertThat(source.meshFor(PART, null, directory)).isNotNull();
    }

    @Test
    void aNodeNameTheFileDoesNotHave_fallsBackToTheVisualMesh(@TempDir Path directory) throws IOException {
        // D08-R3 makes `<partTypeId>_col` optional and says the visual mesh is the hull source when
        // it is absent — so a name that matches nothing is a documented fallback, not a failure.
        writeBox(
                directory,
                "mesh.glb",
                """
                "nodes": [ { "name": "chassis_medium_01", "mesh": 0 } ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        assertThat(source.meshFor(PART, "mesh.glb#node=chassis_medium_01_col", directory)
                        .vertexCount())
                .isEqualTo(8);
    }

    @Test
    void aPartWithNoMeshFile_yieldsNullSoTheLoaderCanReportA503(@TempDir Path directory) {
        assertThat(source.meshFor(PART, "mesh.glb", directory)).isNull();
    }

    @Test
    void aMeshFileThatIsNotGltf_yieldsNullRatherThanThrowing(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("mesh.glb"), "this is not a glTF document");

        assertThat(source.meshFor(PART, "mesh.glb", directory)).isNull();
    }

    @Test
    void geometryWithTooFewVerticesToEncloseAVolume_yieldsNull(@TempDir Path directory) throws IOException {
        writeGltf(
                directory,
                "mesh.glb",
                triangleDocument(
                        """
                        "nodes": [ { "name": "tri", "mesh": 0 } ],
                        "scenes": [ { "nodes": [0] } ],
                        "scene": 0
                        """));

        assertThat(source.meshFor(PART, "mesh.glb", directory)).isNull();
    }

    @Test
    void aReferenceThatEscapesThePartDirectory_yieldsNull(@TempDir Path directory) throws IOException {
        Path parts = Files.createDirectories(directory.resolve("parts").resolve("chassis_medium_01"));
        writeBox(
                directory,
                "elsewhere.glb",
                """
                "nodes": [ { "name": "chassis_medium_01", "mesh": 0 } ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        assertThat(source.meshFor(PART, "../../elsewhere.glb", parts)).isNull();
    }
}
