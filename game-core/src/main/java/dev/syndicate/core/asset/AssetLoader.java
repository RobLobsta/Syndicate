/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an asset tree into an {@link InMemoryAssetIndex} (docs/08_asset_pipeline.md#D08-S5.3).
 *
 * <p>Three file kinds, in the order D08-S5.3 fixes: materials first because everything references
 * them, then parts, then the assemblies that reference the parts. Within each kind, directories are
 * visited in ascending id order, so two runs over the same tree produce the same index and the same
 * findings in the same sequence (G3).
 *
 * <p><b>Nothing throws on bad content.</b> Every problem becomes a {@link ValidationIssue} in
 * {@link #issues()} and the load continues, because D08-S5.4's report lists every finding before
 * anything decides what to do about them; a loader that threw on the first bad file would make
 * fixing a content directory a sequence of edit-run cycles. What the caller does with a blocking
 * finding — refuse to start in strict mode, substitute a fallback in lenient mode (G18) — is
 * D03-S4.4's decision, not this class's.
 *
 * <p><b>Collision geometry comes from outside.</b> A part's hull source lives in {@code mesh.glb}
 * and {@code game-core} has no glTF reader: the runtime importer is gdx-gltf, which builds libGDX
 * {@code Mesh} objects — GPU buffers requiring a GL context — and G17 forbids that in the module the
 * dedicated server shares (DEC-008 rejected it in the harness for the same reason). So the mesh
 * arrives through {@link CollisionMeshSource}, which is the one seam a future headless glTF reader
 * plugs into, and every other field of a part comes off disk here (DEV-010).
 */
public final class AssetLoader {

    private static final Logger LOG = LoggerFactory.getLogger(AssetLoader.class);

    /** The schema major version this loader understands (D08-R6, A103). */
    public static final int SCHEMA_MAJOR = 1;

    /**
     * Supplies a part's collision hull source.
     *
     * <p>Separate from the loader because the geometry lives in a binary format this module cannot
     * read (see the class comment). An implementation may parse {@code mesh.glb} outside
     * {@code game-core}, generate a primitive, or — in a test — return a box.
     */
    @FunctionalInterface
    public interface CollisionMeshSource {

        /**
         * @param partTypeId the part being loaded
         * @param collisionSourceRef the {@code assets.collisionSource} string from {@code part.json},
         *     e.g. {@code mesh.glb#node=armor_plate_medium_01_col}; null when the part declares none
         * @param partDirectory the part's directory, so an implementation can resolve a relative path
         * @return the mesh, or null when it cannot be produced — the part is then skipped with A503
         */
        MeshData meshFor(AssetId partTypeId, String collisionSourceRef, Path partDirectory);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final CollisionMeshSource meshes;
    private final List<ValidationIssue> issues = new ArrayList<>();

    public AssetLoader(CollisionMeshSource meshes) {
        this.meshes = Objects.requireNonNull(meshes, "meshes");
    }

    /** Every finding from the loads performed so far, in the order they were found. */
    public List<ValidationIssue> issues() {
        return List.copyOf(issues);
    }

    /** Every finding that fails a strict load (D08-S5.4). */
    public List<ValidationIssue> blockingIssues() {
        return AssemblyValidator.blocking(issues);
    }

    /**
     * Loads {@code materials/}, {@code parts/} and {@code vehicles/} beneath an asset root.
     *
     * <p>Assemblies are validated against the parts loaded in the same pass (D08-S5.3 step 3), so a
     * vehicle referencing a part that failed to load is reported as the assembly problem it is
     * rather than surfacing later as a vehicle with a hole in it.
     */
    public InMemoryAssetIndex loadFrom(Path assetRoot) {
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        loadMaterials(assetRoot.resolve("materials").resolve("materials.json"), index);
        for (Path directory : childDirectories(assetRoot.resolve("parts"))) {
            loadPart(directory, index);
        }
        for (Path directory : childDirectories(assetRoot.resolve("vehicles"))) {
            loadAssembly(directory, index);
        }
        LOG.info(
                "loaded {} materials, {} part types, {} assemblies from {} ({} findings)",
                index.materials().size(),
                index.partTypes().size(),
                index.assemblies().size(),
                assetRoot,
                issues.size());
        return index;
    }

    // ---- Materials (D08-S4.3) --------------------------------------------------------

    /** Reads the single material table. Everything else resolves {@code materialId} against it. */
    public void loadMaterials(Path materialsFile, InMemoryAssetIndex index) {
        JsonNode root = readJson(materialsFile, "materials");
        if (root == null || !checkSchemaVersion(root, materialsFile.toString())) {
            return;
        }
        for (JsonNode node : root.path("materials")) {
            AssetId materialId = assetId(node.path("materialId").asText(null), materialsFile.toString());
            if (materialId == null) {
                continue;
            }
            Map<DamageType, Float> resistance = new EnumMap<>(DamageType.class);
            JsonNode resistanceNode = node.path("resistance");
            for (DamageType damageType : DamageType.values()) {
                JsonNode value = resistanceNode.path(damageType.name());
                if (value.isNumber()) {
                    resistance.put(damageType, (float) value.asDouble());
                }
            }
            float density = (float) node.path("densityKgPerM3").asDouble(0d);
            if (density <= 0f) {
                issues.add(ValidationIssue.error(
                        "A203", materialId.value(), "densityKgPerM3 is " + density + "; it must be positive"));
                continue;
            }
            index.put(new MaterialDef(materialId, density, resistance, (float)
                    node.path("fractureBrittleness").asDouble(0d)));
        }
    }

    // ---- Parts (D08-S4.2) ------------------------------------------------------------

    /** Reads one {@code assets/parts/<partTypeId>/part.json}. */
    public void loadPart(Path partDirectory, InMemoryAssetIndex index) {
        Path file = partDirectory.resolve("part.json");
        JsonNode root = readJson(file, partDirectory.getFileName().toString());
        if (root == null || !checkSchemaVersion(root, file.toString())) {
            return;
        }
        String directoryName = partDirectory.getFileName().toString();
        AssetId partTypeId = assetId(root.path("partTypeId").asText(null), directoryName);
        if (partTypeId == null) {
            return;
        }
        if (!partTypeId.value().equals(directoryName)) {
            issues.add(ValidationIssue.error(
                    "A105", partTypeId.value(), "partTypeId does not match its directory name " + directoryName));
        }

        PartCategory category =
                enumValue(PartCategory.class, root.path("category").asText(null));
        if (category == null) {
            issues.add(ValidationIssue.error(
                    "A102",
                    partTypeId.value(),
                    "category \"" + root.path("category").asText() + "\" is not one of "
                            + java.util.Arrays.toString(PartCategory.values())));
            return;
        }

        String collisionSource = root.path("assets").path("collisionSource").asText(null);
        MeshData collisionMesh = meshes.meshFor(partTypeId, collisionSource, partDirectory);
        if (collisionMesh == null) {
            issues.add(ValidationIssue.error(
                    "A503",
                    partTypeId.value(),
                    "no collision mesh could be produced from \"" + collisionSource + "\""));
            return;
        }

        PartType.Builder builder = PartType.builder(partTypeId, category, collisionMesh)
                .massKg((float) root.path("massKg").asDouble(0d))
                .maxHp((float) root.path("maxHp").asDouble(0d))
                .armorValue((float) root.path("armorValue").asDouble(0d))
                .breakImpulseN((float) root.path("breakImpulseN").asDouble(0d))
                .powerCost((float) root.path("powerCost").asDouble(0d))
                .hangsBeforeFalling(root.path("hangsBeforeFalling").asBoolean(false));

        SlotType slotTypeRequired =
                enumValue(SlotType.class, root.path("slotTypeRequired").asText(null));
        if (slotTypeRequired != null) {
            builder.slotTypeRequired(slotTypeRequired);
        } else if (root.hasNonNull("slotTypeRequired")) {
            issues.add(ValidationIssue.error(
                    "A209",
                    partTypeId.value(),
                    "slotTypeRequired \"" + root.path("slotTypeRequired").asText() + "\" is not a SlotType"));
        }

        AssetId materialId = optionalAssetId(root.path("materialId").asText(null), partTypeId.value());
        if (materialId != null) {
            builder.materialId(materialId);
            if (index.material(materialId) == null) {
                issues.add(ValidationIssue.error(
                        "A203", partTypeId.value(), "materialId " + materialId.value() + " is not in the table"));
            }
        }

        readStats(root.path("stats"), partTypeId, category, builder);
        readSlots(root.path("slots"), partTypeId, builder);

        String manifestFile = root.path("assets").path("fractureManifest").asText(null);
        if (manifestFile != null && !manifestFile.isBlank()) {
            // The manifest's own id is its part type's, which is how a part and its shards are
            // paired without a second identifier to keep in sync (D09-S4.4).
            builder.fractureManifestRef(partTypeId);
        } else {
            issues.add(ValidationIssue.warn(
                    "A213", partTypeId.value(), "no fracture manifest; this part will detach whole (D07-E5)"));
        }

        checkPartSemantics(root, partTypeId, category);
        index.put(builder.build());
    }

    /** A201, A204, A205 and A214 — the part-level rules of D08-S5.4 this loader can decide. */
    private void checkPartSemantics(JsonNode root, AssetId partTypeId, PartCategory category) {
        float massKg = (float) root.path("massKg").asDouble(0d);
        if (massKg <= SimulationConstants.MIN_BODY_MASS_KG) {
            issues.add(ValidationIssue.error(
                    "A201",
                    partTypeId.value(),
                    "massKg " + massKg + " is at or below MIN_BODY_MASS_KG (" + SimulationConstants.MIN_BODY_MASS_KG
                            + ")"));
        }
        if (root.path("maxHp").asDouble(0d) <= 0d) {
            issues.add(ValidationIssue.error(
                    "A204", partTypeId.value(), "maxHp " + root.path("maxHp").asDouble(0d) + " must be positive"));
        }
        if (root.path("breakImpulseN").asDouble(0d) <= 0d) {
            issues.add(ValidationIssue.error(
                    "A214",
                    partTypeId.value(),
                    "breakImpulseN " + root.path("breakImpulseN").asDouble(0d)
                            + " must be positive; a zero threshold detaches the part on its first contact"));
        }
        if (category == PartCategory.DECORATIVE
                && (root.path("armorValue").asDouble(0d) != 0d
                        || root.path("stats").isObject())) {
            issues.add(ValidationIssue.error(
                    "A205", partTypeId.value(), "a decorative part may declare no stats and no armour (D05-R6)"));
        }
    }

    /** Reads the {@code stats} block, mapping its camelCase keys onto {@link StatBlock.Stat}. */
    private void readStats(JsonNode statsNode, AssetId partTypeId, PartCategory category, PartType.Builder builder) {
        if (!statsNode.isObject()) {
            return;
        }
        Iterator<String> names = statsNode.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            StatBlock.Stat stat = statByName(name);
            if (stat == null) {
                issues.add(ValidationIssue.error(
                        "A206", partTypeId.value(), "unknown stat name \"" + name + "\" (D05-S4.5)"));
                continue;
            }
            JsonNode value = statsNode.path(name);
            // Unset means identity, never zero: a stat that declares only `add` must keep its
            // multiplier at 1, or the part would zero out every other part's contribution (D05-R15).
            builder.stat(stat, (float) value.path("add").asDouble(0d), (float)
                    value.path("mul").asDouble(1d));
        }
    }

    /** Reads the {@code slots} array, reporting A207 and A208 rather than throwing on either. */
    private void readSlots(JsonNode slotsNode, AssetId partTypeId, PartType.Builder builder) {
        if (!slotsNode.isArray()) {
            return;
        }
        List<String> declaredIds = new ArrayList<>();
        for (JsonNode node : slotsNode) {
            String slotId = node.path("slotId").asText(null);
            if (slotId == null || slotId.isBlank()) {
                issues.add(ValidationIssue.error("A102", partTypeId.value(), "a slot declares no slotId"));
                continue;
            }
            if (declaredIds.contains(slotId)) {
                issues.add(ValidationIssue.error("A207", partTypeId.value(), "slot " + slotId + " is declared twice"));
                continue;
            }
            declaredIds.add(slotId);

            SlotType slotType = enumValue(SlotType.class, node.path("slotType").asText(null));
            if (slotType == null) {
                issues.add(ValidationIssue.error(
                        "A209",
                        partTypeId.value(),
                        "slot " + slotId + " has slotType \""
                                + node.path("slotType").asText() + "\", which is not a SlotType"));
                continue;
            }
            float maxMassKg = (float) node.path("maxMassKg").asDouble(0d);
            if (maxMassKg <= 0f) {
                issues.add(ValidationIssue.error(
                        "A102", partTypeId.value(), "slot " + slotId + " has maxMassKg " + maxMassKg));
                continue;
            }
            List<String> covers = new ArrayList<>();
            for (JsonNode covered : node.path("covers")) {
                covers.add(covered.asText());
            }
            builder.slot(new SlotDefinition(
                    slotId,
                    slotType,
                    readTransform(node),
                    maxMassKg,
                    covers,
                    node.path("isDetachable").asBoolean(true)));
        }
        // A208 is checked after the whole array, because `covers` may name a slot declared later.
        for (JsonNode node : slotsNode) {
            for (JsonNode covered : node.path("covers")) {
                if (!declaredIds.contains(covered.asText())) {
                    issues.add(ValidationIssue.error(
                            "A208",
                            partTypeId.value(),
                            "slot " + node.path("slotId").asText() + " covers \"" + covered.asText()
                                    + "\", which is not a slot on this part"));
                }
            }
        }
    }

    // ---- Assemblies (D08-S4.4) -------------------------------------------------------

    /** Reads one {@code assets/vehicles/<vehicleTypeId>/assembly.json} and validates it. */
    public void loadAssembly(Path vehicleDirectory, InMemoryAssetIndex index) {
        Path file = vehicleDirectory.resolve("assembly.json");
        JsonNode root = readJson(file, vehicleDirectory.getFileName().toString());
        if (root == null || !checkSchemaVersion(root, file.toString())) {
            return;
        }
        String directoryName = vehicleDirectory.getFileName().toString();
        AssetId assemblyId = assetId(root.path("vehicleTypeId").asText(null), directoryName);
        AssetId chassis = optionalAssetId(root.path("chassis").asText(null), directoryName);
        if (assemblyId == null || chassis == null) {
            issues.add(ValidationIssue.error("A301", directoryName, "assembly names no chassis part type"));
            return;
        }

        List<AssemblyDef.PartPlacement> placements = new ArrayList<>();
        for (JsonNode node : root.path("parts")) {
            AssetId partTypeId = optionalAssetId(node.path("partTypeId").asText(null), directoryName);
            String parentSlotPath = node.path("parentSlotPath").asText(null);
            String parentSlotId = node.path("parentSlotId").asText(null);
            if (partTypeId == null || parentSlotPath == null || parentSlotId == null) {
                issues.add(ValidationIssue.error(
                        "A102",
                        assemblyId.value(),
                        "a part entry is missing partTypeId, parentSlotPath or parentSlotId"));
                continue;
            }
            String slotPath = node.path("slotPath").asText(parentSlotPath + "/" + parentSlotId);
            placements.add(new AssemblyDef.PartPlacement(
                    slotPath, parentSlotPath, parentSlotId, partTypeId, readOverrides(node.path("overrides"))));
        }

        AssemblyDef assembly = new AssemblyDef(
                assemblyId,
                root.path("vehicleClass").asText("medium"),
                chassis,
                placements,
                readExpected(root.path("expected")));
        issues.addAll(AssemblyValidator.validate(assembly, index));
        index.put(assembly);
    }

    private static AssemblyDef.Overrides readOverrides(JsonNode node) {
        if (!node.isObject()) {
            return AssemblyDef.Overrides.NONE;
        }
        return new AssemblyDef.Overrides(
                node.hasNonNull("isSteering") ? node.get("isSteering").asBoolean() : null,
                node.hasNonNull("isDriven") ? node.get("isDriven").asBoolean() : null,
                node.hasNonNull("weaponGroup") ? node.get("weaponGroup").asInt() : null);
    }

    private static AssemblyDef.Expected readExpected(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new AssemblyDef.Expected(
                (float) node.path("totalMassKg").asDouble(0d),
                (float) node.path("powerBudget").asDouble(0d),
                readVector(node.path("comLocal")));
    }

    // ---- Shared readers --------------------------------------------------------------

    /**
     * Reads {@code localPosition} and {@code localRotationDeg} into a transform (D08-R6, D00-R17).
     *
     * <p>The rotation's {@code order} is honoured rather than assumed: D00-R17 requires the axis
     * order to be explicit precisely because Euler angles mean different rotations in different
     * orders, and silently applying XYZ to a manifest that said ZYX would place a turret pointing
     * somewhere the author never saw.
     */
    private static Transform readTransform(JsonNode node) {
        Transform transform = new Transform();
        transform.position.set(readVector(node.path("localPosition")));
        JsonNode rotation = node.path("localRotationDeg");
        if (rotation.isObject()) {
            String order = rotation.path("order").asText("XYZ").toUpperCase(Locale.ROOT);
            Quaternion axis = new Quaternion();
            for (int i = 0; i < order.length(); i++) {
                float degrees = (float) rotation.path(String.valueOf(Character.toLowerCase(order.charAt(i))))
                        .asDouble(0d);
                switch (order.charAt(i)) {
                    case 'X' -> axis.set(Vector3.X, degrees);
                    case 'Y' -> axis.set(Vector3.Y, degrees);
                    case 'Z' -> axis.set(Vector3.Z, degrees);
                    default -> axis.idt();
                }
                transform.rotation.mul(axis);
            }
        }
        return transform;
    }

    private static Vector3 readVector(JsonNode node) {
        return new Vector3(
                (float) node.path("x").asDouble(0d), (float) node.path("y").asDouble(0d), (float)
                        node.path("z").asDouble(0d));
    }

    private JsonNode readJson(Path file, String subject) {
        if (!Files.isRegularFile(file)) {
            issues.add(ValidationIssue.error("A107", subject, "missing file " + file));
            return null;
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            issues.add(new ValidationIssue(
                    "A101", ValidationIssue.Severity.FATAL, subject, file + " is not valid JSON: " + e.getMessage()));
            return null;
        }
    }

    private boolean checkSchemaVersion(JsonNode root, String subject) {
        String version = root.path("schemaVersion").asText("");
        int major = version.indexOf('.') > 0 ? parseIntOrMinusOne(version.substring(0, version.indexOf('.'))) : -1;
        if (major != SCHEMA_MAJOR) {
            issues.add(new ValidationIssue(
                    "A103",
                    ValidationIssue.Severity.FATAL,
                    subject,
                    "schemaVersion \"" + version + "\" has major " + major + "; this loader reads " + SCHEMA_MAJOR));
            return false;
        }
        return true;
    }

    private static int parseIntOrMinusOne(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** An id that must be present and well formed; reports A104 and returns null when it is not. */
    private AssetId assetId(String value, String subject) {
        if (value == null || !AssetId.isValid(value)) {
            issues.add(ValidationIssue.error("A104", subject, "\"" + value + "\" is not a valid asset id"));
            return null;
        }
        return AssetId.of(value);
    }

    /** An id that may be absent; an absent one is null, a malformed one is A104. */
    private AssetId optionalAssetId(String value, String subject) {
        return value == null || value.isBlank() ? null : assetId(value, subject);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalised)) {
                return constant;
            }
        }
        return null;
    }

    /**
     * The stat whose name matches a JSON key, comparing on letters and digits alone.
     *
     * <p>{@code part.json} authors stats in camelCase ({@code engineForceN}) and {@link StatBlock}
     * names them in the enum convention ({@code ENGINE_FORCE_N}). Normalising both rather than
     * keeping a translation table means adding a stat requires touching one place, not two.
     */
    private static StatBlock.Stat statByName(String name) {
        String normalised = normalise(name);
        for (StatBlock.Stat stat : StatBlock.Stat.values()) {
            if (normalise(stat.name()).equals(normalised)) {
                return stat;
            }
        }
        return null;
    }

    private static String normalise(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** Immediate subdirectories, sorted by name so a load is reproducible (G3). */
    private List<Path> childDirectories(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            issues.add(ValidationIssue.error("A107", parent.toString(), "cannot list " + parent + ": " + e));
            return List.of();
        }
    }
}
