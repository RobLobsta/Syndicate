/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.asset.AssetLoader;
import dev.syndicate.core.asset.GltfCollisionMeshSource;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.ValidationIssue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads the real {@code assets/} tree, with the real collision geometry
 * (docs/08_asset_pipeline.md#D08-S5.3).
 *
 * <p>It used to supply stand-in boxes sized from each car's published dimensions, because the
 * supplied art was one model per whole vehicle and no part had a {@code mesh.glb} of its own. The
 * dissection tool (DEC-042) produced those meshes, so this now reads them through the same
 * {@code GltfCollisionMeshSource} the dedicated server uses — which is the point: a calibration
 * test that ran on invented geometry could only ever prove the test self-consistent.
 *
 * <p>The change is not cosmetic. The hulls are the shapes of the actual cars rather than boxes
 * around them, and the wheel radii come off the tyres rather than off a tyre code, so the figures
 * this fixture produces moved when it landed. That is recorded in DEC-043 rather than absorbed.
 */
public final class ShippedContent {

    /** Where the shipped content lives, relative to the repository root. */
    public static final Path ASSET_ROOT = repositoryRoot().resolve("assets");

    /** Ground clearance under the chassis hull, metres — the same for both shipped vehicles. */
    private static final float GROUND_CLEARANCE_M = 0.10f;

    private ShippedContent() {
        throw new AssertionError("no instances");
    }

    /** True when the shipped asset tree is present, so a test can skip rather than fail without it. */
    public static boolean isPresent() {
        return Files.isDirectory(ASSET_ROOT.resolve("parts"));
    }

    /** Loads every shipped material, part and assembly, with the real collision meshes. */
    public static InMemoryAssetIndex load() {
        return loader().loadFrom(ASSET_ROOT);
    }

    /** A loader over the shipped tree, for a test that wants to inspect its findings. */
    public static AssetLoader loader() {
        return new AssetLoader(new GltfCollisionMeshSource());
    }

    /** Everything the shipped tree reports at error severity, which should be nothing. */
    public static List<ValidationIssue> blockingIssues() {
        AssetLoader loader = loader();
        loader.loadFrom(ASSET_ROOT);
        return loader.blockingIssues();
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
