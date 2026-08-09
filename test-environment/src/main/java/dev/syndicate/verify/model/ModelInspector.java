/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.model;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.GltfImage;
import dev.syndicate.core.asset.GltfMeshNode;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfPrimitive;
import dev.syndicate.core.asset.GltfReader;
import dev.syndicate.verify.check.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks a piece of vehicle source art before anything is built on it
 * (docs/14_test_environment.md#D14-S5.3, docs/08_asset_pipeline.md#D08-S4.1).
 *
 * <p>The Blender tool's checks (D09-S5.7) and the harness's asset checks (D14-S5.4) both start from
 * a *processed* part: a mesh, a manifest, shards. Nothing checked the file before that, and the two
 * cars in {@code art-source/vehicles/} are exactly why one should — between them they arrived a
 * hundred times too small, facing the wrong way, and rigged to a skeleton that does nothing. Every
 * one of those is invisible until something downstream produces a wrong number.
 *
 * <p>So these checks answer the questions that decide whether a model can become a part at all: is
 * it there, is it finite, is it in metres, is it the right way up, does its origin mean anything,
 * and are the files it names actually beside it. The geometry is measured after
 * {@link ModelImport} has been applied, which is what makes the correction in {@code import.json}
 * something the harness confirms rather than something a comment claims.
 *
 * <p>Ids are {@code MODEL-nnn}, permanent and never reused (D14-R6).
 */
public final class ModelInspector {

    /**
     * The length a road vehicle plausibly has, in metres.
     *
     * <p>Wide on purpose: the check is not "is this the right car", it is "is this in metres at
     * all". The two failures it exists to catch are a centimetre model (0.05 m long) and an
     * inch-or-unit model (180 long), and both miss this window by two orders of magnitude.
     */
    private static final float MIN_VEHICLE_LENGTH_M = 2.5f;

    private static final float MAX_VEHICLE_LENGTH_M = 7.0f;

    /** How far a model's lowest point may sit from y=0 before the origin stops meaning "ground". */
    private static final float GROUND_TOLERANCE_M = 0.02f;

    /** D08-R2's visual mesh budget, per part. */
    private static final int MAX_PART_TRIANGLES = 8000;

    /** Above this share of degenerate triangles the mesh is not something to build a hull from. */
    private static final double MAX_DEGENERATE_FRACTION = 0.01;

    private final GltfModel model;
    private final Path file;
    private final ModelImport correction;
    private final Map<String, Object> measurements = new LinkedHashMap<>();

    public ModelInspector(GltfModel model, Path file, ModelImport correction) {
        this.model = model;
        this.file = file;
        this.correction = correction;
    }

    /** Everything measured during {@link #run()}, for the report's {@code physics_data} block. */
    public Map<String, Object> measurements() {
        return measurements;
    }

    /** Runs every check, in id order. */
    public List<Check> run() {
        List<Check> checks = new ArrayList<>();
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        boolean hasGeometry = model.bounds(min, max);
        Vector3 size = new Vector3(max).sub(min);

        recordMeasurements(min, max, size);

        checks.add(geometryPresent(hasGeometry));
        checks.add(coordinatesFinite());
        checks.add(externalResourcesResolve());
        if (!hasGeometry) {
            // Every remaining check measures the bounding box, and there is none.
            return checks;
        }
        checks.add(scalePlausible(size));
        checks.add(upAxisIsY(size));
        checks.add(longitudinalAxisIsZ(size));
        checks.add(originSitsOnTheGround(min));
        checks.add(noSkinning());
        checks.add(degenerateTriangles());
        checks.add(triangleBudget());
        return checks;
    }

    private void recordMeasurements(Vector3 min, Vector3 max, Vector3 size) {
        measurements.put("source", file.toString());
        measurements.put("generator", model.generator());
        measurements.put("importScaleToMetres", correction.scaleToMetres());
        measurements.put("importYawDeg", correction.yawDeg());
        measurements.put("meshNodes", model.meshNodes().size());
        measurements.put("nodes", model.nodeCount());
        measurements.put("vertices", model.vertexCount());
        measurements.put("triangles", model.triangleCount());
        measurements.put("materials", model.materials().size());
        measurements.put("images", model.images().size());
        measurements.put("boundsMinM", vector(min));
        measurements.put("boundsMaxM", vector(max));
        measurements.put("lengthM", round(size.z));
        measurements.put("widthM", round(size.x));
        measurements.put("heightM", round(size.y));
    }

    private static Map<String, Object> vector(Vector3 v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", round(v.x));
        out.put("y", round(v.y));
        out.put("z", round(v.z));
        return out;
    }

    private static double round(float value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ------------------------------------------------------------- the checks

    private Check geometryPresent(boolean hasGeometry) {
        int triangles = model.triangleCount();
        return check(
                "MODEL-001",
                "geometry present",
                hasGeometry && triangles > 0,
                "at least one triangle",
                model.meshNodes().size() + " mesh nodes, " + triangles + " triangles",
                "A document that parses to nothing is the failure mode a required extension or an"
                        + " unread compression scheme produces (D08-R14).");
    }

    private Check coordinatesFinite() {
        long nonFinite = 0;
        for (GltfMeshNode node : model.meshNodes()) {
            for (GltfPrimitive primitive : node.primitives()) {
                for (float value : primitive.positions()) {
                    if (!Float.isFinite(value)) {
                        nonFinite++;
                    }
                }
            }
        }
        measurements.put("nonFiniteCoordinates", nonFinite);
        return check(
                "MODEL-002",
                "coordinates are finite",
                nonFinite == 0,
                "0 non-finite coordinates (D00-R13)",
                nonFinite + " non-finite coordinates",
                "One NaN vertex makes a convex hull, an inertia tensor and every contact derived from"
                        + " them NaN, and Bullet does not complain.");
    }

    private Check externalResourcesResolve() {
        Path directory = file.toAbsolutePath().getParent();
        List<String> missing = new ArrayList<>();
        for (GltfImage image : model.images()) {
            if (image.uri() == null || directory == null) {
                continue;
            }
            if (!Files.isRegularFile(directory.resolve(image.uri()))) {
                missing.add(image.uri());
            }
        }
        measurements.put("missingImages", missing);
        return check(
                "MODEL-003",
                "external resources resolve",
                missing.isEmpty(),
                model.images().size() + " images present beside the document",
                missing.isEmpty() ? "all present" : missing.size() + " missing: " + missing,
                "A glTF that names its textures by URI is only as complete as the directory around"
                        + " it, and a zip that lost its textures/ still parses.");
    }

    private Check scalePlausible(Vector3 size) {
        float longest = Math.max(size.x, Math.max(size.y, size.z));
        boolean ok = longest >= MIN_VEHICLE_LENGTH_M && longest <= MAX_VEHICLE_LENGTH_M;
        return measured(
                "MODEL-004",
                "scale is metres",
                ok ? Check.Status.PASS : Check.Status.FAIL,
                MIN_VEHICLE_LENGTH_M + "–" + MAX_VEHICLE_LENGTH_M + " m longest extent",
                String.format(Locale.ROOT, "%.4f m", longest),
                (double) longest,
                ok
                        ? "D00-R11: one unit is one metre."
                        : "D00-R11: one unit is one metre. Set scaleToMetres in " + ModelImport.FILE_NAME
                                + " — the factor needed here is about "
                                + String.format(Locale.ROOT, "%.4g", 4.6f / longest) + ".");
    }

    private Check upAxisIsY(Vector3 size) {
        boolean ok = size.y < size.x && size.y < size.z;
        return check(
                "MODEL-005",
                "up axis is Y",
                ok,
                "height is the smallest extent (D00-R16)",
                String.format(Locale.ROOT, "x %.3f, y %.3f, z %.3f m", size.x, size.y, size.z),
                "A Z-up export is taller than it is long. Blender is Z-up natively, so this is what a"
                        + " missing +Y-up export option looks like (D08-R13).");
    }

    private Check longitudinalAxisIsZ(Vector3 size) {
        boolean ok = size.z > size.x;
        return check(
                "MODEL-006",
                "long axis is Z",
                ok,
                "length along Z exceeds width along X (D00-R16)",
                String.format(Locale.ROOT, "z %.3f m, x %.3f m", size.z, size.x),
                "Which end is the front is not decidable from geometry — check the render — but a car"
                        + " longer across X than along Z is turned ninety degrees, not one-eighty.");
    }

    private Check originSitsOnTheGround(Vector3 min) {
        boolean ok = Math.abs(min.y) <= GROUND_TOLERANCE_M;
        return measured(
                "MODEL-007",
                "origin sits on the ground plane",
                ok ? Check.Status.PASS : Check.Status.WARNING,
                "lowest point within " + GROUND_TOLERANCE_M + " m of y=0",
                String.format(Locale.ROOT, "%.4f m", min.y),
                (double) min.y,
                "D08-R2 puts a part's origin at its attachment point, and a chassis attaches at the"
                        + " wheel-centre plane — so a model whose origin floats is one whose slot"
                        + " positions all inherit the same offset. Correct it with translationM in "
                        + ModelImport.FILE_NAME + ".");
    }

    private Check noSkinning() {
        int skinned = GltfReader.skinnedNodeCount(file);
        measurements.put("skinnedMeshNodes", skinned);
        return measured(
                "MODEL-008",
                "no skinned geometry",
                skinned == 0 ? Check.Status.PASS : Check.Status.WARNING,
                "0 skinned mesh nodes",
                skinned + " skinned mesh nodes",
                (double) skinned,
                "The reader ignores joint weights and uses each node's own transform. That is exact"
                        + " for art an exporter rigged without needing to, and wrong for art that"
                        + " genuinely deforms — so a non-zero count is worth an eye on the render.");
    }

    private Check degenerateTriangles() {
        long degenerate = 0;
        long total = 0;
        Vector3 a = new Vector3();
        Vector3 b = new Vector3();
        Vector3 c = new Vector3();
        Vector3 edge = new Vector3();
        for (GltfMeshNode node : model.meshNodes()) {
            for (GltfPrimitive primitive : node.primitives()) {
                int[] indices = primitive.indices();
                for (int t = 0; t + 2 < indices.length; t += 3) {
                    total++;
                    primitive.vertex(indices[t], a);
                    primitive.vertex(indices[t + 1], b);
                    primitive.vertex(indices[t + 2], c);
                    // Twice the triangle's area; a hull vertex that only appears in zero-area faces
                    // contributes nothing and a normal derived from one is undefined.
                    if (b.sub(a).crs(edge.set(c).sub(a)).len2() <= 1e-16f) {
                        degenerate++;
                    }
                }
            }
        }
        double fraction = total == 0 ? 0 : (double) degenerate / total;
        measurements.put("degenerateTriangles", degenerate);
        return measured(
                "MODEL-009",
                "degenerate triangles are rare",
                fraction <= MAX_DEGENERATE_FRACTION ? Check.Status.PASS : Check.Status.WARNING,
                "at most " + (MAX_DEGENERATE_FRACTION * 100) + "% zero-area",
                String.format(Locale.ROOT, "%d of %d (%.3f%%)", degenerate, total, fraction * 100),
                fraction,
                "Zero-area faces survive a decimation pass and break normal generation.");
    }

    private Check triangleBudget() {
        int triangles = model.triangleCount();
        return measured(
                "MODEL-010",
                "triangle budget",
                triangles <= MAX_PART_TRIANGLES ? Check.Status.PASS : Check.Status.WARNING,
                "≤ " + MAX_PART_TRIANGLES + " triangles per part (D08-R2)",
                triangles + " triangles",
                (double) triangles,
                "A whole-vehicle source model is expected to exceed this: D08-R2's budget is per"
                        + " part, and this file is not split into parts yet. It becomes a failure"
                        + " once it is.");
    }

    // ----------------------------------------------------------------- helpers

    private static Check check(String id, String name, boolean pass, String expected, String actual, String details) {
        return new Check(
                id,
                name,
                Check.Category.ASSET,
                pass ? Check.Status.PASS : Check.Status.FAIL,
                expected,
                actual,
                null,
                null,
                null,
                null,
                details,
                0L);
    }

    private static Check measured(
            String id,
            String name,
            Check.Status status,
            String expected,
            String actual,
            Double actualValue,
            String details) {
        return new Check(
                id, name, Check.Category.ASSET, status, expected, actual, null, actualValue, null, null, details, 0L);
    }
}
