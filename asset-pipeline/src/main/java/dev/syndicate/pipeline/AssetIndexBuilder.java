/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.AssetPaths;
import dev.syndicate.model.DestructionClass;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Resolves {@code assets/} into one catalogue and checks it (docs/08_asset_pipeline.md#D08-S5.2).
 *
 * <p>The build-time half of the asset story. At runtime {@code game-core}'s loader reads the same
 * files leniently, substitutes a fallback for anything broken, and gets on with the match (G18);
 * here nothing is substituted, everything is cross-checked against everything else, and the output
 * is a single {@code asset-index.json} plus a verdict.
 *
 * <p><b>Why this is a second implementation rather than a call into the loader.</b> D02-S4.5 puts
 * {@code asset-pipeline} on {@code shared-models}, Jackson and a schema validator, and nothing else
 * — it is deliberately not allowed to depend on {@code game-core}. That is not an oversight to work
 * around: a build gate that shares its parser with the thing it is gating cannot catch a bug in that
 * parser, and the two have opposite failure modes by design. What they must agree on is the rule
 * codes, which is why every check below cites the D08-S5.4 code it implements.
 *
 * <p><b>What it does not do yet.</b> The JSON Schema files of D08-S6.1 do not exist, so A102 is
 * never raised and the structural checks below stand in for it; and mesh-level rules (A5xx beyond
 * the manifest cross-check) need a glTF reader this module may not depend on either. Both are named
 * here rather than silently absent.
 */
public final class AssetIndexBuilder {

    /** The schema major version this build understands (D08-R6, A103). */
    public static final int SCHEMA_MAJOR = 1;

    /** The index file's own schema version. */
    public static final String INDEX_SCHEMA_VERSION = "1.0.0";

    /** Fraction by which an authored mass may differ from a computed one (DEC-020, D08-R6). */
    public static final float MASS_DELTA_FRAC = SimulationConstants.MASS_TOLERANCE_FRAC;

    /** Metres by which an authored centre of mass may differ from a computed one (DEC-020). */
    public static final float COM_OFFSET_M = 0.01f;

    /** Fraction by which a vehicle's power budget may differ from its class target (D05-R30). */
    public static final float POWER_BUDGET_TOLERANCE = 0.03f;

    /** Fraction by which an authored {@code powerCost} may differ from the D05-S5.7 formula (A210). */
    public static final float POWER_COST_ADVISORY_FRAC = 0.15f;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Finding> findings = new ArrayList<>();

    /** Everything the walk found, in the order it found it. */
    public List<Finding> findings() {
        return List.copyOf(findings);
    }

    /** The findings that fail a strict build. */
    public List<Finding> blockingFindings() {
        return findings.stream().filter(Finding::isBlocking).toList();
    }

    /**
     * Walks an asset root and produces the index of D08-S5.2.
     *
     * <p>Directories are visited in sorted order throughout, so the index is byte-identical for the
     * same input on any machine — which is what makes it cacheable and what makes a diff of two
     * index files mean something (G3).
     */
    public ObjectNode build(Path assetRoot) {
        findings.clear();
        ObjectNode index = mapper.createObjectNode();
        index.put("schemaVersion", INDEX_SCHEMA_VERSION);

        Map<String, JsonNode> materials = readMaterials(assetRoot);
        Map<String, PartRecord> parts = readParts(assetRoot, materials.keySet());
        List<ObjectNode> vehicles = readVehicles(assetRoot, parts);
        List<ObjectNode> arenas = readArenas(assetRoot);
        checkBalanceClasses(assetRoot, vehicles);

        ArrayNode materialArray = index.putArray("materials");
        materials.values().forEach(materialArray::add);

        ArrayNode partArray = index.putArray("parts");
        for (Map.Entry<String, PartRecord> entry : parts.entrySet()) {
            JsonNode document = entry.getValue().document();
            ObjectNode summary = mapper.createObjectNode();
            summary.put("partTypeId", entry.getKey());
            summary.put("path", entry.getValue().path());
            // Which vehicle owns this part, or null for one in the shared library. The index is
            // what a tool reads instead of walking the tree, so the ownership the tree encodes as
            // a directory has to survive into it (D08-R14b).
            summary.put("ownedBy", entry.getValue().owner());
            summary.put("category", document.path("category").asText(""));
            summary.put("massKg", document.path("massKg").asDouble(0d));
            summary.put("maxHp", document.path("maxHp").asDouble(0d));
            summary.put("powerCost", document.path("powerCost").asDouble(0d));
            summary.put("materialId", document.path("materialId").asText(""));
            partArray.add(summary);
        }

        ArrayNode vehicleArray = index.putArray("vehicles");
        vehicles.forEach(vehicleArray::add);

        ArrayNode arenaArray = index.putArray("arenas");
        arenas.forEach(arenaArray::add);
        return index;
    }

    /** Writes an index to disk with a trailing newline, so the file is diff-friendly. */
    public void write(ObjectNode index, Path outputFile) {
        try {
            Files.createDirectories(outputFile.toAbsolutePath().getParent());
            Files.writeString(
                    outputFile,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(index) + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + outputFile, e);
        }
    }

    // ---- Materials (D08-S4.3) --------------------------------------------------------

    private Map<String, JsonNode> readMaterials(Path assetRoot) {
        Map<String, JsonNode> byId = new TreeMap<>();
        Path file = assetRoot.resolve("materials").resolve("materials.json");
        JsonNode root = readJson(file, "materials");
        if (root == null || !checkSchemaVersion(root, "materials")) {
            return byId;
        }
        for (JsonNode node : root.path("materials")) {
            String id = node.path("materialId").asText("");
            if (!AssetId.isValid(id)) {
                findings.add(Finding.error("A104", "materials", "\"" + id + "\" is not a valid asset id"));
                continue;
            }
            if (byId.put(id, node) != null) {
                findings.add(Finding.error("A106", id, "duplicate materialId in the material table"));
            }
            if (node.path("densityKgPerM3").asDouble(0d) <= 0d) {
                findings.add(Finding.error("A203", id, "densityKgPerM3 must be positive"));
            }
        }
        return byId;
    }

    // ---- Parts (D08-S4.2) ------------------------------------------------------------

    private Map<String, PartRecord> readParts(Path assetRoot, Set<String> materialIds) {
        Map<String, PartRecord> byId = new TreeMap<>();
        for (Path directory : AssetPaths.partDirectories(assetRoot)) {
            String directoryName = directory.getFileName().toString();
            String owner = AssetPaths.owningVehicle(assetRoot, directory);
            JsonNode root = readJson(directory.resolve("part.json"), directoryName);
            if (root == null || !checkSchemaVersion(root, directoryName)) {
                continue;
            }
            String id = root.path("partTypeId").asText("");
            if (!AssetId.isValid(id)) {
                findings.add(Finding.error("A104", directoryName, "\"" + id + "\" is not a valid asset id"));
                continue;
            }
            if (!id.equals(directoryName)) {
                findings.add(
                        Finding.error("A105", id, "partTypeId does not equal its directory name " + directoryName));
            }
            PartRecord previous = byId.put(id, new PartRecord(root, relativePath(assetRoot, directory), owner));
            if (previous != null) {
                findings.add(Finding.error(
                        "A106",
                        id,
                        "declared twice — once in " + previous.path() + " and again in "
                                + relativePath(assetRoot, directory)
                                + "; a part type is owned by exactly one directory (D08-R14b)"));
            }

            double massKg = root.path("massKg").asDouble(0d);
            if (massKg <= SimulationConstants.MIN_BODY_MASS_KG) {
                findings.add(Finding.error("A201", id, "massKg " + massKg + " is at or below MIN_BODY_MASS_KG"));
            }
            if (root.path("maxHp").asDouble(0d) <= 0d) {
                findings.add(Finding.error("A204", id, "maxHp must be positive"));
            }
            if (root.path("breakImpulseN").asDouble(0d) <= 0d) {
                findings.add(Finding.error("A214", id, "breakImpulseN must be positive (it is in N·s, D06-R22)"));
            }
            String materialId = root.path("materialId").asText("");
            if (!materialId.isEmpty() && !materialIds.contains(materialId)) {
                findings.add(Finding.error("A203", id, "materialId " + materialId + " is not in the material table"));
            }
            checkPartSlots(id, root);
            checkPartAssets(directory, id, root, massKg);
            checkPowerCost(id, root);
        }
        return byId;
    }

    /**
     * One part as the walk found it: its document, where it lives, and which vehicle owns it.
     *
     * <p>The last two are what {@link AssetPaths}'s two buckets add. Before the split every part
     * lived in one flat directory and its path was its id; now the path is what a duplicate-id
     * finding has to name to be actionable, and the owner is what A315 is a rule about.
     *
     * @param owner the {@code vehicleTypeId} owning this part, or null for a shared one
     */
    private record PartRecord(JsonNode document, String path, String owner) {}

    /**
     * A315: a vehicle may use the shared library and its own parts, and nothing else.
     *
     * <p>The rule the two-bucket layout exists to make checkable. A vehicle-owned part is cut from
     * one car's art and fitted to that car's slot graph, so another vehicle referencing it gets
     * geometry that is the wrong size, in the wrong place, with a mass derived from a body it is not
     * on. That is the failure the flat directory could not prevent and could not detect.
     *
     * <p>A part in {@code parts/} is shared by construction and is always in scope: that is what
     * makes a modular weapon modular.
     */
    private void checkPartOwnership(String vehicleTypeId, String vehicleDirectory, String partTypeId, PartRecord part) {
        if (part.owner() == null || part.owner().equals(vehicleDirectory)) {
            return;
        }
        findings.add(Finding.error(
                "A315",
                vehicleTypeId,
                "part type " + partTypeId + " belongs to " + part.owner()
                        + "; a vehicle may reference its own parts and the shared library only (D08-R14b)"));
    }

    /** An asset-root-relative path with forward slashes, so the index reads the same on any OS. */
    private static String relativePath(Path assetRoot, Path directory) {
        return assetRoot
                .toAbsolutePath()
                .relativize(directory.toAbsolutePath())
                .toString()
                .replace('\\', '/');
    }

    /** A207 and A208: slot ids are unique within a part, and {@code covers} names slots on it. */
    private void checkPartSlots(String partTypeId, JsonNode root) {
        Set<String> slotIds = new TreeSet<>();
        for (JsonNode slot : root.path("slots")) {
            String slotId = slot.path("slotId").asText("");
            if (!slotIds.add(slotId)) {
                findings.add(Finding.error("A207", partTypeId, "slot " + slotId + " is declared twice"));
            }
        }
        for (JsonNode slot : root.path("slots")) {
            for (JsonNode covered : slot.path("covers")) {
                if (!slotIds.contains(covered.asText(""))) {
                    findings.add(Finding.error(
                            "A208",
                            partTypeId,
                            "slot " + slot.path("slotId").asText("") + " covers \"" + covered.asText()
                                    + "\", which is not a slot on this part"));
                }
            }
        }
    }

    /**
     * A107, A202, A211, A212 and A213: the files a part names, and the manifest it agrees with.
     *
     * <p>The mass cross-check (A202) is the one that matters most and the one nothing else does: a
     * part whose fracture manifest was generated from a different mesh will conserve the manifest's
     * mass rather than the part's, and G7 will hold against the wrong number.
     */
    private void checkPartAssets(Path directory, String partTypeId, JsonNode root, double massKg) {
        JsonNode assets = root.path("assets");
        for (String key : List.of("visualMesh", "shardMesh", "collisionSource")) {
            String value = assets.path(key).asText("");
            if (value.isEmpty()) {
                continue;
            }
            // A collision source may carry a "#node=" fragment; the file is what has to exist.
            String fileName = value.contains("#") ? value.substring(0, value.indexOf('#')) : value;
            if (!Files.isRegularFile(directory.resolve(fileName))) {
                findings.add(Finding.error("A107", partTypeId, "assets." + key + " names a missing file " + fileName));
            }
        }

        JsonNode morphTargets = assets.path("morphTargets");
        if (!morphTargets.isArray() || morphTargets.isEmpty()) {
            findings.add(Finding.warn("A212", partTypeId, "no morph targets; this part will never deform (D07-R17)"));
        } else if (morphTargets.size() != CANONICAL_MORPH_TARGETS.size()) {
            findings.add(Finding.error(
                    "A211", partTypeId, "morphTargets must be exactly " + CANONICAL_MORPH_TARGETS + " or absent"));
        } else {
            for (int i = 0; i < morphTargets.size(); i++) {
                if (!CANONICAL_MORPH_TARGETS.get(i).equals(morphTargets.get(i).asText(""))) {
                    findings.add(Finding.error(
                            "A211", partTypeId, "morphTargets must be exactly " + CANONICAL_MORPH_TARGETS));
                    break;
                }
            }
        }

        String manifestRef = assets.path("fractureManifest").asText("");
        if (manifestRef.isEmpty()) {
            findings.add(
                    Finding.warn("A213", partTypeId, "no fracture manifest; this part will detach whole (D07-E5)"));
            return;
        }
        Path manifestFile = directory.resolve(manifestRef);
        JsonNode manifest = readJson(manifestFile, partTypeId);
        if (manifest == null) {
            return;
        }
        double manifestMass = manifest.path("partMassKg").asDouble(0d);
        if (manifestMass > 0d && Math.abs(manifestMass - massKg) > MASS_DELTA_FRAC * massKg) {
            findings.add(Finding.error(
                    "A202",
                    partTypeId,
                    "massKg " + massKg + " differs from the manifest's partMassKg " + manifestMass + " by more than "
                            + (MASS_DELTA_FRAC * 100) + "%"));
        }
        if (manifest.path("toolVersion").asText("").isEmpty()) {
            findings.add(Finding.error("A506", partTypeId, "the fracture manifest declares no toolVersion"));
        }
        checkTransformMatchesClass(partTypeId, root, manifest);
        double shardMassSum = 0d;
        for (JsonNode shard : manifest.path("shards")) {
            double shardMass = shard.path("massKg").asDouble(0d);
            if (shardMass <= SimulationConstants.MIN_BODY_MASS_KG) {
                findings.add(Finding.error(
                        "A505", partTypeId, "shard " + shard.path("id").asText("") + " weighs " + shardMass + " kg"));
            }
            shardMassSum += shardMass;
        }
        if (manifestMass > 0d && Math.abs(shardMassSum - manifestMass) > MASS_DELTA_FRAC * manifestMass) {
            findings.add(Finding.error(
                    "A504",
                    partTypeId,
                    "the shards sum to " + shardMassSum + " kg against a part mass of " + manifestMass + " kg (G7)"));
        }
    }

    /**
     * A510: a manifest's transform must be one the part's destruction class receives (D15-S5.7).
     *
     * <p>This is the rule that makes the invariant checkable rather than merely stated. Nothing at
     * runtime consults {@code destructionClass} — a part dents because its mesh has shape keys and
     * shatters because it declares a manifest — so the only places the rule can live are the
     * Blender tools, which refuse to author the wrong transform, and here, which refuses to ship
     * one that was authored anyway (DISC-068).
     *
     * <p>The tools and this gate are deliberately independent implementations of one rule, in the
     * spirit of DEC-041: a manifest that arrived by hand, by a copied directory, or from a tool
     * version predating the split has never passed the tools' check, and this is what catches it.
     */
    private void checkTransformMatchesClass(String partTypeId, JsonNode root, JsonNode manifest) {
        String declaredTransform = manifest.path("transform").asText("");
        if (declaredTransform.isEmpty()) {
            findings.add(Finding.error(
                    "A510",
                    partTypeId,
                    "the fracture manifest declares no transform; it predates the FRACTURE/DEFORM "
                            + "split and must be regenerated (D15-S5.7)"));
            return;
        }
        if (!"FRACTURE".equals(declaredTransform)) {
            findings.add(Finding.error(
                    "A510",
                    partTypeId,
                    "assets.fractureManifest names a manifest whose transform is " + declaredTransform));
            return;
        }

        DestructionClass partClass = destructionClassOf(root);
        String manifestClass = manifest.path("destructionClass").asText("");
        if (!manifestClass.isEmpty() && !manifestClass.equals(partClass.name())) {
            findings.add(Finding.error(
                    "A510",
                    partTypeId,
                    "part.json says destructionClass " + partClass + " and its manifest says " + manifestClass));
        }
        // The rule itself: shards on a class D15-S5.7 gives none to. Its own message rather than
        // the mismatch above, because this one is wrong even when the two files agree.
        if (partClass != DestructionClass.GLASS && partClass != DestructionClass.RIGID) {
            findings.add(Finding.error(
                    "A510",
                    partTypeId,
                    "a " + partClass + " part carries a FRACTURE manifest; D15-S5.7 gives it "
                            + (partClass.hasDamageShapeKeys() ? "damage morphs" : "no transform") + " instead"));
        }
        // And the mixture, which is the failure the whole split exists to make impossible.
        if (!root.path("assets").path("morphTargets").isEmpty()) {
            findings.add(Finding.error(
                    "A510",
                    partTypeId,
                    "the part declares both damage morphs and a fracture manifest; no destruction "
                            + "class in D15-S5.7 receives both transforms"));
        }
    }

    /** A part's destruction class, authored or defaulted from its category (D15-S5.7). */
    private static DestructionClass destructionClassOf(JsonNode root) {
        String declared = root.path("destructionClass").asText("");
        if (!declared.isEmpty()) {
            try {
                return DestructionClass.valueOf(declared);
            } catch (IllegalArgumentException ignored) {
                // A misspelled class is A102's business; fall through to the category default.
            }
        }
        PartCategory category;
        try {
            category = PartCategory.valueOf(root.path("category").asText(""));
        } catch (IllegalArgumentException ignored) {
            return DestructionClass.RIGID;
        }
        return DestructionClass.forCategory(category);
    }

    /** A210: an authored {@code powerCost} far from the D05-S5.7 reference formula is advisory. */
    private void checkPowerCost(String partTypeId, JsonNode root) {
        double authored = root.path("powerCost").asDouble(0d);
        if (authored <= 0d) {
            return;
        }
        JsonNode stats = root.path("stats");
        double reference = 0.010 * root.path("maxHp").asDouble(0d)
                + 0.050 * root.path("armorValue").asDouble(0d)
                + 0.300 * statAdd(stats, "engineForceN") / 1000d
                + 0.200 * statAdd(stats, "frictionSlip") * 100d
                - 0.004 * root.path("massKg").asDouble(0d);
        if (reference <= 0d) {
            return;
        }
        if (Math.abs(authored - reference) > POWER_COST_ADVISORY_FRAC * reference) {
            findings.add(Finding.warn(
                    "A210",
                    partTypeId,
                    "powerCost " + authored + " deviates more than " + (int) (POWER_COST_ADVISORY_FRAC * 100)
                            + "% from the D05-S5.7 reference " + Math.round(reference * 100d) / 100d));
        }
    }

    private static double statAdd(JsonNode stats, String name) {
        return stats.path(name).path("add").asDouble(0d);
    }

    // ---- Vehicles (D08-S4.4) ---------------------------------------------------------

    private List<ObjectNode> readVehicles(Path assetRoot, Map<String, PartRecord> parts) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (Path directory : AssetPaths.vehicleDirectories(assetRoot)) {
            String directoryName = directory.getFileName().toString();
            JsonNode root = readJson(directory.resolve("assembly.json"), directoryName);
            if (root == null || !checkSchemaVersion(root, directoryName)) {
                continue;
            }
            String id = root.path("vehicleTypeId").asText("");
            if (!AssetId.isValid(id)) {
                findings.add(Finding.error("A104", directoryName, "\"" + id + "\" is not a valid asset id"));
                continue;
            }
            String chassisId = root.path("chassis").asText("");
            if (!parts.containsKey(chassisId)) {
                findings.add(Finding.error("A301", id, "chassis " + chassisId + " is not a loaded part type"));
                continue;
            }
            JsonNode chassis = parts.get(chassisId).document();
            if (!"CHASSIS".equalsIgnoreCase(chassis.path("category").asText(""))) {
                findings.add(Finding.error("A301", id, "the root part " + chassisId + " is not a chassis"));
            }
            checkPartOwnership(id, directoryName, chassisId, parts.get(chassisId));

            double totalMassKg = chassis.path("massKg").asDouble(0d);
            double powerBudget = chassis.path("powerCost").asDouble(0d);
            int partCount = 1;
            int wheelCount = 0;
            Set<String> occupiedSlots = new TreeSet<>();
            for (JsonNode placement : root.path("parts")) {
                String partTypeId = placement.path("partTypeId").asText("");
                PartRecord record = parts.get(partTypeId);
                if (record == null) {
                    findings.add(Finding.error("A304", id, "part type " + partTypeId + " is not in the catalogue"));
                    continue;
                }
                JsonNode part = record.document();
                checkPartOwnership(id, directoryName, partTypeId, record);
                String slotPath = placement.path("slotPath").asText("");
                String expectedPath = placement.path("parentSlotPath").asText("") + "/"
                        + placement.path("parentSlotId").asText("");
                if (!slotPath.equals(expectedPath)) {
                    findings.add(
                            Finding.error("A303", id, "slotPath \"" + slotPath + "\" is not \"" + expectedPath + "\""));
                }
                if (!occupiedSlots.add(slotPath)) {
                    findings.add(Finding.error("A307", id, "slot " + slotPath + " is occupied twice"));
                }
                totalMassKg += part.path("massKg").asDouble(0d);
                powerBudget += part.path("powerCost").asDouble(0d);
                partCount++;
                if ("WHEEL".equalsIgnoreCase(part.path("category").asText(""))) {
                    wheelCount++;
                }
            }
            if (partCount > SimulationConstants.MAX_PARTS_PER_VEHICLE) {
                findings.add(Finding.error(
                        "A302",
                        id,
                        partCount + " parts exceeds MAX_PARTS_PER_VEHICLE (" + SimulationConstants.MAX_PARTS_PER_VEHICLE
                                + ")"));
            }
            if (wheelCount < MIN_WHEELS) {
                findings.add(Finding.error("A309", id, wheelCount + " wheels; a vehicle needs at least " + MIN_WHEELS));
            }

            JsonNode expected = root.path("expected");
            double expectedMass = expected.path("totalMassKg").asDouble(0d);
            if (expectedMass > 0d && Math.abs(expectedMass - totalMassKg) > MASS_DELTA_FRAC * expectedMass) {
                findings.add(Finding.error(
                        "A310",
                        id,
                        "expected.totalMassKg " + expectedMass + " differs from the computed " + totalMassKg));
            }

            ObjectNode summary = mapper.createObjectNode();
            summary.put("vehicleTypeId", id);
            summary.put("path", "vehicles/" + id);
            summary.put("vehicleClass", root.path("vehicleClass").asText("medium"));
            summary.put("chassis", chassisId);
            summary.put("partCount", partCount);
            summary.put("wheelCount", wheelCount);
            summary.put("totalMassKg", totalMassKg);
            summary.put("powerBudget", powerBudget);
            summaries.add(summary);
        }
        summaries.sort(Comparator.comparing(node -> node.path("vehicleTypeId").asText("")));
        return summaries;
    }

    // ---- Arenas (D08-S4.7) -----------------------------------------------------------

    private List<ObjectNode> readArenas(Path assetRoot) {
        List<ObjectNode> summaries = new ArrayList<>();
        for (Path directory : childDirectories(assetRoot.resolve("arenas"))) {
            String directoryName = directory.getFileName().toString();
            JsonNode root = readJson(directory.resolve("arena.json"), directoryName);
            if (root == null || !checkSchemaVersion(root, directoryName)) {
                continue;
            }
            String id = root.path("arenaId").asText("");
            if (!AssetId.isValid(id)) {
                findings.add(Finding.error("A104", directoryName, "\"" + id + "\" is not a valid asset id"));
                continue;
            }
            int usableSpawns = 0;
            for (JsonNode point : root.path("spawnPoints")) {
                double clearance = point.path("clearanceRadiusM").asDouble(0d);
                if (clearance < MIN_SPAWN_SEPARATION_M) {
                    findings.add(Finding.error(
                            "A402",
                            id,
                            "spawn point " + point.path("id").asText("") + " has clearanceRadiusM " + clearance));
                    continue;
                }
                usableSpawns++;
            }
            if (usableSpawns < MIN_SPAWN_POINTS) {
                findings.add(Finding.error(
                        "A403",
                        id,
                        usableSpawns + " usable spawn points; at least " + MIN_SPAWN_POINTS + " are needed"));
            }
            ObjectNode summary = mapper.createObjectNode();
            summary.put("arenaId", id);
            summary.put("path", "arenas/" + id);
            summary.put("spawnPointCount", usableSpawns);
            summaries.add(summary);
        }
        summaries.sort(Comparator.comparing(node -> node.path("arenaId").asText("")));
        return summaries;
    }

    // ---- Balance (D05-S5.7, A312) ----------------------------------------------------

    /**
     * A312: every vehicle of a class sits within {@link #POWER_BUDGET_TOLERANCE} of its target.
     *
     * <p>This is the check that makes D01-R27 — "unlocks are sidegrades, not upgrades" —
     * mechanically true rather than a matter of opinion. Without a {@code balance/classes.json}
     * there is nothing to compare against, and the check reports that rather than passing silently,
     * because a balance gate that quietly does nothing is worse than no gate at all.
     */
    private void checkBalanceClasses(Path assetRoot, List<ObjectNode> vehicles) {
        Path file = assetRoot.resolve("balance").resolve("classes.json");
        if (!Files.isRegularFile(file)) {
            if (!vehicles.isEmpty()) {
                findings.add(Finding.warn(
                        "A312",
                        "balance",
                        "no balance/classes.json; power budgets are computed but checked against nothing (D05-R32)"));
            }
            return;
        }
        JsonNode root = readJson(file, "balance");
        if (root == null) {
            return;
        }
        Map<String, Double> targets = new TreeMap<>();
        for (JsonNode node : root.path("classes")) {
            targets.put(
                    node.path("classId").asText(""),
                    node.path("powerBudgetTarget").asDouble(0d));
        }
        for (ObjectNode vehicle : vehicles) {
            String vehicleClass = vehicle.path("vehicleClass").asText("");
            Double target = targets.get(vehicleClass);
            if (target == null) {
                findings.add(Finding.error(
                        "A314", vehicle.path("vehicleTypeId").asText(""), "unknown vehicle class " + vehicleClass));
                continue;
            }
            double budget = vehicle.path("powerBudget").asDouble(0d);
            if (target > 0d && Math.abs(budget - target) > POWER_BUDGET_TOLERANCE * target) {
                findings.add(Finding.error(
                        "A312",
                        vehicle.path("vehicleTypeId").asText(""),
                        "power budget " + budget + " is outside " + (int) (POWER_BUDGET_TOLERANCE * 100) + "% of the "
                                + vehicleClass + " target " + target));
            }
        }
    }

    // ---- Plumbing --------------------------------------------------------------------

    /** The canonical damage morph target names (D08-R6, D07-S5.5). */
    private static final List<String> CANONICAL_MORPH_TARGETS = List.of("dmg_25", "dmg_50", "dmg_75", "dmg_100");

    /** Fewest wheels a vehicle may have (A309). */
    private static final int MIN_WHEELS = 3;

    /** Fewest usable spawn points an arena may declare (A403). */
    private static final int MIN_SPAWN_POINTS = 2;

    /** Metres of clearance a spawn point needs (D06-E7, D08-R15). */
    private static final float MIN_SPAWN_SEPARATION_M = 8.0f;

    private JsonNode readJson(Path file, String subject) {
        if (!Files.isRegularFile(file)) {
            findings.add(Finding.error("A107", subject, "missing file " + file));
            return null;
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            findings.add(Finding.fatal("A101", subject, file + " is not valid JSON: " + e.getMessage()));
            return null;
        }
    }

    private boolean checkSchemaVersion(JsonNode root, String subject) {
        String version = root.path("schemaVersion").asText("");
        int dot = version.indexOf('.');
        int major = -1;
        if (dot > 0) {
            try {
                major = Integer.parseInt(version.substring(0, dot).trim());
            } catch (NumberFormatException e) {
                major = -1;
            }
        }
        if (major != SCHEMA_MAJOR) {
            findings.add(Finding.fatal(
                    "A103", subject, "schemaVersion \"" + version + "\"; this build reads major " + SCHEMA_MAJOR));
            return false;
        }
        return true;
    }

    /** Child directories in sorted order, so the index does not depend on the file system's whim. */
    private static List<Path> childDirectories(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + parent, e);
        }
    }
}
