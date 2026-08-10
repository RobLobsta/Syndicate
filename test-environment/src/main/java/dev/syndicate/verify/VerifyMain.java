/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import dev.syndicate.core.asset.GltfException;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfOptions;
import dev.syndicate.core.asset.GltfReader;
import dev.syndicate.model.AssetId;
import dev.syndicate.verify.asset.FractureManifest;
import dev.syndicate.verify.asset.GlbReader;
import dev.syndicate.verify.asset.MeshData;
import dev.syndicate.verify.check.Check;
import dev.syndicate.verify.check.CheckRunner;
import dev.syndicate.verify.check.ReportWriter;
import dev.syndicate.verify.check.Tolerances;
import dev.syndicate.verify.model.ModelImport;
import dev.syndicate.verify.model.ModelInspector;
import dev.syndicate.verify.model.ModelScene;
import dev.syndicate.verify.render.VisualScene;
import dev.syndicate.verify.vehicle.VehicleRun;
import dev.syndicate.verify.vehicle.VehicleScene;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the verification harness (docs/14_test_environment.md#D14-S5.1).
 *
 * <p>Runs the asset, physics, and destruction-progression checks against a processed asset in a
 * real Bullet world and emits the JSON report of D14-S4.4. Two modes: headless (D14-S5.13), which
 * is CI stage 5 and needs no display; and visual (D14-S5.11), which renders the same simulation and
 * can capture a frame to PNG.
 *
 * <p>Exit codes are the harness's own (D14-S4.2), deliberately distinct from the game's (D03-S4.4)
 * and the Blender tool's (D09-S4.3) — three programs, three code spaces, so a caller chaining them
 * never has to guess which one failed.
 */
public final class VerifyMain {

    private static final Logger LOG = LoggerFactory.getLogger(VerifyMain.class);

    // D14-R3 exit codes.
    private static final int OK = 0;
    private static final int ASSET_CHECK_FAILED = 10;
    private static final int PHYSICS_CHECK_FAILED = 11;
    private static final int PROGRESSION_CHECK_FAILED = 12;
    private static final int INPUT_NOT_FOUND = 20;
    private static final int MANIFEST_INVALID = 21;
    private static final int MESH_LOAD_FAILED = 22;
    private static final int HARNESS_ERROR = 30;

    private VerifyMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        VerifyOptions options;
        try {
            options = VerifyOptions.parse(args);
        } catch (VerifyOptions.UsageException e) {
            LOG.error("{}\n{}", e.getMessage(), VerifyOptions.usage());
            return HARNESS_ERROR;
        }

        if (options.isVehicleMode()) {
            return runVehicle(options);
        }
        return options.isModelMode() ? runModel(options) : runAsset(options);
    }

    /**
     * The source-art mode: check one glTF file, and optionally render it.
     *
     * <p>Deliberately not part of the asset path. That one starts from a manifest and a shard set,
     * which a model dropped into {@code art-source/} has neither of — and the questions worth asking
     * about a raw model (is it in metres, which way is up, do its textures exist) are the ones that
     * have to be answered before anything is processed at all (D08-S4.1).
     */
    private static int runModel(VerifyOptions options) {
        long started = System.currentTimeMillis();
        Path file = options.modelPath();
        if (!Files.isRegularFile(file)) {
            LOG.error("input not found: {}", file);
            return INPUT_NOT_FOUND;
        }

        ModelImport correction = ModelImport.besideModel(file);
        GltfModel model;
        try {
            model = GltfReader.read(file, GltfOptions.FULL);
            correction.applyTo(model);
        } catch (GltfException | UncheckedIOException e) {
            LOG.error("{}", e.getMessage());
            return MESH_LOAD_FAILED;
        }

        LOG.info(
                "inspecting {}: {} mesh nodes, {} triangles, {} materials{}",
                file.getFileName(),
                model.meshNodes().size(),
                model.triangleCount(),
                model.materials().size(),
                correction.isIdentity()
                        ? ""
                        : ", import x" + correction.scaleToMetres() + " yaw " + correction.yawDeg() + "°");

        ModelInspector inspector = new ModelInspector(model, file, correction);
        List<Check> checks = inspector.run();

        if (options.visual()) {
            try {
                renderModel(options, model, inspector.measurements());
            } catch (RuntimeException e) {
                LOG.error("visual mode failed", e);
                return HARNESS_ERROR;
            }
        }

        int exitCode = exitCodeFor(checks);
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("model", file.toString());
        target.put(
                "import",
                correction.isIdentity()
                        ? null
                        : file.toAbsolutePath()
                                .getParent()
                                .resolve(ModelImport.FILE_NAME)
                                .toString());
        Map<String, Object> report = ReportWriter.build(
                file.getFileName().toString(),
                target,
                options.visual() ? "visual" : "headless",
                options.seed(),
                checks,
                inspector.measurements(),
                new Tolerances(),
                exitCode,
                System.currentTimeMillis() - started);
        ReportWriter.write(report, options.reportPath());

        if (options.verbose()) {
            for (Check check : checks) {
                LOG.info(
                        "{} {} {} — {}",
                        check.status().json().toUpperCase(java.util.Locale.ROOT),
                        check.id(),
                        check.name(),
                        check.actual());
            }
        }
        LOG.info("{}", ReportWriter.oneLine(report, file.getFileName().toString()));
        LOG.info("report written to {}", options.reportPath());
        return exitCode;
    }

    /**
     * The vehicle mode: assemble a shipped car, drive it, take a wheel off, photograph all of it.
     *
     * <p>Not a check mode. Everything it would assert — that the wheels sit on the road, that they
     * roll through the distance travelled, that a wheel comes off and the car drives on — is already
     * asserted headlessly in {@code game-core}'s {@code RideHeightTest}, {@code WheelSpinTest} and
     * {@code WheelDetachTest}, which run in CI where no display exists. What this adds is the one
     * thing an assertion cannot give: a picture, from the same component state, that a person can
     * look at. The report carries the measurements taken at each captured moment so the frames and
     * the numbers can be compared afterwards.
     */
    private static int runVehicle(VerifyOptions options) {
        long started = System.currentTimeMillis();
        Path assetRoot = options.assetRoot();
        if (!Files.isDirectory(assetRoot.resolve("parts"))) {
            LOG.error("no shipped asset tree at {} (expected {}/parts)", assetRoot, assetRoot);
            return INPUT_NOT_FOUND;
        }

        AssetId vehicleTypeId = AssetId.of(options.vehicleTypeId());
        Map<String, Object> measurements;
        List<Path> captures;
        try (VehicleRun run = new VehicleRun(assetRoot, options.seed())) {
            VehicleScene scene =
                    new VehicleScene(run, vehicleTypeId, options.capturePath(), 1.0f, options.driveSeconds());
            Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
            config.setTitle("syndicate-verify — " + vehicleTypeId.value());
            config.setWindowedMode(1600, 900);
            config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
            config.useVsync(false);
            new Lwjgl3Application(scene, config);
            measurements = scene.measurements();
            captures = scene.captures();
        } catch (IllegalArgumentException e) {
            LOG.error("{}", e.getMessage());
            return INPUT_NOT_FOUND;
        } catch (RuntimeException e) {
            LOG.error("vehicle mode failed", e);
            return HARNESS_ERROR;
        }
        measurements.put("captures", captures.stream().map(Path::toString).toList());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("vehicleTypeId", vehicleTypeId.value());
        target.put("assets", assetRoot.toString());
        Map<String, Object> report = ReportWriter.build(
                vehicleTypeId.value(),
                target,
                "visual",
                options.seed(),
                List.of(),
                measurements,
                new Tolerances(),
                OK,
                System.currentTimeMillis() - started);
        ReportWriter.write(report, options.reportPath());
        LOG.info("{} frames written; report at {}", captures.size(), options.reportPath());
        return OK;
    }

    /** Renders a model in an LWJGL3 window, capturing a front and a rear three-quarter view. */
    private static void renderModel(VerifyOptions options, GltfModel model, Map<String, Object> measurements) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("syndicate-verify — " + model.source().getFileName());
        config.setWindowedMode(1600, 900);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
        config.useVsync(false);

        ModelScene scene = new ModelScene(model, options.capturePath());
        new Lwjgl3Application(scene, config);
        measurements.put(
                "captures", scene.captures().stream().map(Path::toString).toList());
    }

    private static int runAsset(VerifyOptions options) {
        long started = System.currentTimeMillis();
        Path assetDir = options.assetDir();
        Path manifestPath = assetDir.resolve("fracture_manifest.json");
        Path meshPath = assetDir.resolve("mesh.glb");
        Path shardsPath = assetDir.resolve("shards.glb");

        for (Path required : new Path[] {assetDir, manifestPath, meshPath, shardsPath}) {
            if (!Files.exists(required)) {
                LOG.error("input not found: {}", required);
                return INPUT_NOT_FOUND;
            }
        }

        FractureManifest manifest;
        try {
            manifest = FractureManifest.load(manifestPath);
        } catch (FractureManifest.ManifestException e) {
            LOG.error("{}", e.getMessage());
            return e.notFound ? INPUT_NOT_FOUND : MANIFEST_INVALID;
        }

        MeshData intactMesh;
        List<MeshData> shardMeshes;
        try {
            // The intact mesh file may also carry a collision proxy node; the largest mesh is the
            // part itself, and picking by volume rather than by index survives an exporter that
            // reorders nodes.
            List<MeshData> meshNodes = new ArrayList<>(GlbReader.read(meshPath));
            meshNodes.sort(
                    Comparator.comparingDouble((MeshData m) -> m.volumeM3()).reversed());
            intactMesh = meshNodes.get(0);

            shardMeshes = new ArrayList<>(GlbReader.read(shardsPath));
            shardMeshes.sort(Comparator.comparing(MeshData::name));
        } catch (GlbReader.AssetLoadException e) {
            LOG.error("{}", e.getMessage());
            return MESH_LOAD_FAILED;
        }

        LOG.info(
                "verifying {}: {} shards, {} kg, tool {}",
                manifest.partTypeId,
                manifest.shardCount,
                manifest.partMassKg,
                manifest.toolVersion);

        Tolerances tolerances = new Tolerances();
        CheckRunner runner = new CheckRunner(manifest, intactMesh, shardMeshes, tolerances, options.seed());

        List<Check> checks;
        try {
            checks = runner.run(options.categories());
        } catch (RuntimeException e) {
            LOG.error("harness error while running checks", e);
            return HARNESS_ERROR;
        }

        if (options.visual()) {
            try {
                renderVisual(options, manifest, intactMesh, shardMeshes, runner);
            } catch (RuntimeException e) {
                LOG.error("visual mode failed", e);
                return HARNESS_ERROR;
            }
        }

        int exitCode = exitCodeFor(checks);
        Map<String, Object> report = ReportWriter.build(
                manifest.partTypeId,
                assetDir,
                options.visual() ? "visual" : "headless",
                options.seed(),
                checks,
                runner.physicsData(),
                tolerances,
                exitCode,
                System.currentTimeMillis() - started);
        ReportWriter.write(report, options.reportPath());

        if (options.verbose()) {
            for (Check check : checks) {
                LOG.info(
                        "{} {} {} — {}",
                        check.status().json().toUpperCase(java.util.Locale.ROOT),
                        check.id(),
                        check.name(),
                        check.details());
            }
        }
        LOG.info("{}", ReportWriter.oneLine(report, manifest.partTypeId));
        LOG.info("report written to {}", options.reportPath());
        return exitCode;
    }

    /**
     * Runs the visual scene in an LWJGL3 window.
     *
     * <p>The window is created only here, never on the headless path, which is what keeps D14-S5.13
     * and G17 true: CI runs the same checks with no GL context in the process at all.
     */
    private static void renderVisual(
            VerifyOptions options,
            FractureManifest manifest,
            MeshData intactMesh,
            List<MeshData> shardMeshes,
            CheckRunner runner) {

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("syndicate-verify — " + manifest.partTypeId);
        config.setWindowedMode(1280, 720);
        config.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
        // vsync off so a capture run finishes at software-renderer speed rather than being paced
        // to a display that, under Xvfb, is not refreshing anything.
        config.useVsync(false);

        VisualScene scene = new VisualScene(
                manifest,
                intactMesh,
                shardMeshes,
                options.seed(),
                options.captureScatter(),
                options.captureTick(),
                options.capturePath());
        new Lwjgl3Application(scene, config);
        runner.physicsData().putAll(scene.captureData());
    }

    /**
     * D14-R4: the lowest-numbered failing category wins, because a failure early in the chain
     * usually causes the later ones. The report still lists every failure.
     */
    private static int exitCodeFor(List<Check> checks) {
        boolean asset = false;
        boolean physics = false;
        boolean progression = false;
        for (Check check : checks) {
            if (!check.isBlocking()) {
                continue;
            }
            switch (check.category()) {
                case ASSET -> asset = true;
                case PHYSICS -> physics = true;
                case PROGRESSION -> progression = true;
                default -> {}
            }
        }
        if (asset) {
            return ASSET_CHECK_FAILED;
        }
        if (physics) {
            return PHYSICS_CHECK_FAILED;
        }
        if (progression) {
            return PROGRESSION_CHECK_FAILED;
        }
        return OK;
    }
}
