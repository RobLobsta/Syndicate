/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Builds glTF documents for the reader's tests.
 *
 * <p>Documents rather than checked-in binaries, deliberately. The reader's risky cases are the ones
 * no exporter in this project produces — an interleaved buffer view, a sparse accessor, a mirrored
 * node transform, a triangle strip — so a fixture file would have to be hand-forged anyway, and a
 * forged file in {@code resources/} is a blob nobody can review. Here the bytes and the expectation
 * are next to each other.
 */
final class GltfTestDocuments {

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private GltfTestDocuments() {
        throw new AssertionError("no instances");
    }

    /** A base64 {@code data:} URI for a buffer, which is how these documents carry their bytes. */
    static String dataUri(byte[] bytes) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** Little-endian floats, the only byte order glTF uses. */
    static byte[] floats(float... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** Little-endian unsigned shorts. */
    static byte[] shorts(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putShort((short) value);
        }
        return buffer.array();
    }

    /** Little-endian unsigned ints. */
    static byte[] ints(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            buffer.putInt(value);
        }
        return buffer.array();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Writes a {@code .gltf} text document and returns its path. */
    static Path writeGltf(Path directory, String name, String json) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Writes a {@code .glb}: the 12-byte container header, a JSON chunk and a BIN chunk, each padded
     * to four bytes as the specification requires.
     */
    static Path writeGlb(Path directory, String name, String json, byte[] binary) throws IOException {
        byte[] jsonChunk = pad(json.getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] binChunk = pad(binary, (byte) 0);
        int total = 12 + 8 + jsonChunk.length + (binChunk.length == 0 ? 0 : 8 + binChunk.length);

        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GLB_MAGIC);
        out.putInt(2);
        out.putInt(total);
        out.putInt(jsonChunk.length);
        out.putInt(CHUNK_JSON);
        out.put(jsonChunk);
        if (binChunk.length > 0) {
            out.putInt(binChunk.length);
            out.putInt(CHUNK_BIN);
            out.put(binChunk);
        }
        Path file = directory.resolve(name);
        Files.write(file, out.array());
        return file;
    }

    private static byte[] pad(byte[] bytes, byte filler) {
        int remainder = bytes.length % 4;
        if (remainder == 0) {
            return bytes;
        }
        byte[] padded = new byte[bytes.length + (4 - remainder)];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        for (int i = bytes.length; i < padded.length; i++) {
            padded[i] = filler;
        }
        return padded;
    }

    /**
     * A single triangle at {@code (0,0,0)}, {@code (1,0,0)}, {@code (0,1,0)}, as a complete document
     * body with one buffer, one mesh and no node — callers supply the {@code nodes} and
     * {@code scenes} they want to test.
     *
     * @param nodesAndScenes the {@code "nodes": [...], "scenes": [...], "scene": 0} portion
     */
    static String triangleDocument(String nodesAndScenes) {
        byte[] positions = floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f);
        byte[] indices = shorts(0, 1, 2);
        return """
                {
                  "asset": { "version": "2.0", "generator": "GltfTestDocuments" },
                  "buffers": [ { "byteLength": %d, "uri": "%s" } ],
                  "bufferViews": [
                    { "buffer": 0, "byteOffset": 0,  "byteLength": 36 },
                    { "buffer": 0, "byteOffset": 36, "byteLength": 6 }
                  ],
                  "accessors": [
                    { "bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                      "min": [0,0,0], "max": [1,1,0] },
                    { "bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR" }
                  ],
                  "meshes": [ { "name": "tri", "primitives": [
                    { "attributes": { "POSITION": 0 }, "indices": 1 } ] } ],
                  %s
                }
                """
                .formatted(positions.length + indices.length, dataUri(concat(positions, indices)), nodesAndScenes);
    }
}
