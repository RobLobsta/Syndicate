/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import static dev.syndicate.core.asset.GltfTestDocuments.concat;
import static dev.syndicate.core.asset.GltfTestDocuments.dataUri;
import static dev.syndicate.core.asset.GltfTestDocuments.floats;
import static dev.syndicate.core.asset.GltfTestDocuments.shorts;
import static dev.syndicate.core.asset.GltfTestDocuments.triangleDocument;
import static dev.syndicate.core.asset.GltfTestDocuments.writeGlb;
import static dev.syndicate.core.asset.GltfTestDocuments.writeGltf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading glTF geometry headlessly (docs/08_asset_pipeline.md#D08-S4.5, #D08-S5.3).
 *
 * <p>Covers the cases the project's own exporter never produces and real art does: node transforms
 * that carry an axis conversion or a unit scale, external buffers, interleaved attributes, strips,
 * sparse accessors and mirrored nodes. T-D08-3 (a part whose mesh cannot be read) is
 * {@link GltfCollisionMeshSourceTest}'s.
 */
@Tag("unit")
class GltfReaderTest {

    private static final String SCENE_ONE_NODE =
            """
            "nodes": [ { "name": "tri", "mesh": 0 } ],
            "scenes": [ { "nodes": [0] } ],
            "scene": 0
            """;

    @Test
    void aGlbWithOneMeshNode_readsItsPositionsAndIndices(@TempDir Path directory) throws IOException {
        byte[] binary = concat(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), shorts(0, 1, 2));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": 42 } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0, "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                        .formatted(SCENE_ONE_NODE);

        GltfModel model = GltfReader.read(writeGlb(directory, "mesh.glb", json, binary));

        assertThat(model.meshNodes()).hasSize(1);
        assertThat(model.meshNodes().get(0).name()).isEqualTo("tri");
        assertThat(model.triangleCount()).isEqualTo(1);
        assertThat(model.meshNodes().get(0).primitives().get(0).indices()).containsExactly(0, 1, 2);
        assertThat(model.allPositions()).containsExactly(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
    }

    @Test
    void nodeTransformsAreComposedFromTheSceneRootDown(@TempDir Path directory) throws IOException {
        // The reason this reader exists rather than the harness's: a Sketchfab export puts its
        // Z-up-to-Y-up conversion in a root node matrix and its unit scale in the node below, so a
        // reader that ignores the hierarchy returns a car on its side at a hundredth of its size.
        String json = triangleDocument(
                """
                "nodes": [
                  { "name": "root", "translation": [10, 0, 0], "children": [1] },
                  { "name": "tri", "mesh": 0, "scale": [2, 2, 2] }
                ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        GltfModel model = GltfReader.read(writeGltf(directory, "scene.gltf", json));

        // Vertex (1,0,0) scaled by 2 then translated by 10.
        assertThat(model.allPositions()).containsExactly(10f, 0f, 0f, 12f, 0f, 0f, 10f, 2f, 0f);
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        assertThat(model.bounds(min, max)).isTrue();
        assertThat(min.x).isEqualTo(10f);
        assertThat(max.x).isEqualTo(12f);
    }

    @Test
    void aGltfBesideItsBinFile_resolvesTheExternalBuffer(@TempDir Path directory) throws IOException {
        // Exactly the shape of the supplied car art: scene.gltf + scene.bin + textures/.
        Files.write(
                directory.resolve("scene.bin"), concat(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), shorts(0, 1, 2)));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": 42, "uri": "scene.bin" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0, "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                        .formatted(SCENE_ONE_NODE);

        GltfModel model = GltfReader.read(writeGltf(directory, "scene.gltf", json));

        assertThat(model.vertexCount()).isEqualTo(3);
        assertThat(model.allPositions()[3]).isEqualTo(1f);
    }

    @Test
    void aBufferUriThatEscapesTheDocumentDirectory_isRefused(@TempDir Path directory) throws IOException {
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": 4, "uri": "../secrets.bin" } ],
                  "meshes": [], "nodes": [], "scenes": [ { "nodes": [] } ]
                }
                """;
        Path file = writeGltf(directory, "scene.gltf", json);

        assertThatThrownBy(() -> GltfReader.read(file))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void interleavedAttributes_respectTheBufferViewStride(@TempDir Path directory) throws IOException {
        // 20 bytes per vertex: a VEC3 position then a VEC2 texture coordinate.
        byte[] vertices = concat(
                floats(0f, 0f, 0f), floats(0.1f, 0.2f),
                floats(1f, 0f, 0f), floats(0.3f, 0.4f),
                floats(0f, 1f, 0f), floats(0.5f, 0.6f));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0, "byteLength": 60, "byteStride": 20 },
                    { "buffer": 0, "byteOffset": 60, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "byteOffset": 0,  "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 3, "type": "VEC2" },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 0, "TEXCOORD_0": 1 }, "indices": 2 } ] } ],
                  %s
                }
                """
                        .formatted(vertices.length + 6, dataUri(concat(vertices, shorts(0, 1, 2))), SCENE_ONE_NODE);

        GltfPrimitive primitive = GltfReader.read(writeGltf(directory, "scene.gltf", json))
                .meshNodes()
                .get(0)
                .primitives()
                .get(0);

        assertThat(primitive.positions()).containsExactly(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        assertThat(primitive.texCoords()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f);
    }

    @Test
    void aTriangleStrip_isExpandedIntoIndependentTriangles(@TempDir Path directory) throws IOException {
        byte[] buffer = concat(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f), shorts(0, 1, 2, 3));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 48 },
                    { "buffer": 0, "byteOffset": 48, "byteLength": 8 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 4, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [
                    { "mode": 5, "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                        .formatted(buffer.length, dataUri(buffer), SCENE_ONE_NODE);

        GltfPrimitive primitive = GltfReader.read(writeGltf(directory, "scene.gltf", json))
                .meshNodes()
                .get(0)
                .primitives()
                .get(0);

        // The odd triangle of a strip is wound the other way; emitting it as-is would give the
        // primitive alternating face orientations and a divergence-theorem volume of nearly zero.
        assertThat(primitive.indices()).containsExactly(0, 1, 2, 2, 1, 3);
    }

    @Test
    void aSparseAccessor_overridesTheElementsItNames(@TempDir Path directory) throws IOException {
        // A positions accessor with no bufferView reads as zeros (glTF 3.6.2) and the sparse block
        // replaces one vertex. Unhandled, this is silent: the base array is legal and the overrides
        // simply never land.
        byte[] buffer = concat(
                shorts(0, 1, 2),
                new byte[2], // 0: triangle indices, padded to a 4-byte boundary
                shorts(1),
                new byte[2], //         8: the sparse index
                floats(5f, 0f, 0f)); //           12: the sparse value
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 6 },
                    { "buffer": 0, "byteOffset": 8,  "byteLength": 2 },
                    { "buffer": 0, "byteOffset": 12, "byteLength": 12 }
                  ],
                  "accessors": [
                    { "componentType": 5126, "count": 3, "type": "VEC3",
                      "sparse": { "count": 1,
                        "indices": { "bufferView": 1, "componentType": 5123 },
                        "values":  { "bufferView": 2 } } },
                    { "bufferView": 0, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                        .formatted(buffer.length, dataUri(buffer), SCENE_ONE_NODE);

        GltfModel model = GltfReader.read(writeGltf(directory, "scene.gltf", json));

        assertThat(model.allPositions()).containsExactly(0f, 0f, 0f, 5f, 0f, 0f, 0f, 0f, 0f);
    }

    @Test
    void normalizedIntegerTexCoords_areScaledIntoZeroToOne(@TempDir Path directory) throws IOException {
        byte[] buffer = concat(
                floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), shorts(0, 65535, 65535, 0, 32767, 32767), shorts(0, 1, 2));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 12 },
                    { "buffer": 0, "byteOffset": 48, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "normalized": true, "count": 3, "type": "VEC2" },
                    { "bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [
                    { "attributes": { "POSITION": 0, "TEXCOORD_0": 1 }, "indices": 2 } ] } ],
                  %s
                }
                """
                        .formatted(buffer.length, dataUri(buffer), SCENE_ONE_NODE);

        GltfPrimitive primitive = GltfReader.read(writeGltf(directory, "scene.gltf", json))
                .meshNodes()
                .get(0)
                .primitives()
                .get(0);

        assertThat(primitive.texCoords()[0]).isEqualTo(0f);
        assertThat(primitive.texCoords()[1]).isEqualTo(1f);
        assertThat(primitive.texCoords()[4]).isCloseTo(0.5f, within(1e-4f));
    }

    @Test
    void aMirroredNodeTransform_flipsTriangleWinding(@TempDir Path directory) throws IOException {
        // A negative-determinant transform reverses face orientation. Left uncorrected, every
        // winding-derived normal points inward and a hull built from it is inside out.
        String json = triangleDocument(
                """
                "nodes": [ { "name": "tri", "mesh": 0, "scale": [-1, 1, 1] } ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        GltfPrimitive primitive = GltfReader.read(writeGltf(directory, "scene.gltf", json))
                .meshNodes()
                .get(0)
                .primitives()
                .get(0);

        assertThat(primitive.indices()).containsExactly(0, 2, 1);
        assertThat(primitive.positions()[3]).isEqualTo(-1f);
    }

    @Test
    void aRequiredExtensionTheReaderDoesNotImplement_isRefused(@TempDir Path directory) throws IOException {
        // D08-R14 turns Draco off precisely so this never ships; an unread compressed mesh would
        // otherwise parse to zero geometry and be reported as an empty part.
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "extensionsRequired": [ "KHR_draco_mesh_compression" ],
                  "meshes": [], "nodes": [], "scenes": [ { "nodes": [] } ]
                }
                """;
        Path file = writeGltf(directory, "scene.gltf", json);

        assertThatThrownBy(() -> GltfReader.read(file))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("KHR_draco_mesh_compression");
    }

    @Test
    void anIndexPastTheVertexCount_isRefused(@TempDir Path directory) throws IOException {
        byte[] buffer = concat(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), shorts(0, 1, 9));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": 42, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                        .formatted(dataUri(buffer), SCENE_ONE_NODE);
        Path file = writeGltf(directory, "scene.gltf", json);

        assertThatThrownBy(() -> GltfReader.read(file))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("addresses outside");
    }

    @Test
    void materialsCarryTheirBaseColourAndTheUriOfTheirImage(@TempDir Path directory) throws IOException {
        byte[] buffer = concat(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), shorts(0, 1, 2));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": 42, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "materials": [ { "name": "paint",
                    "pbrMetallicRoughness": {
                      "baseColorFactor": [0.8, 0.1, 0.1, 1.0],
                      "baseColorTexture": { "index": 0 },
                      "metallicFactor": 0.2, "roughnessFactor": 0.4 },
                    "doubleSided": true } ],
                  "textures": [ { "source": 0 } ],
                  "images": [ { "uri": "textures/body.png" } ],
                  "meshes": [ { "primitives": [
                    { "material": 0, "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                        .formatted(dataUri(buffer), SCENE_ONE_NODE);
        Path file = writeGltf(directory, "scene.gltf", json);

        GltfModel model = GltfReader.read(file);

        assertThat(model.materials()).hasSize(1);
        GltfMaterial material = model.materials().get(0);
        assertThat(material.name()).isEqualTo("paint");
        assertThat(material.baseColorFactor()).containsExactly(0.8f, 0.1f, 0.1f, 1.0f);
        assertThat(material.baseColorTextureUri()).isEqualTo("textures/body.png");
        assertThat(material.isDoubleSided()).isTrue();
        assertThat(model.images()).hasSize(1);
        assertThat(model.images().get(0).uri()).isEqualTo("textures/body.png");
        assertThat(model.materialFor(model.meshNodes().get(0).primitives().get(0)))
                .isSameAs(material);
    }

    @Test
    void theGeometryOptions_skipEverythingTheHeadlessServerNeverLooksAt(@TempDir Path directory) throws IOException {
        byte[] buffer = concat(
                floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
                floats(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
                shorts(0, 1, 2));
        String json =
                """
                {
                  "asset": { "version": "2.0" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 72, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC3" },
                    { "bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "materials": [ { "name": "paint" } ],
                  "meshes": [ { "primitives": [
                    { "material": 0, "attributes": { "POSITION": 0, "NORMAL": 1 }, "indices": 2 } ] } ],
                  %s
                }
                """
                        .formatted(buffer.length, dataUri(buffer), SCENE_ONE_NODE);
        Path file = writeGltf(directory, "scene.gltf", json);

        GltfPrimitive full = GltfReader.read(file, GltfOptions.FULL)
                .meshNodes()
                .get(0)
                .primitives()
                .get(0);
        GltfModel geometry = GltfReader.read(file, GltfOptions.GEOMETRY);

        assertThat(full.normals()).containsExactly(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f);
        assertThat(geometry.meshNodes().get(0).primitives().get(0).normals()).isNull();
        assertThat(geometry.materials()).isEmpty();
        // The material index survives so the same parse can be re-read for rendering.
        assertThat(geometry.meshNodes().get(0).primitives().get(0).materialIndex())
                .isEqualTo(0);
    }

    @Test
    void meshNodesUnder_returnsTheWholeSubtreeOfACollisionSource(@TempDir Path directory) throws IOException {
        // D08-R3: a `_col` object may be several convex pieces, which are its children.
        String json = triangleDocument(
                """
                "nodes": [
                  { "name": "chassis_01_col", "children": [1, 2] },
                  { "name": "piece_a", "mesh": 0 },
                  { "name": "piece_b", "mesh": 0, "translation": [3, 0, 0] }
                ],
                "scenes": [ { "nodes": [0] } ],
                "scene": 0
                """);

        GltfModel model = GltfReader.read(writeGltf(directory, "scene.gltf", json));

        assertThat(model.meshNodes()).hasSize(2);
        assertThat(model.meshNodesUnder("chassis_01_col")).hasSize(2);
        assertThat(model.meshNodesUnder("piece_b")).hasSize(1);
        assertThat(model.meshNodesUnder("nothing_of_the_sort")).isEmpty();
        assertThat(model.positionsUnder("chassis_01_col")).hasSize(18);
        assertThat(model.hasNode("chassis_01_col")).isTrue();
    }
}
