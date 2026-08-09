/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfOptions;
import dev.syndicate.core.asset.GltfReader;
import dev.syndicate.verify.check.Check;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Source-art checks over a corrected model (docs/08_asset_pipeline.md#D08-S4.1,
 * docs/14_test_environment.md#D14-S5.3).
 *
 * <p>The car-shaped box these tests use is deliberately the wrong size and facing the wrong way in
 * the file, because that is the state real art arrives in and the point of {@link ModelImport} is
 * that the checks measure it after the correction rather than before.
 */
@Tag("unit")
class ModelInspectorTest {

    /** A 4.6 m × 1.9 m × 1.3 m box — a car's bounding volume — at 1/100 scale, facing −Z. */
    private static Path writeCarBox(Path directory, float scale) throws IOException {
        float halfLength = 2.3f * scale;
        float halfWidth = 0.95f * scale;
        float height = 1.3f * scale;
        float[] corners = new float[] {
            -halfWidth,
            0f,
            -halfLength,
            halfWidth,
            0f,
            -halfLength,
            -halfWidth,
            height,
            -halfLength,
            halfWidth,
            height,
            -halfLength,
            -halfWidth,
            0f,
            halfLength,
            halfWidth,
            0f,
            halfLength,
            -halfWidth,
            height,
            halfLength,
            halfWidth,
            height,
            halfLength
        };
        byte[] positions = floats(corners);
        byte[] indices = shorts(0, 1, 2, 1, 3, 2, 4, 6, 5, 5, 6, 7, 0, 2, 4, 2, 6, 4, 1, 5, 3, 3, 5, 7);
        String json =
                """
                {
                  "asset": { "version": "2.0", "generator": "ModelInspectorTest" },
                  "buffers": [ { "byteLength": %d, "uri": "data:application/octet-stream;base64,%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 96 },
                    { "buffer": 0, "byteOffset": 96, "byteLength": 48 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 8,  "type": "VEC3" },
                    { "bufferView": 1, "componentType": 5123, "count": 24, "type": "SCALAR" }
                  ],
                  "meshes": [ { "primitives": [ { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  "nodes": [ { "name": "car", "mesh": 0 } ],
                  "scenes": [ { "nodes": [0] } ],
                  "scene": 0
                }
                """
                        .formatted(
                                positions.length + indices.length,
                                Base64.getEncoder().encodeToString(concat(positions, indices)));
        Path file = directory.resolve("scene.gltf");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static Map<String, Check> inspect(Path file) {
        ModelImport correction = ModelImport.besideModel(file);
        GltfModel model = GltfReader.read(file, GltfOptions.FULL);
        correction.applyTo(model);
        List<Check> checks = new ModelInspector(model, file, correction).run();
        return checks.stream().collect(Collectors.toMap(Check::id, Function.identity()));
    }

    @Test
    void aModelAlreadyInMetres_passesEveryCheck(@TempDir Path directory) throws IOException {
        Path file = writeCarBox(directory, 1f);

        Map<String, Check> checks = inspect(file);

        assertThat(checks.get("MODEL-004").status()).isEqualTo(Check.Status.PASS);
        assertThat(checks.get("MODEL-005").status()).isEqualTo(Check.Status.PASS);
        assertThat(checks.get("MODEL-006").status()).isEqualTo(Check.Status.PASS);
        assertThat(checks.get("MODEL-007").status()).isEqualTo(Check.Status.PASS);
        assertThat(checks.values().stream().noneMatch(Check::isBlocking)).isTrue();
    }

    @Test
    void aCentimetreModelWithNoCorrection_failsTheScaleCheck(@TempDir Path directory) throws IOException {
        // This is how the Mustang GTD export arrived: a hundred times too small, and otherwise
        // perfectly valid glTF. Nothing downstream would have noticed except the physics.
        Path file = writeCarBox(directory, 0.01f);

        Check scale = inspect(file).get("MODEL-004");

        assertThat(scale.status()).isEqualTo(Check.Status.FAIL);
        assertThat(scale.actualValue()).isCloseTo(0.046, within(1e-3));
        assertThat(scale.details()).contains("scaleToMetres");
    }

    @Test
    void anImportCorrection_isAppliedBeforeTheChecksMeasure(@TempDir Path directory) throws IOException {
        writeCarBox(directory, 0.01f);
        Files.writeString(
                directory.resolve(ModelImport.FILE_NAME),
                """
                { "scaleToMetres": 100.0, "yawDeg": 180.0, "translationM": { "x": 0, "y": 0, "z": 0 } }
                """);
        Path file = directory.resolve("scene.gltf");

        Map<String, Check> checks = inspect(file);

        assertThat(checks.get("MODEL-004").status()).isEqualTo(Check.Status.PASS);
        assertThat(checks.get("MODEL-004").actualValue()).isCloseTo(4.6, within(1e-3));
    }

    @Test
    void aYawOfOneEightyTurnsTheModelAroundWithoutMovingIt(@TempDir Path directory) throws IOException {
        Path file = writeCarBox(directory, 1f);
        GltfModel model = GltfReader.read(file, GltfOptions.FULL);
        Vector3 before = new Vector3();
        model.bounds(before, new Vector3());

        new ModelImport(1f, 180f, new Vector3()).applyTo(model);

        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        model.bounds(min, max);
        // A symmetric box is its own mirror, so the bounds are unchanged — which is exactly why the
        // yaw has to be confirmed by looking at a render rather than by measuring a bounding box.
        assertThat(min.z).isCloseTo(before.z, within(1e-4f));
        assertThat(max.y).isCloseTo(1.3f, within(1e-4f));
    }

    @Test
    void anImportFileWithNoFields_readsAsTheIdentity(@TempDir Path directory) throws IOException {
        writeCarBox(directory, 1f);
        Files.writeString(directory.resolve(ModelImport.FILE_NAME), "{}");

        assertThat(ModelImport.besideModel(directory.resolve("scene.gltf")).isIdentity())
                .isTrue();
    }

    private static byte[] floats(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static byte[] shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
