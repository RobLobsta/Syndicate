/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.asset.ValidationIssue;
import dev.syndicate.model.AssetId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads the real {@code assets/} tree with stand-in collision geometry, for the tests that check the
 * shipped vehicles (docs/08_asset_pipeline.md#D08-S5.3).
 *
 * <p>{@code game-core} has no headless glTF reader yet (DEV-010), so no part's {@code mesh.glb} can
 * be turned into a hull and every shipped part would fail to load with A503. This supplies a box
 * instead, sized from the vehicle's own published dimensions and tyre codes — a chassis the size of
 * the real car's body, and a wheel the size of the real car's tyre. It is a fixture standing in for
 * art, not a fallback: nothing outside tests uses it, and when the reader lands it goes away.
 *
 * <p>The boxes are not centred on the origin. A real car's body sits mostly <em>above</em> the wheel
 * centres and hangs about a wheel radius minus its ground clearance below them, and a hull that
 * ignored that would either float or drag its underside along the road.
 */
public final class ShippedContent {

    /** Where the shipped content lives, relative to the repository root. */
    public static final Path ASSET_ROOT = repositoryRoot().resolve("assets");

    /** Ground clearance under the chassis hull, metres — the same for both shipped vehicles. */
    private static final float GROUND_CLEARANCE_M = 0.10f;

    /** Half-extents of each chassis hull, keyed by part type id. */
    private static final Map<String, Vector3> CHASSIS_BODY = Map.of(
            // 4669 x 1965 x 1224 mm (published), as half-extents about the wheel-centre plane.
            "chassis_apex_gt_01", new Vector3(0.9825f, 0.562f, 2.3345f),
            // Mustang S650 bodyshell, lowered to GT3 ride height.
            "chassis_stampede_gt3_01", new Vector3(0.960f, 0.570f, 2.405f));

    /** Where the centre of each chassis hull sits above the wheel-centre plane, metres. */
    private static final Map<String, Float> CHASSIS_CENTRE_Y =
            Map.of("chassis_apex_gt_01", 0.322f, "chassis_stampede_gt3_01", 0.330f);

    /** Tyre section width and radius per wheel part type, metres. */
    private static final Map<String, Vector3> WHEEL_SIZE = Map.of(
            "wheel_apex_front_01", new Vector3(0.245f, 0.340f, 0f),
            "wheel_apex_rear_01", new Vector3(0.305f, 0.346f, 0f),
            "wheel_stampede_front_01", new Vector3(0.325f, 0.340f, 0f),
            "wheel_stampede_rear_01", new Vector3(0.345f, 0.350f, 0f));

    private ShippedContent() {
        throw new AssertionError("no instances");
    }

    /** True when the shipped asset tree is present, so a test can skip rather than fail without it. */
    public static boolean isPresent() {
        return Files.isDirectory(ASSET_ROOT.resolve("parts"));
    }

    /** Loads every shipped material, part and assembly with stand-in hulls. */
    public static InMemoryAssetIndex load() {
        return loader().loadFrom(ASSET_ROOT);
    }

    /** A loader over the shipped tree, for a test that wants to inspect its findings. */
    public static AssetLoader loader() {
        return new AssetLoader(ShippedContent::meshFor);
    }

    /** Everything the shipped tree reports at error severity, which should be nothing. */
    public static List<ValidationIssue> blockingIssues() {
        AssetLoader loader = loader();
        loader.loadFrom(ASSET_ROOT);
        return loader.blockingIssues();
    }

    /** The stand-in hull for a part, or null when the part is not one of the shipped vehicles'. */
    static MeshData meshFor(AssetId partTypeId, String collisionSourceRef, Path partDirectory) {
        String id = partTypeId.value();
        Vector3 chassis = CHASSIS_BODY.get(id);
        if (chassis != null) {
            return box(chassis.x, chassis.y, chassis.z, CHASSIS_CENTRE_Y.get(id));
        }
        Vector3 wheel = WHEEL_SIZE.get(id);
        if (wheel != null) {
            // The axle runs along X, so the silhouette is the YZ extent and the radius comes off it
            // (DEC-022). Half the section width on X.
            return box(wheel.x * 0.5f, wheel.y, wheel.y, 0f);
        }
        return null;
    }

    /** A box's eight corners: the smallest mesh whose convex hull has the box's volume. */
    private static MeshData box(float halfX, float halfY, float halfZ, float centreY) {
        float lo = centreY - halfY;
        float hi = centreY + halfY;
        return new MeshData(new float[] {
            -halfX, lo, -halfZ, halfX, lo, -halfZ, -halfX, hi, -halfZ, halfX, hi, -halfZ, -halfX, lo, halfZ, halfX, lo,
            halfZ, -halfX, hi, halfZ, halfX, hi, halfZ
        });
    }

    /**
     * The repository root, found by walking up from the working directory until {@code assets/} and
     * {@code docs/} sit side by side.
     *
     * <p>Gradle runs tests with the module directory as the working directory, and a hard-coded
     * {@code ../assets} breaks the moment anyone runs a test from somewhere else.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("assets")) && Files.isDirectory(candidate.resolve("docs"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no repository root above " + Path.of("").toAbsolutePath());
    }

    /** Ground clearance, exposed so a test can explain a hull that sits where it does. */
    public static float groundClearanceM() {
        return GROUND_CLEARANCE_M;
    }
}
