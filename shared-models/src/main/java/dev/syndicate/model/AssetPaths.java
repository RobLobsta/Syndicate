/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where a part lives (docs/08_asset_pipeline.md#D08-S4.6).
 *
 * <p>A part is owned by exactly one of two places, and which one it is says what the part is for:
 *
 * <ul>
 *   <li>{@code assets/vehicles/<vehicleTypeId>/parts/<partTypeId>/} — <b>vehicle-owned</b>. A
 *       chassis, a door, a wheel, a bonnet: geometry cut from one car's art, fitted to that car's
 *       slot graph, and meaningless on any other. Only that vehicle's assembly may reference it.
 *   <li>{@code assets/parts/<partTypeId>/} — <b>shared</b>. A weapon, a utility module, a universal
 *       accessory: authored once against a hardpoint and bolted onto whatever will take it. Any
 *       assembly may reference it.
 * </ul>
 *
 * <p>The split is the reason this class exists rather than a {@code resolve("parts")} at four call
 * sites. Before it, every part was shared whether it made sense or not, and two cars whose art
 * happened to produce a {@code panel_door_l_01} each would have collided in one flat directory.
 *
 * <p>Pure path arithmetic, in {@code shared-models} because the runtime loader (D08-S5.3), the
 * validating pipeline (DEC-041), the client's model cache and the verification harness all need the
 * same answer and no two of them may depend on each other.
 */
public final class AssetPaths {

    /** The directory under an asset root holding shared, cross-vehicle parts. */
    public static final String SHARED_PARTS_DIR = "parts";

    /** The directory under an asset root holding one directory per vehicle. */
    public static final String VEHICLES_DIR = "vehicles";

    /** The directory holding one subdirectory per structure (D16-R18). */
    public static final String STRUCTURES_DIR = "structures";

    /** The directory under a vehicle's own directory holding the parts only it uses. */
    public static final String VEHICLE_PARTS_DIR = "parts";

    /** The manifest a vehicle's part set is described by (D08-R14b). */
    public static final String PARTS_MANIFEST_FILE = "manifest.json";

    private AssetPaths() {}

    /** {@code assets/parts} — the shared library root, whether or not it exists. */
    public static Path sharedPartsRoot(Path assetRoot) {
        return assetRoot.resolve(SHARED_PARTS_DIR);
    }

    /** {@code assets/vehicles} — one directory per vehicle, whether or not it exists. */
    public static Path vehiclesRoot(Path assetRoot) {
        return assetRoot.resolve(VEHICLES_DIR);
    }

    /** {@code assets/vehicles/<vehicleTypeId>/parts} — that vehicle's own parts. */
    public static Path vehiclePartsRoot(Path assetRoot, String vehicleTypeId) {
        return vehiclesRoot(assetRoot).resolve(vehicleTypeId).resolve(VEHICLE_PARTS_DIR);
    }

    /** Every vehicle directory under the asset root, in ascending id order (G3). */
    public static List<Path> structureDirectories(Path assetRoot) {
        return childDirectories(structuresRoot(assetRoot));
    }

    /** {@code assets/structures} — one directory per structure (D16-R18). */
    public static Path structuresRoot(Path assetRoot) {
        return assetRoot.resolve(STRUCTURES_DIR);
    }

    /** {@code assets/structures/<structureId>/parts} — a structure owns its parts, as a vehicle does (DEC-075). */
    public static Path structurePartsRoot(Path assetRoot, String structureId) {
        return structuresRoot(assetRoot).resolve(structureId).resolve(VEHICLE_PARTS_DIR);
    }

    public static List<Path> vehicleDirectories(Path assetRoot) {
        return childDirectories(vehiclesRoot(assetRoot));
    }

    /**
     * Every part directory under the asset root, shared first and then vehicle-owned (G3).
     *
     * <p>Shared first because a shared part may not reference a vehicle and a vehicle's assembly may
     * reference a shared part, so loading in this order means every reference resolves against
     * something already read. Within each bucket, ascending id order.
     */
    public static List<Path> partDirectories(Path assetRoot) {
        List<Path> directories = new ArrayList<>(childDirectories(sharedPartsRoot(assetRoot)));
        for (Path vehicle : vehicleDirectories(assetRoot)) {
            directories.addAll(childDirectories(vehicle.resolve(VEHICLE_PARTS_DIR)));
        }
        // Structures own their parts for the reason vehicles do (DEC-075): a building's third floor
        // is not a component anybody else fits, and putting it in the shared bucket would make it
        // look like one.
        for (Path structure : structureDirectories(assetRoot)) {
            directories.addAll(childDirectories(structure.resolve(VEHICLE_PARTS_DIR)));
        }
        return List.copyOf(directories);
    }

    /**
     * Every vehicle-owned part directory, keyed by the {@code vehicleTypeId} that owns it.
     *
     * <p>Iteration order is ascending vehicle id, then ascending part id, so a validator reporting
     * an ownership violation reports it in the same order on every machine.
     */
    public static Map<String, List<Path>> partDirectoriesByVehicle(Path assetRoot) {
        Map<String, List<Path>> owned = new LinkedHashMap<>();
        for (Path vehicle : vehicleDirectories(assetRoot)) {
            owned.put(vehicle.getFileName().toString(), childDirectories(vehicle.resolve(VEHICLE_PARTS_DIR)));
        }
        return owned;
    }

    /**
     * The directory holding {@code partTypeId}, or null when nothing does.
     *
     * <p>Shared is searched first, so a shared part and a vehicle-owned part with the same id
     * resolve to the shared one everywhere rather than to whichever the caller happened to walk
     * first. The pipeline reports that collision as A106 rather than leaving it to be discovered as
     * a car with somebody else's door on it.
     */
    public static Path partDirectory(Path assetRoot, String partTypeId) {
        Path shared = sharedPartsRoot(assetRoot).resolve(partTypeId);
        if (Files.isDirectory(shared)) {
            return shared;
        }
        for (Path structure : structureDirectories(assetRoot)) {
            Path owned = structure.resolve(VEHICLE_PARTS_DIR).resolve(partTypeId);
            if (Files.isDirectory(owned)) {
                return owned;
            }
        }
        for (Path vehicle : vehicleDirectories(assetRoot)) {
            Path owned = vehicle.resolve(VEHICLE_PARTS_DIR).resolve(partTypeId);
            if (Files.isDirectory(owned)) {
                return owned;
            }
        }
        return null;
    }

    /** True when {@code partDirectory} sits under some vehicle rather than in the shared library. */
    public static boolean isVehicleOwned(Path assetRoot, Path partDirectory) {
        Path shared = sharedPartsRoot(assetRoot);
        Path parent = partDirectory.getParent();
        return parent != null && !parent.equals(shared);
    }

    /**
     * The {@code vehicleTypeId} owning a part directory, or null when it is a shared part.
     *
     * <p>Derived from the path rather than from the part's contents, because the directory <i>is</i>
     * the ownership statement: a part's own {@code part.json} never names a vehicle.
     */
    public static String owningVehicle(Path assetRoot, Path partDirectory) {
        if (!isVehicleOwned(assetRoot, partDirectory)) {
            return null;
        }
        Path partsDir = partDirectory.getParent();
        Path vehicleDir = partsDir == null ? null : partsDir.getParent();
        return vehicleDir == null || vehicleDir.getFileName() == null
                ? null
                : vehicleDir.getFileName().toString();
    }

    /** Immediate subdirectories, sorted by name, empty when the parent is absent or unreadable. */
    private static List<Path> childDirectories(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
