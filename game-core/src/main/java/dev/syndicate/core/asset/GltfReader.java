/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;

/**
 * Reads a glTF 2.0 document — {@code .glb} or {@code .gltf} — into CPU-side geometry
 * (docs/08_asset_pipeline.md#D08-S4.5, docs/08_asset_pipeline.md#D08-S5.3).
 *
 * <p><b>Why this exists rather than gdx-gltf.</b> D08-R12 names gdx-gltf as the importer, and
 * gdx-gltf builds libGDX {@code Mesh} objects, which are GPU buffers requiring a GL context. G17
 * requires every gameplay system to run headless and D02-S4.5 puts gdx-gltf outside
 * {@code game-core}'s dependency set entirely, so the module the dedicated server shares cannot use
 * it — which is exactly the wall DEC-008 hit in the verification harness. This reader is the answer
 * DEV-010 called for: the same parse serves the headless server, the harness and the client.
 *
 * <p><b>Node transforms are composed and applied.</b> The harness's earlier reader ignored them,
 * which was harmless only because every fixture the Blender tool writes has an identity transform.
 * Real art does not: a Sketchfab export puts its Z-up-to-Y-up conversion in a root node matrix and
 * an FBX-derived export puts its centimetre-to-metre scale there too. Reading raw mesh coordinates
 * from those files yields a car on its side at a hundredth of its size, and every quantity derived
 * from it — hull, volume, wheel radius — is self-consistently wrong.
 *
 * <p><b>What is read.</b> Positions, indices, and — when {@link GltfOptions} asks — normals, the
 * first texture coordinate set, materials and image references. What is deliberately not read:
 *
 * <ul>
 *   <li><b>Skinning.</b> Joint weights are ignored and the node's own transform is used. Nothing in
 *       the asset pipeline authors a skinned part (D08-S4.1), and the one place skins appear — a
 *       Sketchfab export that rigged a static car — has joint matrices that reproduce the node
 *       transforms exactly, so ignoring them changes nothing. {@link #skinnedNodeCount(Path)}
 *       reports the count so a caller can check rather than assume.
 *   <li><b>Morph targets.</b> The damage morphs of D07-S5.5 are a client concern; the simulation's
 *       hulls come from the undeformed mesh (G6). A renderer that wants them reads them itself.
 *   <li><b>Animations, cameras, lights.</b> D08-R14 forbids exporting them.
 *   <li><b>Draco.</b> D08-R14 forbids it, so an {@code extensionsRequired} entry naming it is
 *       reported as an error rather than silently producing empty geometry.
 * </ul>
 */
public final class GltfReader {

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private static final int BYTE = 5120;
    private static final int UNSIGNED_BYTE = 5121;
    private static final int SHORT = 5122;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int UNSIGNED_INT = 5125;
    private static final int FLOAT = 5126;

    private static final int MODE_TRIANGLES = 4;
    private static final int MODE_TRIANGLE_STRIP = 5;
    private static final int MODE_TRIANGLE_FAN = 6;

    /** A guard on malformed input, not a budget: no real document nests this deep (D08-R2). */
    private static final int MAX_NODE_DEPTH = 256;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path source;
    private final GltfOptions options;
    private final JsonNode gltf;
    private final byte[][] buffers;

    private GltfReader(Path source, GltfOptions options, JsonNode gltf, byte[][] buffers) {
        this.source = source;
        this.options = options;
        this.gltf = gltf;
        this.buffers = buffers;
    }

    /** Reads everything the reader understands. */
    public static GltfModel read(Path file) {
        return read(file, GltfOptions.FULL);
    }

    /**
     * Reads a glTF document.
     *
     * @param file a {@code .glb} or {@code .gltf}; the format is decided by the magic number, not
     *     the extension, so a mis-named file still reads
     * @throws GltfException if the file is unreadable, is not glTF 2.0, requires an extension this
     *     reader does not implement, or refers to a buffer or accessor that is not there
     */
    public static GltfModel read(Path file, GltfOptions options) {
        return open(file, options).parse();
    }

    /**
     * How many geometry-bearing nodes in the document are skinned.
     *
     * <p>Skinning is ignored (see the class comment), and a caller that cares whether that is safe
     * should be able to ask rather than trust the comment. Non-zero on art rigged by an exporter
     * that did not need to be; non-zero on art that genuinely deforms means this reader is the wrong
     * tool for it.
     */
    public static int skinnedNodeCount(Path file) {
        JsonNode gltf = open(file, GltfOptions.GEOMETRY).gltf;
        int count = 0;
        for (JsonNode node : gltf.path("nodes")) {
            if (node.has("skin") && node.has("mesh")) {
                count++;
            }
        }
        return count;
    }

    private static GltfReader open(Path file, GltfOptions options) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new GltfException("cannot read " + file + ": " + e.getMessage(), e);
        }
        JsonNode gltf;
        byte[] glbBinary = null;
        if (bytes.length >= 12
                && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() == GLB_MAGIC) {
            ByteBuffer container = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            container.getInt(); // magic, already matched
            int version = container.getInt();
            if (version != 2) {
                throw new GltfException("GLB container version " + version + " is not 2: " + file);
            }
            container.getInt(); // total length; the chunk walk below is the authority
            JsonNode json = null;
            while (container.remaining() >= 8) {
                int chunkLength = container.getInt();
                int chunkType = container.getInt();
                if (chunkLength < 0 || chunkLength > container.remaining()) {
                    throw new GltfException("truncated GLB chunk in " + file);
                }
                byte[] chunk = new byte[chunkLength];
                container.get(chunk);
                if (chunkType == CHUNK_JSON) {
                    json = parseJson(chunk, file);
                } else if (chunkType == CHUNK_BIN) {
                    glbBinary = chunk;
                }
            }
            if (json == null) {
                throw new GltfException("GLB has no JSON chunk: " + file);
            }
            gltf = json;
        } else {
            gltf = parseJson(stripByteOrderMark(bytes), file);
        }

        String version = gltf.path("asset").path("version").asText("");
        if (!version.startsWith("2.")) {
            throw new GltfException("glTF version \"" + version + "\" is not 2.x: " + file);
        }
        JsonNode required = gltf.path("extensionsRequired");
        if (required.isArray() && !required.isEmpty()) {
            // An extension in `extensionsUsed` is safe to ignore by definition (glTF §3.12); one in
            // `extensionsRequired` changes what the data means, so ignoring it produces geometry
            // that parses and is wrong. Draco is the case D08-R14 exists to prevent.
            List<String> names = new ArrayList<>();
            required.forEach(node -> names.add(node.asText()));
            throw new GltfException("required extensions " + names + " are not implemented: " + file);
        }
        return new GltfReader(file, options, gltf, resolveBuffers(gltf, glbBinary, file));
    }

    private static byte[] stripByteOrderMark(byte[] bytes) {
        boolean hasMark = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
        return hasMark ? Arrays.copyOfRange(bytes, 3, bytes.length) : bytes;
    }

    private static JsonNode parseJson(byte[] chunk, Path file) {
        try {
            return MAPPER.readTree(new String(chunk, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GltfException("glTF JSON is malformed in " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves every declared buffer to its bytes.
     *
     * <p>Three sources, all of which the shipped and source art use between them: the GLB binary
     * chunk (a buffer with no URI), a {@code data:} URI, and a file beside the document — which is
     * what a Sketchfab {@code .gltf} plus {@code scene.bin} is. A relative URI is percent-decoded
     * and resolved against the document's directory, and is rejected if it escapes it, because a
     * document is content and content does not get to name arbitrary files on the host.
     */
    private static byte[][] resolveBuffers(JsonNode gltf, byte[] glbBinary, Path file) {
        JsonNode declared = gltf.path("buffers");
        byte[][] resolved = new byte[declared.size()][];
        Path baseDirectory = file.toAbsolutePath().normalize().getParent();
        for (int i = 0; i < declared.size(); i++) {
            JsonNode buffer = declared.get(i);
            String uri = buffer.path("uri").asText(null);
            if (uri == null || uri.isEmpty()) {
                if (glbBinary == null) {
                    throw new GltfException("buffer " + i + " has no URI and there is no GLB chunk: " + file);
                }
                resolved[i] = glbBinary;
            } else if (uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
                    throw new GltfException("buffer " + i + " has a data URI that is not base64: " + file);
                }
                resolved[i] = Base64.getDecoder().decode(uri.substring(comma + 1));
            } else {
                resolved[i] = readSibling(baseDirectory, uri, "buffer " + i, file);
            }
        }
        return resolved;
    }

    private static byte[] readSibling(Path baseDirectory, String uri, String subject, Path file) {
        Path resolved = resolveSibling(baseDirectory, uri, subject, file);
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new GltfException(subject + " names " + uri + ", which cannot be read beside " + file, e);
        }
    }

    private static Path resolveSibling(Path baseDirectory, String uri, String subject, Path file) {
        if (baseDirectory == null) {
            throw new GltfException(subject + " names " + uri + " but " + file + " has no directory");
        }
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        Path resolved = baseDirectory.resolve(decoded).normalize();
        if (!resolved.startsWith(baseDirectory)) {
            throw new GltfException(subject + " names " + uri + ", which escapes " + baseDirectory);
        }
        return resolved;
    }

    // ---------------------------------------------------------------- parsing

    private GltfModel parse() {
        JsonNode nodes = gltf.path("nodes");
        int nodeCount = nodes.size();
        String[] names = new String[nodeCount];
        int[] parents = new int[nodeCount];
        Arrays.fill(parents, -1);
        for (int i = 0; i < nodeCount; i++) {
            JsonNode node = nodes.get(i);
            names[i] = node.path("name").asText("node_" + i);
            for (JsonNode child : node.path("children")) {
                int childIndex = child.asInt(-1);
                if (childIndex < 0 || childIndex >= nodeCount) {
                    throw new GltfException("node " + i + " names child " + child.asText() + " in " + source);
                }
                parents[childIndex] = i;
            }
        }

        List<GltfMaterial> materials = options.readMaterials() ? readMaterials() : List.of();
        List<GltfImage> images = options.readMaterials() ? readImages() : List.of();

        List<GltfMeshNode> meshNodes = new ArrayList<>();
        BitSet visited = new BitSet(nodeCount);
        for (int root : sceneRoots(nodeCount, parents)) {
            traverse(root, new Matrix4(), meshNodes, visited, 0);
        }
        return new GltfModel(
                source,
                gltf.path("asset").path("generator").asText("unknown"),
                meshNodes,
                materials,
                images,
                names,
                parents);
    }

    /**
     * The nodes traversal starts from: the default scene's, or — when the document declares no scene
     * — every node without a parent. glTF §3.5 makes {@code scene} optional, and a document with no
     * scene is a library rather than something to draw; reading its roots is more useful than
     * reading nothing.
     */
    private int[] sceneRoots(int nodeCount, int[] parents) {
        JsonNode scenes = gltf.path("scenes");
        int sceneIndex = gltf.path("scene").asInt(0);
        if (scenes.isArray() && sceneIndex >= 0 && sceneIndex < scenes.size()) {
            JsonNode roots = scenes.get(sceneIndex).path("nodes");
            int[] out = new int[roots.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = roots.get(i).asInt();
            }
            return out;
        }
        int count = 0;
        for (int i = 0; i < nodeCount; i++) {
            if (parents[i] < 0) {
                count++;
            }
        }
        int[] out = new int[count];
        int cursor = 0;
        for (int i = 0; i < nodeCount; i++) {
            if (parents[i] < 0) {
                out[cursor++] = i;
            }
        }
        return out;
    }

    private void traverse(int nodeIndex, Matrix4 parentTransform, List<GltfMeshNode> out, BitSet visited, int depth) {
        JsonNode nodes = gltf.path("nodes");
        if (nodeIndex < 0 || nodeIndex >= nodes.size()) {
            throw new GltfException("scene names node " + nodeIndex + ", which does not exist, in " + source);
        }
        if (visited.get(nodeIndex)) {
            throw new GltfException(
                    "node " + nodeIndex + " is reachable twice; the node graph is not a tree in " + source);
        }
        if (depth > MAX_NODE_DEPTH) {
            throw new GltfException("node graph nests deeper than " + MAX_NODE_DEPTH + " in " + source);
        }
        visited.set(nodeIndex);

        JsonNode node = nodes.get(nodeIndex);
        Matrix4 world = new Matrix4(parentTransform).mul(localTransform(node));
        if (node.has("mesh")) {
            List<GltfPrimitive> primitives = readMesh(node.get("mesh").asInt(), world);
            if (!primitives.isEmpty()) {
                out.add(new GltfMeshNode(
                        node.path("name").asText("node_" + nodeIndex), nodeIndex, new Matrix4(world), primitives));
            }
        }
        for (JsonNode child : node.path("children")) {
            traverse(child.asInt(), world, out, visited, depth + 1);
        }
    }

    /** A node's own transform: a column-major matrix, or a TRS triple, per glTF §3.5.2. */
    private static Matrix4 localTransform(JsonNode node) {
        if (node.has("matrix")) {
            JsonNode values = node.get("matrix");
            if (values.size() != 16) {
                throw new GltfException("node matrix has " + values.size() + " values, not 16");
            }
            float[] columnMajor = new float[16];
            for (int i = 0; i < 16; i++) {
                columnMajor[i] = (float) values.get(i).asDouble();
            }
            return new Matrix4(columnMajor);
        }
        Vector3 translation = readVector3(node.path("translation"), 0f);
        Vector3 scale = readVector3(node.path("scale"), 1f);
        JsonNode rotation = node.path("rotation");
        Quaternion quaternion = rotation.size() == 4
                ? new Quaternion(
                        (float) rotation.get(0).asDouble(),
                        (float) rotation.get(1).asDouble(),
                        (float) rotation.get(2).asDouble(),
                        (float) rotation.get(3).asDouble())
                : new Quaternion(0f, 0f, 0f, 1f);
        return new Matrix4().set(translation, quaternion, scale);
    }

    private static Vector3 readVector3(JsonNode array, float fallback) {
        if (array.size() != 3) {
            return new Vector3(fallback, fallback, fallback);
        }
        return new Vector3((float) array.get(0).asDouble(), (float) array.get(1).asDouble(), (float)
                array.get(2).asDouble());
    }

    private List<GltfPrimitive> readMesh(int meshIndex, Matrix4 world) {
        JsonNode meshes = gltf.path("meshes");
        if (meshIndex < 0 || meshIndex >= meshes.size()) {
            throw new GltfException("node names mesh " + meshIndex + ", which does not exist, in " + source);
        }
        Matrix3 normalMatrix = new Matrix3().set(world).inv().transpose();
        boolean mirrored = world.det3x3() < 0f;

        List<GltfPrimitive> out = new ArrayList<>();
        for (JsonNode primitive : meshes.get(meshIndex).path("primitives")) {
            int mode = primitive.path("mode").asInt(MODE_TRIANGLES);
            if (mode != MODE_TRIANGLES && mode != MODE_TRIANGLE_STRIP && mode != MODE_TRIANGLE_FAN) {
                continue; // points and lines enclose no volume and draw nothing we want
            }
            JsonNode attributes = primitive.path("attributes");
            if (!attributes.has("POSITION")) {
                continue;
            }
            float[] positions = readAccessorAsFloats(attributes.get("POSITION").asInt(), 3);
            transformPositions(positions, world);

            float[] normals = null;
            if (options.readNormals() && attributes.has("NORMAL")) {
                normals = readAccessorAsFloats(attributes.get("NORMAL").asInt(), 3);
                transformNormals(normals, normalMatrix);
            }
            float[] texCoords = null;
            if (options.readTexCoords() && attributes.has("TEXCOORD_0")) {
                texCoords = readAccessorAsFloats(attributes.get("TEXCOORD_0").asInt(), 2);
            }

            int vertexCount = positions.length / 3;
            int[] indices = primitive.has("indices")
                    ? readAccessorAsInts(primitive.get("indices").asInt())
                    : sequential(vertexCount);
            indices = toTriangles(indices, mode);
            validateIndices(indices, vertexCount);
            // A mirrored transform reverses triangle orientation. Left uncorrected, every normal
            // derived from winding points inward — which is how a hull's faces end up inside out and
            // a divergence-theorem volume comes back negative.
            if (mirrored) {
                flipWinding(indices);
            }
            out.add(new GltfPrimitive(
                    positions,
                    normals,
                    texCoords,
                    indices,
                    primitive.path("material").asInt(-1)));
        }
        return out;
    }

    private static void transformPositions(float[] positions, Matrix4 world) {
        Vector3 scratch = new Vector3();
        for (int i = 0; i < positions.length; i += 3) {
            scratch.set(positions[i], positions[i + 1], positions[i + 2]).mul(world);
            positions[i] = scratch.x;
            positions[i + 1] = scratch.y;
            positions[i + 2] = scratch.z;
        }
    }

    private static void transformNormals(float[] normals, Matrix3 normalMatrix) {
        Vector3 scratch = new Vector3();
        for (int i = 0; i < normals.length; i += 3) {
            scratch.set(normals[i], normals[i + 1], normals[i + 2])
                    .mul(normalMatrix)
                    .nor();
            normals[i] = scratch.x;
            normals[i + 1] = scratch.y;
            normals[i + 2] = scratch.z;
        }
    }

    private static void flipWinding(int[] indices) {
        for (int t = 0; t + 2 < indices.length; t += 3) {
            int swap = indices[t + 1];
            indices[t + 1] = indices[t + 2];
            indices[t + 2] = swap;
        }
    }

    private void validateIndices(int[] indices, int vertexCount) {
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new GltfException(
                        "index " + index + " addresses outside a " + vertexCount + "-vertex primitive in " + source);
            }
        }
    }

    /** Expands a strip or a fan into independent triangles; a triangle list passes through. */
    private static int[] toTriangles(int[] indices, int mode) {
        if (mode == MODE_TRIANGLES) {
            return indices.length % 3 == 0 ? indices : Arrays.copyOf(indices, indices.length - indices.length % 3);
        }
        if (indices.length < 3) {
            return new int[0];
        }
        int triangles = indices.length - 2;
        int[] out = new int[triangles * 3];
        for (int t = 0; t < triangles; t++) {
            if (mode == MODE_TRIANGLE_FAN) {
                out[t * 3] = indices[0];
                out[t * 3 + 1] = indices[t + 1];
                out[t * 3 + 2] = indices[t + 2];
            } else if ((t & 1) == 0) {
                out[t * 3] = indices[t];
                out[t * 3 + 1] = indices[t + 1];
                out[t * 3 + 2] = indices[t + 2];
            } else {
                // Odd strip triangles are wound the other way; emitting them as-is would give the
                // primitive alternating face orientations.
                out[t * 3] = indices[t + 1];
                out[t * 3 + 1] = indices[t];
                out[t * 3 + 2] = indices[t + 2];
            }
        }
        return out;
    }

    private static int[] sequential(int count) {
        int[] out = new int[count - count % 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = i;
        }
        return out;
    }

    // ------------------------------------------------------------- accessors

    private static int componentSize(int componentType) {
        return switch (componentType) {
            case BYTE, UNSIGNED_BYTE -> 1;
            case SHORT, UNSIGNED_SHORT -> 2;
            case UNSIGNED_INT, FLOAT -> 4;
            default -> throw new GltfException("unsupported component type " + componentType);
        };
    }

    private static int componentCount(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new GltfException("unsupported accessor type " + type);
        };
    }

    private JsonNode accessor(int index) {
        JsonNode accessors = gltf.path("accessors");
        if (index < 0 || index >= accessors.size()) {
            throw new GltfException("accessor " + index + " does not exist in " + source);
        }
        return accessors.get(index);
    }

    /**
     * Decodes an accessor into floats, normalising integer components the way glTF §3.6.2.1 defines.
     *
     * @param components how many components the caller needs; an accessor with more is truncated
     *     per component, and one with fewer is an error
     */
    private float[] readAccessorAsFloats(int accessorIndex, int components) {
        JsonNode accessor = accessor(accessorIndex);
        int declared = componentCount(accessor.path("type").asText("SCALAR"));
        if (declared < components) {
            throw new GltfException("accessor " + accessorIndex + " is "
                    + accessor.path("type").asText() + " but " + components + " components are needed in " + source);
        }
        int componentType = accessor.path("componentType").asInt();
        boolean normalized = accessor.path("normalized").asBoolean(false);
        int count = accessor.path("count").asInt();

        float[] out = new float[count * components];
        ElementReader reader = elementReader(accessor, declared, componentType);
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < components; c++) {
                out[i * components + c] = toFloat(reader.read(i, c), componentType, normalized);
            }
        }
        applySparseFloats(accessor, out, components, declared, componentType, normalized);
        return out;
    }

    /** Decodes an accessor into indices. glTF indices are unsigned; masking keeps them so. */
    private int[] readAccessorAsInts(int accessorIndex) {
        JsonNode accessor = accessor(accessorIndex);
        int componentType = accessor.path("componentType").asInt();
        int count = accessor.path("count").asInt();
        int[] out = new int[count];
        ElementReader reader = elementReader(accessor, 1, componentType);
        for (int i = 0; i < count; i++) {
            out[i] = (int) reader.read(i, 0);
        }
        applySparseInts(accessor, out);
        return out;
    }

    /** Reads component {@code c} of element {@code i}, or zero when the accessor has no bufferView. */
    @FunctionalInterface
    private interface ElementReader {
        long read(int element, int component);
    }

    private ElementReader elementReader(JsonNode accessor, int declaredComponents, int componentType) {
        int viewIndex = accessor.path("bufferView").asInt(-1);
        if (viewIndex < 0) {
            // glTF §3.6.2: an accessor without a bufferView reads as zeros, and exists so a sparse
            // accessor can describe a mostly-empty array without storing it.
            return (element, component) -> 0L;
        }
        int accessorOffset = accessor.path("byteOffset").asInt(0);
        return byteReader(viewIndex, accessorOffset, declaredComponents, componentType);
    }

    private ElementReader byteReader(int viewIndex, int accessorOffset, int components, int componentType) {
        JsonNode views = gltf.path("bufferViews");
        if (viewIndex < 0 || viewIndex >= views.size()) {
            throw new GltfException("bufferView " + viewIndex + " does not exist in " + source);
        }
        JsonNode view = views.get(viewIndex);
        int bufferIndex = view.path("buffer").asInt(-1);
        if (bufferIndex < 0 || bufferIndex >= buffers.length) {
            throw new GltfException("bufferView " + viewIndex + " names buffer " + bufferIndex + " in " + source);
        }
        byte[] buffer = buffers[bufferIndex];
        int size = componentSize(componentType);
        int packed = size * components;
        int stride = view.path("byteStride").asInt(0);
        int elementStride = stride > 0 ? stride : packed;
        int base = view.path("byteOffset").asInt(0) + accessorOffset;
        int limit = view.path("byteOffset").asInt(0) + view.path("byteLength").asInt(0);
        if (base < 0 || limit > buffer.length) {
            throw new GltfException("bufferView " + viewIndex + " runs past its buffer in " + source);
        }
        ByteBuffer bytes = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        return (element, component) -> {
            int offset = base + element * elementStride + component * size;
            if (offset + size > limit) {
                throw new GltfException(
                        "accessor element " + element + " runs past bufferView " + viewIndex + " in " + source);
            }
            return switch (componentType) {
                case BYTE -> bytes.get(offset);
                case UNSIGNED_BYTE -> bytes.get(offset) & 0xFFL;
                case SHORT -> bytes.getShort(offset);
                case UNSIGNED_SHORT -> bytes.getShort(offset) & 0xFFFFL;
                case UNSIGNED_INT -> bytes.getInt(offset) & 0xFFFFFFFFL;
                case FLOAT -> Float.floatToRawIntBits(bytes.getFloat(offset)) & 0xFFFFFFFFL;
                default -> throw new GltfException("unsupported component type " + componentType + " in " + source);
            };
        };
    }

    private static float toFloat(long raw, int componentType, boolean normalized) {
        if (componentType == FLOAT) {
            return Float.intBitsToFloat((int) raw);
        }
        if (!normalized) {
            return raw;
        }
        return switch (componentType) {
            case BYTE -> Math.max((float) raw / 127f, -1f);
            case UNSIGNED_BYTE -> (float) raw / 255f;
            case SHORT -> Math.max((float) raw / 32767f, -1f);
            case UNSIGNED_SHORT -> (float) raw / 65535f;
            default -> raw;
        };
    }

    /**
     * Overwrites the elements a sparse accessor replaces (glTF §3.6.2.3).
     *
     * <p>Nothing the Blender tool writes is sparse, but a mesh with a mostly-zero morph target
     * commonly is, and an unhandled {@code sparse} block is silent: the base array is legal and the
     * overrides simply never land.
     */
    private void applySparseFloats(
            JsonNode accessor, float[] out, int components, int declared, int componentType, boolean normalized) {
        JsonNode sparse = accessor.path("sparse");
        if (sparse.isMissingNode()) {
            return;
        }
        int count = sparse.path("count").asInt();
        ElementReader indices = sparseIndexReader(sparse);
        JsonNode values = sparse.path("values");
        ElementReader reader = byteReader(
                values.path("bufferView").asInt(-1), values.path("byteOffset").asInt(0), declared, componentType);
        for (int i = 0; i < count; i++) {
            int target = (int) indices.read(i, 0);
            if (target < 0 || (target + 1) * components > out.length) {
                throw new GltfException("sparse index " + target + " is out of range in " + source);
            }
            for (int c = 0; c < components; c++) {
                out[target * components + c] = toFloat(reader.read(i, c), componentType, normalized);
            }
        }
    }

    private void applySparseInts(JsonNode accessor, int[] out) {
        JsonNode sparse = accessor.path("sparse");
        if (sparse.isMissingNode()) {
            return;
        }
        int count = sparse.path("count").asInt();
        ElementReader indices = sparseIndexReader(sparse);
        JsonNode values = sparse.path("values");
        ElementReader reader = byteReader(
                values.path("bufferView").asInt(-1),
                values.path("byteOffset").asInt(0),
                1,
                accessor.path("componentType").asInt());
        for (int i = 0; i < count; i++) {
            int target = (int) indices.read(i, 0);
            if (target < 0 || target >= out.length) {
                throw new GltfException("sparse index " + target + " is out of range in " + source);
            }
            out[target] = (int) reader.read(i, 0);
        }
    }

    private ElementReader sparseIndexReader(JsonNode sparse) {
        JsonNode indices = sparse.path("indices");
        return byteReader(
                indices.path("bufferView").asInt(-1),
                indices.path("byteOffset").asInt(0),
                1,
                indices.path("componentType").asInt());
    }

    // ------------------------------------------------------- materials, images

    private List<GltfMaterial> readMaterials() {
        List<GltfMaterial> out = new ArrayList<>();
        JsonNode materials = gltf.path("materials");
        for (int i = 0; i < materials.size(); i++) {
            JsonNode material = materials.get(i);
            JsonNode pbr = material.path("pbrMetallicRoughness");
            int imageIndex =
                    imageIndexOf(pbr.path("baseColorTexture").path("index").asInt(-1));
            if (imageIndex >= gltf.path("images").size()) {
                throw new GltfException(
                        "material " + i + " names image " + imageIndex + ", which does not exist, in " + source);
            }
            out.add(new GltfMaterial(
                    material.path("name").asText("material_" + i),
                    readFloats(pbr.path("baseColorFactor"), new float[] {1f, 1f, 1f, 1f}),
                    imageIndex < 0
                            ? null
                            : gltf.path("images").get(imageIndex).path("uri").asText(null),
                    imageIndex,
                    (float) pbr.path("metallicFactor").asDouble(1.0),
                    (float) pbr.path("roughnessFactor").asDouble(1.0),
                    readFloats(material.path("emissiveFactor"), new float[] {0f, 0f, 0f}),
                    material.path("alphaMode").asText("OPAQUE"),
                    material.path("doubleSided").asBoolean(false)));
        }
        return out;
    }

    private int imageIndexOf(int textureIndex) {
        if (textureIndex < 0) {
            return -1;
        }
        JsonNode textures = gltf.path("textures");
        if (textureIndex >= textures.size()) {
            throw new GltfException("texture " + textureIndex + " does not exist in " + source);
        }
        return textures.get(textureIndex).path("source").asInt(-1);
    }

    private static float[] readFloats(JsonNode array, float[] fallback) {
        if (!array.isArray() || array.size() != fallback.length) {
            return fallback;
        }
        float[] out = new float[fallback.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) array.get(i).asDouble();
        }
        return out;
    }

    private List<GltfImage> readImages() {
        List<GltfImage> out = new ArrayList<>();
        JsonNode images = gltf.path("images");
        for (int i = 0; i < images.size(); i++) {
            JsonNode image = images.get(i);
            String name = image.path("name").asText("image_" + i);
            String mimeType = image.path("mimeType").asText(null);
            String uri = image.path("uri").asText(null);
            byte[] embedded = null;
            if (uri != null && uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                embedded = comma < 0 ? new byte[0] : Base64.getDecoder().decode(uri.substring(comma + 1));
                uri = null;
            } else if (uri == null && image.has("bufferView")) {
                embedded = sliceOf(image.get("bufferView").asInt());
            }
            out.add(new GltfImage(name, uri, mimeType, embedded));
        }
        return out;
    }

    private byte[] sliceOf(int viewIndex) {
        JsonNode views = gltf.path("bufferViews");
        if (viewIndex < 0 || viewIndex >= views.size()) {
            throw new GltfException("image names bufferView " + viewIndex + ", which does not exist, in " + source);
        }
        JsonNode view = views.get(viewIndex);
        byte[] buffer = buffers[view.path("buffer").asInt(0)];
        int offset = view.path("byteOffset").asInt(0);
        int length = view.path("byteLength").asInt(0);
        if (offset < 0 || offset + length > buffer.length) {
            throw new GltfException("image bufferView " + viewIndex + " runs past its buffer in " + source);
        }
        return Arrays.copyOfRange(buffer, offset, offset + length);
    }
}
