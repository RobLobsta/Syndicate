/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two part buckets of docs/08_asset_pipeline.md#D08-R14b.
 *
 * <p>Four consumers resolve a part's directory — the runtime loader, the asset gate, the client's
 * model cache and the verification harness — and none of them may depend on another. This class is
 * the one answer all four share, so what is worth asserting is that it gives the same answer in the
 * same order every time, and that it can say which vehicle owns what.
 */
@Tag("unit")
class AssetPathsTest {

    /** Both buckets are walked, shared first, each in ascending id order (G3). */
    @Test
    void partDirectoriesWalksSharedThenVehicleOwned(@TempDir Path root) throws IOException {
        tree(root);

        assertThat(AssetPaths.partDirectories(root).stream()
                        .map(path -> path.getFileName().toString())
                        .toList())
                .containsExactly(
                        "module_radar_01",
                        "weapon_autocannon_01",
                        "chassis_eclipse_01",
                        "panel_eclipse_door_l_01",
                        "chassis_stampede_01");
    }

    /** A part resolves from whichever bucket holds it, and null when nothing does. */
    @Test
    void partDirectoryResolvesFromEitherBucket(@TempDir Path root) throws IOException {
        tree(root);

        assertThat(AssetPaths.partDirectory(root, "weapon_autocannon_01"))
                .isEqualTo(root.resolve("parts").resolve("weapon_autocannon_01"));
        assertThat(AssetPaths.partDirectory(root, "panel_eclipse_door_l_01"))
                .isEqualTo(
                        AssetPaths.vehiclePartsRoot(root, "vehicle_eclipse_01").resolve("panel_eclipse_door_l_01"));
        assertThat(AssetPaths.partDirectory(root, "never_authored_01")).isNull();
    }

    /**
     * Ownership comes from the path, because the path <em>is</em> the ownership statement.
     *
     * <p>A part's own {@code part.json} never names a vehicle, which is what lets the same schema
     * serve both buckets — and what makes A315 a rule the gate can check by walking the tree.
     */
    @Test
    void ownershipIsDerivedFromTheDirectory(@TempDir Path root) throws IOException {
        tree(root);

        Path shared = root.resolve("parts").resolve("weapon_autocannon_01");
        Path owned = AssetPaths.vehiclePartsRoot(root, "vehicle_eclipse_01").resolve("chassis_eclipse_01");

        assertThat(AssetPaths.isVehicleOwned(root, shared)).isFalse();
        assertThat(AssetPaths.owningVehicle(root, shared)).isNull();
        assertThat(AssetPaths.isVehicleOwned(root, owned)).isTrue();
        assertThat(AssetPaths.owningVehicle(root, owned)).isEqualTo("vehicle_eclipse_01");
    }

    /** The per-vehicle view is keyed by vehicle and ordered within each. */
    @Test
    void partsAreGroupedByTheVehicleThatOwnsThem(@TempDir Path root) throws IOException {
        tree(root);

        Map<String, List<Path>> byVehicle = AssetPaths.partDirectoriesByVehicle(root);
        assertThat(byVehicle.keySet()).containsExactly("vehicle_eclipse_01", "vehicle_stampede_01");
        assertThat(byVehicle.get("vehicle_eclipse_01"))
                .extracting(path -> path.getFileName().toString())
                .containsExactly("chassis_eclipse_01", "panel_eclipse_door_l_01");
    }

    /** An asset root with neither bucket is empty rather than an exception (G18). */
    @Test
    void anEmptyRootYieldsNothingRatherThanFailing(@TempDir Path root) {
        assertThat(AssetPaths.partDirectories(root)).isEmpty();
        assertThat(AssetPaths.vehicleDirectories(root)).isEmpty();
        assertThat(AssetPaths.partDirectory(root, "anything_01")).isNull();
    }

    private static void tree(Path root) throws IOException {
        for (String shared : new String[] {"weapon_autocannon_01", "module_radar_01"}) {
            Files.createDirectories(AssetPaths.sharedPartsRoot(root).resolve(shared));
        }
        for (String part : new String[] {"chassis_eclipse_01", "panel_eclipse_door_l_01"}) {
            Files.createDirectories(
                    AssetPaths.vehiclePartsRoot(root, "vehicle_eclipse_01").resolve(part));
        }
        Files.createDirectories(
                AssetPaths.vehiclePartsRoot(root, "vehicle_stampede_01").resolve("chassis_stampede_01"));
        // A loose file in a bucket is not a part and must not be walked as one — `manifest.json`
        // lives exactly here (D08-R14c).
        Files.writeString(
                AssetPaths.vehiclePartsRoot(root, "vehicle_eclipse_01").resolve(AssetPaths.PARTS_MANIFEST_FILE), "{}");
    }
}
