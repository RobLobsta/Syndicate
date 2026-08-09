/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfOptions;
import dev.syndicate.core.asset.GltfReader;
import dev.syndicate.verify.check.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The two supplied vehicle models still measure what {@code SOURCE.md} says they measure
 * (docs/08_asset_pipeline.md#D08-S4.1).
 *
 * <p>Every number in each vehicle's {@code SOURCE.md} was produced by this reader, and the
 * import corrections in {@code import.json} were chosen to make them come out. Nothing else in the
 * build would notice if a reader change quietly moved them — a scale factor that drifted 1% still
 * parses, still renders, and puts a car on the road at the wrong size. This is what notices.
 *
 * <p>Skips rather than fails when {@code art-source/} is absent, the way {@code ShippedContent}'s
 * tests do: the art is checked in, but a build in a tree without it should report that, not a
 * failure that reads like a regression.
 */
@Tag("integration")
class SuppliedVehicleArtTest {

    private static final Path ART_SOURCE =
            repositoryRoot().resolve("art-source").resolve("vehicles");

    /** Metres. Wide enough to survive a reader refactor, tight enough to catch a wrong correction. */
    private static final float DIMENSION_TOLERANCE_M = 0.01f;

    @Test
    void theEclipseMeasuresItsReferenceCar() {
        GltfModel model = corrected("eclipse");
        Vector3 size = sizeOf(model);

        // Maserati MC20: 4669 mm long, 2700 mm wheelbase. SOURCE.md records the full table.
        assertThat(size.z).as("Eclipse length").isCloseTo(4.6682f, within(DIMENSION_TOLERANCE_M));
        assertThat(size.x).as("Eclipse width over mirrors").isCloseTo(2.1776f, within(DIMENSION_TOLERANCE_M));
        assertThat(size.y).as("Eclipse height").isCloseTo(1.2365f, within(DIMENSION_TOLERANCE_M));
        assertThat(model.triangleCount()).isEqualTo(283192);
    }

    @Test
    void theStampedeMeasuresItsReferenceCar() {
        GltfModel model = corrected("stampede");
        Vector3 size = sizeOf(model);

        // Ford Mustang GTD: 4810 mm body, 2720 mm wheelbase; the extra length here is the rear wing.
        assertThat(size.z).as("Stampede length over the wing").isCloseTo(4.9196f, within(DIMENSION_TOLERANCE_M));
        assertThat(size.x).as("Stampede width over mirrors").isCloseTo(2.0847f, within(DIMENSION_TOLERANCE_M));
        assertThat(size.y).as("Stampede height over the wing").isCloseTo(1.3749f, within(DIMENSION_TOLERANCE_M));
        assertThat(model.triangleCount()).isEqualTo(234057);
    }

    /** Neither model has a blocking finding: both are usable source art as they stand. */
    @Test
    void bothModelsPassEveryBlockingCheck() {
        for (String vehicle : List.of("eclipse", "stampede")) {
            Path file = modelPath(vehicle);
            assumeTrue(Files.isRegularFile(file), "art-source is not present");
            ModelImport correction = ModelImport.besideModel(file);
            GltfModel model = GltfReader.read(file, GltfOptions.FULL);
            correction.applyTo(model);

            List<Check> checks = new ModelInspector(model, file, correction).run();
            assertThat(checks).as("%s checks", vehicle).hasSize(10);
            assertThat(checks.stream().filter(Check::isBlocking).toList())
                    .as("%s blocking findings", vehicle)
                    .isEmpty();
        }
    }

    /**
     * Every texture both documents name is on disk.
     *
     * <p>A glTF that names its images by URI is only as complete as the directory around it, and the
     * commonest way to lose one is a commit that adds the model without the folder beside it.
     */
    @Test
    void everyTextureBothModelsNameIsPresent() {
        for (String vehicle : List.of("eclipse", "stampede")) {
            Path file = modelPath(vehicle);
            assumeTrue(Files.isRegularFile(file), "art-source is not present");
            GltfModel model = GltfReader.read(file, GltfOptions.FULL);
            for (var image : model.images()) {
                if (image.uri() != null) {
                    assertThat(file.getParent().resolve(image.uri()))
                            .as("%s texture %s", vehicle, image.uri())
                            .exists();
                }
            }
        }
    }

    private static GltfModel corrected(String vehicle) {
        Path file = modelPath(vehicle);
        assumeTrue(Files.isRegularFile(file), "art-source is not present");
        ModelImport correction = ModelImport.besideModel(file);
        GltfModel model = GltfReader.read(file, GltfOptions.GEOMETRY);
        correction.applyTo(model);
        return model;
    }

    private static Path modelPath(String vehicle) {
        return ART_SOURCE.resolve(vehicle).resolve("scene.gltf");
    }

    private static Vector3 sizeOf(GltfModel model) {
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        assertThat(model.bounds(min, max)).isTrue();
        return new Vector3(max).sub(min);
    }

    /** The repository root, found by walking up from the working directory to the settings file. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        return directory == null ? Path.of("").toAbsolutePath() : directory;
    }
}
