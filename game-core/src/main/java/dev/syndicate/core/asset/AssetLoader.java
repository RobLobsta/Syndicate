/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.syndicate.core.ai.BotDifficultyParams;
import dev.syndicate.core.ai.BotDifficultyTable;
import dev.syndicate.core.arena.ArenaTheme;
import dev.syndicate.core.arena.TerrainParams;
import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.DegradationProfile;
import dev.syndicate.core.vehicle.DegradationRule;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.AudioMaterial;
import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.DestructionClass;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
 * <p><b>Collision geometry comes through a seam.</b> A part's hull source lives in {@code mesh.glb},
 * and the importer D08-R12 names — gdx-gltf — builds libGDX {@code Mesh} objects, which are GPU
 * buffers requiring a GL context that G17 forbids in the module the dedicated server shares. So the
 * mesh arrives through {@link CollisionMeshSource} rather than being read inline (DEV-010). The
 * implementation that reads it is {@link GltfCollisionMeshSource}, on {@link GltfReader}; the seam
 * stays because it is also what lets a test stand a box in place of a model.
 */
public final class AssetLoader {

    private static final Logger LOG = LoggerFactory.getLogger(AssetLoader.class);

    /** The schema major version this loader understands (D08-R6, A103). */
    public static final int SCHEMA_MAJOR = 1;

    /**
     * The fewest usable spawn points an arena may declare (A403).
     *
     * <p>D08-S5.4 words A403 as two per team named in {@code modes}. Two overall is what this checks:
     * a free-for-all arena has no teams to count, and the failure the rule exists to prevent — a
     * second vehicle with nowhere to go — happens at exactly two.
     */
    public static final int MIN_SPAWN_POINTS = 2;

    /** Metres of clearance A405 wants between the kill plane and the arena's floor of the bounds. */
    public static final float KILL_PLANE_CLEARANCE_M = 5.0f;

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
         *     e.g. {@code mesh.glb#node=panel_plate_medium_01_col}; null when the part declares none
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
        for (Path directory : childDirectories(assetRoot.resolve("arenas"))) {
            loadArena(directory, index);
        }
        loadBotDifficulties(assetRoot.resolve("balance").resolve("bot_difficulty.json"), index);
        LOG.info(
                "loaded {} materials, {} part types, {} assemblies, {} arenas from {} ({} findings)",
                index.materials().size(),
                index.partTypes().size(),
                index.assemblies().size(),
                index.arenas().size(),
                assetRoot,
                issues.size());
        return index;
    }

    // ---- Bot difficulty (D11-R4) -----------------------------------------------------

    /**
     * Reads {@code balance/bot_difficulty.json} into the index (D11-R4).
     *
     * <p>A missing or malformed file is a warning, not a blocking error, and the defaults of
     * {@link BotDifficultyTable#defaults()} stand. The alternative — refusing to load, or loading an
     * empty table — would give every bot zero reaction delay and perfect aim, which is a much worse
     * failure than "the tuning file was not read".
     */
    public void loadBotDifficulties(Path difficultyFile, InMemoryAssetIndex index) {
        if (!Files.isRegularFile(difficultyFile)) {
            LOG.warn("{} is absent; bot difficulty falls back to the D11-S4.2 defaults", difficultyFile);
            return;
        }
        JsonNode root = readJson(difficultyFile, "bot difficulty");
        if (root == null || !checkSchemaVersion(root, difficultyFile.toString())) {
            return;
        }
        EnumMap<BotDifficulty, BotDifficultyParams> rows = new EnumMap<>(BotDifficulty.class);
        JsonNode levels = root.path("difficulties");
        for (BotDifficulty level : BotDifficulty.values()) {
            JsonNode node = levels.path(level.name());
            if (node.isMissingNode()) {
                issues.add(ValidationIssue.warn(
                        "A320", level.name(), "no row in bot_difficulty.json; the D11-S4.2 default is used"));
                continue;
            }
            BotDifficultyParams fallback = BotDifficultyTable.defaults().get(level);
            rows.put(
                    level,
                    new BotDifficultyParams(
                            (float) node.path("reactionDelayS").asDouble(fallback.reactionDelayS()),
                            node.path("sensorUpdateHz").asInt(fallback.sensorUpdateHz()),
                            (float) node.path("aimErrorRad").asDouble(fallback.aimErrorRad()),
                            (float) node.path("aimSettleRate").asDouble(fallback.aimSettleRateRadPerS()),
                            (float) node.path("leadPredictionQuality").asDouble(fallback.leadPredictionQuality()),
                            (float) node.path("throttleAggression").asDouble(fallback.throttleAggression()),
                            (float) node.path("avoidanceLookaheadS").asDouble(fallback.avoidanceLookaheadS()),
                            (float) node.path("targetSwitchCooldownS").asDouble(fallback.targetSwitchCooldownS()),
                            node.path("usesPartTargeting").asBoolean(fallback.usesPartTargeting()),
                            (float) node.path("retreatHealthFraction").asDouble(fallback.retreatHealthFraction()),
                            (float) node.path("firingDisciplineRange").asDouble(fallback.firingDisciplineRange()),
                            node.path("usesCover").asBoolean(fallback.usesCover()),
                            node.path("focusFireCoordination").asBoolean(fallback.focusFireCoordination())));
        }
        index.put(BotDifficultyTable.of(rows));
    }

    /**
     * Reads an enum-valued field, reporting an unknown value rather than silently defaulting.
     *
     * <p>Absent is fine and takes the default; present-but-misspelled is a finding. A field that
     * silently fell back would turn a typo into behaviour nobody can trace.
     */
    private <E extends Enum<E>> E enumOrDefault(Class<E> type, String raw, E fallback, AssetId subject, String field) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error(
                    "A204", subject.value(), field + " is \"" + raw + "\", which is not a " + type.getSimpleName()));
            return fallback;
        }
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
            index.put(new MaterialDef(
                    materialId,
                    density,
                    resistance,
                    (float) node.path("fractureBrittleness").asDouble(0d),
                    enumOrDefault(
                            AudioMaterial.class,
                            node.path("audioMaterial").asText(null),
                            MaterialDef.DEFAULT_AUDIO_MATERIAL,
                            materialId,
                            "audioMaterial")));
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
            // Absent is the normal case and defaults from the category; present-but-misspelled is a
            // finding, because a windscreen that quietly became RIGID is a windscreen that does not
            // shatter and nothing downstream would ever say why.
            String declaredClass = root.path("destructionClass").asText(null);
            if (declaredClass != null && !declaredClass.isBlank()) {
                builder.destructionClass(enumOrDefault(
                        DestructionClass.class,
                        declaredClass,
                        DestructionClass.forCategory(category),
                        partTypeId,
                        "destructionClass"));
            }
            if (index.material(materialId) == null) {
                issues.add(ValidationIssue.error(
                        "A203", partTypeId.value(), "materialId " + materialId.value() + " is not in the table"));
            }
        }

        readStats(root.path("stats"), partTypeId, category, builder);
        readHandling(root.path("handling"), partTypeId, builder);
        readDegradationOverrides(root.path("degradationOverrides"), partTypeId, builder);
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

    /**
     * Reads the {@code handling} block: the physical parameters no stat can carry (D08-R5, DEC-031).
     *
     * <p>Every field is optional and falls back to D06-S4.5's reference chassis, so a part authoring
     * only a drag coefficient keeps the reference suspension rather than getting zeros. A value the
     * record rejects — a negative drag coefficient, a NaN damping — is reported as A210 and the whole
     * block is discarded, because a partly-applied handling block is a vehicle nobody can explain.
     */
    private void readHandling(JsonNode node, AssetId partTypeId, PartType.Builder builder) {
        if (!node.isObject()) {
            return;
        }
        HandlingBlock reference = HandlingBlock.REFERENCE;
        try {
            builder.handling(new HandlingBlock(
                    (float) node.path("dragCoefficient").asDouble(reference.dragCoefficient()),
                    (float) node.path("rollingResistance").asDouble(reference.rollingResistance()),
                    (float) node.path("downforceCoefficient").asDouble(reference.downforceCoefficient()),
                    (float) node.path("suspensionCompression").asDouble(reference.suspensionCompression()),
                    (float) node.path("suspensionDamping").asDouble(reference.suspensionDamping()),
                    (float) node.path("rollInfluence").asDouble(reference.rollInfluence()),
                    (float) node.path("suspensionRestLengthM").asDouble(reference.suspensionRestLengthM()),
                    (float) node.path("maxSuspensionTravelCm").asDouble(reference.maxSuspensionTravelCm()),
                    (float) node.path("maxSuspensionForceN").asDouble(reference.maxSuspensionForceN())));
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error("A210", partTypeId.value(), "handling: " + e.getMessage()));
        }
    }

    /**
     * Reads the {@code degradationOverrides} block: a per-stat {@code {profile, floor}} that
     * replaces the D05-S5.4 table for this part type (D08-R5).
     *
     * <p>An unknown stat name is A206, the same code the {@code stats} block reports, because it is
     * the same mistake. A bad profile or an out-of-range floor is reported and the entry dropped, so
     * the part falls back to the table rather than to a curve nobody authored.
     */
    private void readDegradationOverrides(JsonNode node, AssetId partTypeId, PartType.Builder builder) {
        if (!node.isObject()) {
            return;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            StatBlock.Stat stat = statByName(name);
            if (stat == null) {
                issues.add(ValidationIssue.error(
                        "A206",
                        partTypeId.value(),
                        "degradationOverrides names unknown stat \"" + name + "\" (D05-S4.5)"));
                continue;
            }
            JsonNode entry = node.path(name);
            DegradationProfile profile =
                    enumValue(DegradationProfile.class, entry.path("profile").asText(null));
            if (profile == null) {
                issues.add(ValidationIssue.error(
                        "A206",
                        partTypeId.value(),
                        "degradationOverrides." + name + ".profile \""
                                + entry.path("profile").asText() + "\" is not a DegradationProfile (D05-S5.4)"));
                continue;
            }
            float floor = (float) entry.path("floor").asDouble(1d);
            if (!(floor >= 0f) || floor > 1f) {
                issues.add(ValidationIssue.error(
                        "A206",
                        partTypeId.value(),
                        "degradationOverrides." + name + ".floor " + floor + " must be in [0,1]"));
                continue;
            }
            builder.degradationOverride(stat, new DegradationRule(profile, floor));
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

    // ---- Arenas (D08-S4.7) -----------------------------------------------------------

    /**
     * Reads one {@code assets/arenas/<arenaId>/arena.json} and validates it (D08-S5.4 A4xx).
     *
     * <p>The A4xx rules are about a player's first ten seconds in a match: a spawn point outside the
     * bounds drops a vehicle through the world (A401), one with too little clearance spawns two
     * vehicles inside each other (A402), too few of them means a team has nowhere to go (A403), and a
     * kill plane sitting just under the floor kills anyone who drives over a bump (A405).
     */
    public void loadArena(Path arenaDirectory, InMemoryAssetIndex index) {
        Path file = arenaDirectory.resolve("arena.json");
        String directoryName = arenaDirectory.getFileName().toString();
        JsonNode root = readJson(file, directoryName);
        if (root == null || !checkSchemaVersion(root, file.toString())) {
            return;
        }
        AssetId arenaId = assetId(root.path("arenaId").asText(null), directoryName);
        if (arenaId == null) {
            return;
        }
        if (!arenaId.value().equals(directoryName)) {
            issues.add(ValidationIssue.error(
                    "A105", arenaId.value(), "arenaId does not match its directory name " + directoryName));
        }

        Vector3 boundsMin = readVector(root.path("boundsMin"));
        Vector3 boundsMax = readVector(root.path("boundsMax"));
        float killPlaneY = (float) root.path("killPlaneY").asDouble(boundsMin.y);
        float groundY = (float) root.path("groundY").asDouble(0d);

        List<ArenaDef.SpawnPoint> spawnPoints = new ArrayList<>();
        Set<String> seenIds = new TreeSet<>();
        for (JsonNode node : root.path("spawnPoints")) {
            String id = node.path("id").asText(null);
            if (id == null || !seenIds.add(id)) {
                issues.add(ValidationIssue.error(
                        "A102", arenaId.value(), "a spawn point has a missing or duplicate id: " + id));
                continue;
            }
            Vector3 position = readVector(node.path("position"));
            float clearance = (float) node.path("clearanceRadiusM").asDouble(0d);
            ArenaDef.SpawnPoint point = new ArenaDef.SpawnPoint(
                    id,
                    node.path("team").asInt(ArenaDef.SpawnPoint.ANY_TEAM),
                    position,
                    (float) node.path("yawDeg").asDouble(0d),
                    clearance);
            if (!withinBounds(position, boundsMin, boundsMax)) {
                issues.add(ValidationIssue.error(
                        "A401", arenaId.value(), "spawn point " + id + " at " + position + " is outside the bounds"));
                continue;
            }
            if (clearance < ArenaDef.MIN_SPAWN_SEPARATION_M) {
                issues.add(ValidationIssue.error(
                        "A402",
                        arenaId.value(),
                        "spawn point " + id + " has clearanceRadiusM " + clearance + "; the minimum is "
                                + ArenaDef.MIN_SPAWN_SEPARATION_M));
                continue;
            }
            spawnPoints.add(point);
        }
        if (spawnPoints.size() < MIN_SPAWN_POINTS) {
            issues.add(ValidationIssue.error(
                    "A403",
                    arenaId.value(),
                    "declares " + spawnPoints.size() + " usable spawn points; at least " + MIN_SPAWN_POINTS
                            + " are needed"));
        }
        if (killPlaneY > boundsMin.y - KILL_PLANE_CLEARANCE_M) {
            issues.add(ValidationIssue.warn(
                    "A405",
                    arenaId.value(),
                    "killPlaneY " + killPlaneY + " is within " + KILL_PLANE_CLEARANCE_M + " m of boundsMin.y "
                            + boundsMin.y));
        }

        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (JsonNode node : root.path("modes")) {
            GameMode mode = enumValue(GameMode.class, node.asText(null));
            if (mode == null) {
                issues.add(
                        ValidationIssue.error("A102", arenaId.value(), "unknown game mode \"" + node.asText() + "\""));
                continue;
            }
            modes.add(mode);
        }

        TerrainParams terrain = readTerrain(root.path("terrain"), arenaId, boundsMin, boundsMax, issues);

        index.put(new ArenaDef(
                arenaId,
                root.path("displayName").asText(arenaId.value()),
                boundsMin,
                boundsMax,
                killPlaneY,
                groundY,
                spawnPoints,
                modes,
                root.path("assets").path("collision").asText(null),
                terrain));
    }

    /**
     * Reads the optional {@code terrain} block (D16-S4.2), or null when there is none.
     *
     * <p><b>The theme supplies every default.</b> An arena that says {@code "theme": "scrapyard"}
     * and nothing else gets a coherent scrapyard, because the relief amplitude, the feature spacing,
     * the rim and the surface palette all come from {@link ArenaTheme}. Anything explicitly authored
     * overrides it — but authoring nothing is the intended case, not a degenerate one.
     *
     * <p>A missing block is not a finding: a flat arena is legal (D16-R4). A block that is present
     * and inconsistent with the arena's bounds is A410, and a block naming an unknown theme is A413.
     * Both are errors rather than warnings, because every terrain query maps world to grid by
     * arithmetic — a grid that does not cover the bounds does not fail, it silently reads the ground
     * from the wrong place.
     */
    private TerrainParams readTerrain(
            JsonNode node, AssetId arenaId, Vector3 boundsMin, Vector3 boundsMax, List<ValidationIssue> issues) {

        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String themeName = node.path("theme").asText(ArenaTheme.DESERT_HIGHWAY.name());
        ArenaTheme theme = ArenaTheme.parse(themeName);
        if (theme == null) {
            issues.add(ValidationIssue.error("A413", arenaId.value(), "unknown arena theme \"" + themeName + "\""));
            return null;
        }
        ArenaTheme.Shape shape = theme.shape();

        float spanX = boundsMax.x - boundsMin.x;
        float cellSizeM = (float) node.path("cellSizeM").asDouble(TerrainParams.DEFAULT_CELL_SIZE_M);
        // The grid size defaults to whatever covers the bounds at the cell size, so the common case
        // authors two numbers rather than three that have to agree.
        int gridSize = node.path("gridSize").asInt(oddGridFor(spanX, cellSizeM));

        TerrainParams params;
        try {
            params = new TerrainParams(
                    node.path("seed").asLong(0L),
                    cellSizeM,
                    gridSize,
                    theme,
                    (float) node.path("reliefM").asDouble(shape.reliefM()),
                    (float) node.path("baseFrequency").asDouble(shape.baseFrequency()),
                    node.path("octaves").asInt(shape.octaves()),
                    (float) node.path("featureBearingDeg").asDouble(shape.featureBearingDeg()),
                    (float) node.path("featureWavelengthM").asDouble(shape.featureWavelengthM()),
                    (float) node.path("featureHeightM").asDouble(shape.featureHeightM()),
                    (float) node.path("borderWidthM").asDouble(shape.borderWidthM()),
                    (float) node.path("borderRiseM").asDouble(shape.borderRiseM()),
                    (float) node.path("maxDrivableSlopeDeg").asDouble(TerrainParams.MAX_DRIVABLE_SLOPE_DEG));
        } catch (IllegalArgumentException e) {
            issues.add(ValidationIssue.error("A410", arenaId.value(), e.getMessage()));
            return null;
        }

        float spanZ = boundsMax.z - boundsMin.z;
        if (Math.abs(spanX - params.spanM()) > 1e-3f || Math.abs(spanZ - params.spanM()) > 1e-3f) {
            issues.add(ValidationIssue.error(
                    "A410",
                    arenaId.value(),
                    "terrain grid spans " + params.spanM() + " m but the arena bounds span " + spanX + " x " + spanZ
                            + " m (D16-R5)"));
            return null;
        }
        return params;
    }

    /** The smallest odd grid covering a span at a cell size, which is what D16-R5 requires. */
    private static int oddGridFor(float spanM, float cellSizeM) {
        int grid = Math.round(spanM / cellSizeM) + 1;
        return (grid & 1) == 0 ? grid + 1 : grid;
    }

    private static boolean withinBounds(Vector3 point, Vector3 min, Vector3 max) {
        return point.x >= min.x
                && point.x <= max.x
                && point.y >= min.y
                && point.y <= max.y
                && point.z >= min.z
                && point.z <= max.z;
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
                root.path("displayName").asText(assemblyId.value()),
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
