/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.model;

import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.syndicate.core.asset.GltfMeshNode;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfPrimitive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The correction that takes a piece of source art into the game's units and axes
 * (docs/00_master_index.md#D00-R16, docs/08_asset_pipeline.md#D08-S4.1).
 *
 * <p>Downloaded art does not arrive in metres facing +Z, and the two vehicles in
 * {@code art-source/vehicles/} are a fair sample of why: one carries a 0.9625 scale on its root node
 * and faces −Z; the other was exported through an FBX pipeline that applied a centimetre-to-metre
 * conversion the mesh data had already had, leaving it a hundred times too small. Neither is a
 * defect in the art. Both are corrections that have to be recorded somewhere, once, or every tool
 * that touches the file re-derives them and one of them gets it wrong.
 *
 * <p>So they live in an {@code import.json} beside the model, and the harness applies them before it
 * measures anything — which is what turns the numbers in that file from a claim into something the
 * checks either confirm or fail.
 *
 * <p>Order is scale, then yaw about Y, then translation, so the fields can be read independently: the
 * scale is a unit conversion, the yaw is which way the car faces, and the translation is where the
 * origin should sit relative to the art's own.
 *
 * @param scaleToMetres multiplied into every coordinate; 1.0 when the art is already in metres
 * @param yawDeg rotation about +Y applied after scaling; 180 turns a −Z-facing model to face +Z
 * @param translationM added last, in metres — normally used to drop the origin to the ground plane
 */
public record ModelImport(float scaleToMetres, float yawDeg, Vector3 translationM) {

    /** The file the correction lives in, beside the model it corrects. */
    public static final String FILE_NAME = "import.json";

    /** No correction: the art is already in the game's units and axes. */
    public static ModelImport identity() {
        return new ModelImport(1f, 0f, new Vector3());
    }

    /** True when applying this would change nothing. */
    public boolean isIdentity() {
        return scaleToMetres == 1f && yawDeg == 0f && translationM.isZero();
    }

    /**
     * Reads {@code import.json} from the directory holding {@code model}, or returns the identity
     * when there is none.
     *
     * @throws UncheckedIOException if the file exists but cannot be read or parsed — a correction
     *     that is present and wrong must not silently become no correction at all
     */
    public static ModelImport besideModel(Path model) {
        Path directory = model.toAbsolutePath().getParent();
        Path file = directory == null ? null : directory.resolve(FILE_NAME);
        if (file == null || !Files.isRegularFile(file)) {
            return identity();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file));
            JsonNode translation = root.path("translationM");
            return new ModelImport(
                    (float) root.path("scaleToMetres").asDouble(1.0),
                    (float) root.path("yawDeg").asDouble(0.0),
                    new Vector3(
                            (float) translation.path("x").asDouble(0.0),
                            (float) translation.path("y").asDouble(0.0),
                            (float) translation.path("z").asDouble(0.0)));
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException(file + " is not readable: " + e.getMessage(), e));
        }
    }

    /** The correction as a single matrix. */
    public Matrix4 asMatrix() {
        return new Matrix4().translate(translationM).rotate(Vector3.Y, yawDeg).scl(scaleToMetres);
    }

    /**
     * Applies the correction to a model's geometry, in place.
     *
     * <p>In place because the alternative is a second copy of every vertex in the file, and because
     * a corrected model is the only one anything downstream should see: a caller holding both would
     * eventually measure one and render the other.
     */
    public void applyTo(GltfModel model) {
        if (isIdentity()) {
            return;
        }
        Matrix4 transform = asMatrix();
        Matrix3 normalMatrix = new Matrix3().set(transform).inv().transpose();
        Vector3 scratch = new Vector3();
        for (GltfMeshNode node : model.meshNodes()) {
            for (GltfPrimitive primitive : node.primitives()) {
                float[] positions = primitive.positions();
                for (int i = 0; i < positions.length; i += 3) {
                    scratch.set(positions[i], positions[i + 1], positions[i + 2])
                            .mul(transform);
                    positions[i] = scratch.x;
                    positions[i + 1] = scratch.y;
                    positions[i + 2] = scratch.z;
                }
                float[] normals = primitive.normals();
                if (normals == null) {
                    continue;
                }
                for (int i = 0; i < normals.length; i += 3) {
                    scratch.set(normals[i], normals[i + 1], normals[i + 2])
                            .mul(normalMatrix)
                            .nor();
                    normals[i] = scratch.x;
                    normals[i + 1] = scratch.y;
                    normals[i + 2] = scratch.z;
                }
            }
        }
    }
}
