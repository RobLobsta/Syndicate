/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads node names, vertex positions, and triangle indices out of a {@code .glb}
 * (docs/14_test_environment.md#D14-S5.3).
 *
 * <p>A direct reader rather than gdx-gltf, for one reason that decides it: the importer builds
 * libGDX {@code Mesh} objects, which are GPU buffers and require a GL context. The physics and
 * asset checks are the ones CI runs, and D14-S5.13 requires them to run with no GL context at all
 * (G17). A loader that needed a display would put the harness's most valuable half out of CI's
 * reach.
 *
 * <p>Only what the harness measures is read: node names, {@code POSITION}, and indices. Materials,
 * textures, animations, and morph targets are skipped — morph *names* are read from the manifest
 * and checked there (ASSET-007), which is where they matter.
 */
public final class GlbReader {

    private static final int MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private static final int FLOAT = 5126;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final int TRIANGLES = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GlbReader() {
        throw new AssertionError("no instances");
    }

    /**
     * Every mesh node in the file, in the file's own node order.
     *
     * <p>Node order is the exporter's, so callers that need a stable order sort by name. The
     * manifest's shard array is the authority on ordering (D09-R8); this is just what is present.
     *
     * @throws AssetLoadException when the file is not a readable GLB, or contains no usable mesh
     */
    public static List<MeshData> read(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new AssetLoadException("cannot read " + path + ": " + e.getMessage(), e);
        }
        if (bytes.length < 12) {
            throw new AssetLoadException("not a GLB: " + path + " is " + bytes.length + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            throw new AssetLoadException("not a GLB (bad magic): " + path);
        }
        buffer.getInt(); // version
        buffer.getInt(); // total length

        JsonNode gltf = null;
        byte[] binary = new byte[0];
        while (buffer.remaining() >= 8) {
            int chunkLength = buffer.getInt();
            int chunkType = buffer.getInt();
            if (chunkLength < 0 || chunkLength > buffer.remaining()) {
                throw new AssetLoadException("truncated GLB chunk in " + path);
            }
            byte[] chunk = new byte[chunkLength];
            buffer.get(chunk);
            if (chunkType == CHUNK_JSON) {
                gltf = parseJson(chunk, path);
            } else if (chunkType == CHUNK_BIN) {
                binary = chunk;
            }
        }
        if (gltf == null) {
            throw new AssetLoadException("GLB has no JSON chunk: " + path);
        }

        List<MeshData> meshes = readNodes(gltf, binary, path);
        if (meshes.isEmpty()) {
            throw new AssetLoadException("GLB contains no usable mesh: " + path);
        }
        return meshes;
    }

    private static JsonNode parseJson(byte[] chunk, Path path) {
        try {
            return MAPPER.readTree(new String(chunk, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssetLoadException("GLB JSON chunk is malformed in " + path, e);
        }
    }

    private static List<MeshData> readNodes(JsonNode gltf, byte[] binary, Path path) {
        JsonNode nodes = gltf.path("nodes");
        JsonNode meshes = gltf.path("meshes");
        List<MeshData> result = new ArrayList<>();

        for (JsonNode node : nodes) {
            if (!node.has("mesh")) {
                continue;
            }
            String name = node.path("name").asText("mesh_" + result.size());
            JsonNode mesh = meshes.get(node.get("mesh").asInt());
            if (mesh == null) {
                continue;
            }
            // Primitives of one mesh are concatenated: they are one renderable object, and a
            // shard split across two materials is still one rigid body.
            List<Float> positions = new ArrayList<>();
            List<Integer> indices = new ArrayList<>();
            for (JsonNode primitive : mesh.path("primitives")) {
                if (primitive.path("mode").asInt(TRIANGLES) != TRIANGLES) {
                    continue; // lines and points carry no volume
                }
                JsonNode positionRef = primitive.path("attributes").path("POSITION");
                if (positionRef.isMissingNode()) {
                    continue;
                }
                int base = positions.size() / 3;
                float[] primitivePositions = readFloats(gltf, binary, positionRef.asInt(), 3, path);
                for (float value : primitivePositions) {
                    positions.add(value);
                }
                int[] primitiveIndices = primitive.has("indices")
                        ? readIndices(gltf, binary, primitive.get("indices").asInt(), path)
                        : sequential(primitivePositions.length / 3);
                for (int index : primitiveIndices) {
                    indices.add(index + base);
                }
            }
            if (positions.isEmpty() || indices.isEmpty()) {
                continue;
            }
            result.add(new MeshData(name, toFloatArray(positions), toIntArray(indices)));
        }
        return result;
    }

    private static float[] readFloats(JsonNode gltf, byte[] binary, int accessorIndex, int components, Path path) {
        JsonNode accessor = gltf.path("accessors").get(accessorIndex);
        if (accessor == null || accessor.path("componentType").asInt() != FLOAT) {
            throw new AssetLoadException("accessor " + accessorIndex + " is not float data in " + path);
        }
        int count = accessor.path("count").asInt();
        ByteBuffer view = bufferView(gltf, binary, accessor, path);
        int stride = strideOr(gltf, accessor, components * 4);

        float[] out = new float[count * components];
        int start = view.position();
        for (int i = 0; i < count; i++) {
            view.position(start + i * stride);
            for (int c = 0; c < components; c++) {
                out[i * components + c] = view.getFloat();
            }
        }
        return out;
    }

    private static int[] readIndices(JsonNode gltf, byte[] binary, int accessorIndex, Path path) {
        JsonNode accessor = gltf.path("accessors").get(accessorIndex);
        if (accessor == null) {
            throw new AssetLoadException("missing index accessor " + accessorIndex + " in " + path);
        }
        int count = accessor.path("count").asInt();
        int componentType = accessor.path("componentType").asInt();
        ByteBuffer view = bufferView(gltf, binary, accessor, path);

        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = switch (componentType) {
                    // Masked rather than widened: glTF indices are unsigned, and Java's signed
                    // byte/short would turn index 200 into -56 and address the wrong vertex.
                case UNSIGNED_BYTE -> view.get() & 0xFF;
                case UNSIGNED_SHORT -> view.getShort() & 0xFFFF;
                case UNSIGNED_INT -> view.getInt();
                default -> throw new AssetLoadException(
                        "unsupported index component type " + componentType + " in " + path);};
        }
        return out;
    }

    private static ByteBuffer bufferView(JsonNode gltf, byte[] binary, JsonNode accessor, Path path) {
        int viewIndex = accessor.path("bufferView").asInt(-1);
        if (viewIndex < 0) {
            throw new AssetLoadException("accessor without a bufferView in " + path);
        }
        JsonNode view = gltf.path("bufferViews").get(viewIndex);
        int offset =
                view.path("byteOffset").asInt(0) + accessor.path("byteOffset").asInt(0);
        int length = view.path("byteLength").asInt();
        if (offset < 0
                || offset + Math.max(0, length - accessor.path("byteOffset").asInt(0)) > binary.length) {
            throw new AssetLoadException("bufferView " + viewIndex + " is out of range in " + path);
        }
        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(offset);
        return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int strideOr(JsonNode gltf, JsonNode accessor, int packed) {
        JsonNode view = gltf.path("bufferViews").get(accessor.path("bufferView").asInt(0));
        int stride = view == null ? 0 : view.path("byteStride").asInt(0);
        return stride > 0 ? stride : packed;
    }

    private static int[] sequential(int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = i;
        }
        return out;
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** A GLB that cannot be parsed, or holds no mesh. Maps to exit 22 (D14-S4.2). */
    public static final class AssetLoadException extends UncheckedIOException {
        public AssetLoadException(String message) {
            this(message, new IOException(message));
        }

        public AssetLoadException(String message, Throwable cause) {
            super(new IOException(message, cause));
        }
    }
}
