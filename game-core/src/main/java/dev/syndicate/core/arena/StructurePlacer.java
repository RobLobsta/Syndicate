/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.StructureDef;
import dev.syndicate.core.asset.StructurePlacementRule;
import dev.syndicate.core.util.Pcg32;
import dev.syndicate.model.AssetId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides where an arena's structures stand (docs/16_procedural_arena_generation.md#D16-S5.7).
 *
 * <p>The second of the two pieces of new code D16-R81 allows. It draws candidates per rule, tests
 * each against D16-R23's four conditions, and flattens the pad of every candidate that survives —
 * <em>during</em> the pass rather than after it (D16-R45), because a flatten changes the slope test
 * for every later candidate and a structure standing on the lip of another structure's pad is the
 * artefact that ordering prevents.
 *
 * <p><b>Deterministic by construction</b> (D16-R44, G3). Rules are walked in authored order,
 * candidates are generated in a defined order from one seeded stream, and nothing here iterates a
 * hash. A placement pass that iterated a set would produce a different arena on a different JVM from
 * the same seed — the same class of defect as an unsorted system iteration and considerably harder
 * to notice, because both arenas would look fine.
 */
public final class StructurePlacer {

    private static final Logger LOG = LoggerFactory.getLogger(StructurePlacer.class);

    /** Square metres in a hectare, which is the unit {@code densityPerHa} is quoted in. */
    public static final float SQUARE_METRES_PER_HECTARE = 10_000f;

    /**
     * How many candidates are drawn per instance wanted, before giving up on a rule.
     *
     * <p>Scatter placement rejects most of what it draws on a real landscape — a dune face is too
     * steep, a spawn pad is too close, another structure is already there — so drawing exactly as
     * many candidates as instances wanted would populate an arena at a fraction of its density and
     * silently. Eight is enough that the shipped arenas hit their targets, and bounded so a rule
     * whose conditions are unsatisfiable costs a fixed amount rather than spinning.
     */
    public static final int CANDIDATES_PER_INSTANCE = 8;

    /** Metres of flat ground kept around a structure's footprint (D16's STRUCTURE_PAD_MARGIN_M). */
    public static final float STRUCTURE_PAD_MARGIN_M = 2.0f;

    /**
     * Metres from the arena bound within which nothing is placed.
     *
     * <p>The border rise (D16-S5.5) is above the angle of repose by design, so a structure there
     * would stand on a wall. The slope test would catch most of it; this catches the rest, and it
     * costs one comparison.
     */
    public static final float EDGE_KEEPOUT_M = 40.0f;

    private StructurePlacer() {}

    /**
     * One structure, placed.
     *
     * @param structureId what stands here
     * @param position where its root part's origin goes, world space, on the ground
     * @param yawDeg which way it faces, degrees about +Y (D00-R17)
     * @param footprintRadiusM its radius, copied so the overlap test does not re-resolve the asset
     */
    public record Placement(AssetId structureId, Vector3 position, float yawDeg, float footprintRadiusM) {

        public Placement {
            Objects.requireNonNull(structureId, "structureId");
            position = new Vector3(position);
        }
    }

    /** What a placement pass decided, including what it turned down and why (D16-R24). */
    public record Report(List<Placement> placed, int rejected, List<String> findings) {

        public Report {
            placed = List.copyOf(placed);
            findings = List.copyOf(findings);
        }
    }

    /**
     * Runs every rule the arena declares, in authored order.
     *
     * <p>Flattens each accepted candidate's pad on {@code terrain} as it goes, so this must run
     * before the height field's collision shape is built — a flatten after that point would leave
     * the collision and the drawn surface disagreeing by the depth of the pad, which looks exactly
     * like a rendering bug and is not one (the same trap D16-R48 describes).
     *
     * @param arena the arena being generated
     * @param terrain its ground, mutated where a pad is flattened; may be null for a flat arena
     * @param assets the index the structure definitions are resolved against
     * @param random the {@code ARENA_LAYOUT} stream (D16-R43)
     */
    public static Report place(ArenaDef arena, TerrainField terrain, AssetIndex assets, Pcg32 random) {
        List<Placement> placed = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        int rejected = 0;
        if (arena == null || arena.structures().isEmpty()) {
            return new Report(placed, 0, findings);
        }
        for (StructurePlacementRule rule : arena.structures()) {
            StructureDef definition = assets.structure(rule.structureId());
            if (definition == null) {
                findings.add("no structure loaded for " + rule.structureId().value());
                continue;
            }
            int wanted = instanceCount(rule, arena);
            int before = placed.size();
            for (int attempt = 0;
                    attempt < wanted * CANDIDATES_PER_INSTANCE && placed.size() - before < wanted;
                    attempt++) {
                Vector3 candidate = drawCandidate(arena, terrain, rule, random);
                if (candidate == null) {
                    rejected++;
                    continue;
                }
                if (!accepts(arena, terrain, definition, candidate, placed, rule)) {
                    rejected++;
                    continue;
                }
                float yawDeg = random.nextFloat(0f, 360f);
                flattenPad(terrain, candidate, definition.footprintRadiusM() + STRUCTURE_PAD_MARGIN_M);
                candidate.y = groundHeight(arena, terrain, candidate.x, candidate.z);
                placed.add(new Placement(rule.structureId(), candidate, yawDeg, definition.footprintRadiusM()));
            }
            if (placed.size() - before < wanted) {
                findings.add(rule.structureId().value() + ": placed " + (placed.size() - before) + " of " + wanted);
            }
        }
        LOG.info("arena {}: placed {} structures, rejected {}", arena.arenaId().value(), placed.size(), rejected);
        return new Report(placed, rejected, findings);
    }

    /** How many instances a rule asks for, from its density and its bounds. */
    public static int instanceCount(StructurePlacementRule rule, ArenaDef arena) {
        float areaM2 = (arena.boundsMax().x - arena.boundsMin().x) * (arena.boundsMax().z - arena.boundsMin().z);
        int fromDensity = Math.round(rule.densityPerHa() * areaM2 / SQUARE_METRES_PER_HECTARE);
        return Math.max(rule.countMin(), Math.min(rule.countMax(), fromDensity));
    }

    /**
     * One candidate position, or null when the rule has no candidate to offer here.
     *
     * <p>{@code ROAD_VERGE} and {@code ROAD_SPAN} draw against the arena's roads and produce nothing
     * when it has none, which is the honest answer rather than falling back to scatter: an arena
     * with no road cannot have anything beside its road, and silently scattering jersey barriers
     * across a desert is worse than placing none.
     */
    private static Vector3 drawCandidate(
            ArenaDef arena, TerrainField terrain, StructurePlacementRule rule, Pcg32 random) {

        return switch (rule.placement()) {
            case ROAD_VERGE, ROAD_SPAN -> alongRoad(arena, rule, random);
            case CLUSTER, SCATTER -> anywhere(arena, terrain, rule, random);
        };
    }

    private static Vector3 anywhere(ArenaDef arena, TerrainField terrain, StructurePlacementRule rule, Pcg32 random) {
        Vector3 min = arena.boundsMin();
        Vector3 max = arena.boundsMax();
        float x = random.nextFloat(min.x + EDGE_KEEPOUT_M, max.x - EDGE_KEEPOUT_M);
        float z = random.nextFloat(min.z + EDGE_KEEPOUT_M, max.z - EDGE_KEEPOUT_M);
        if (rule.jitterM() > 0f) {
            x += random.nextFloat(-rule.jitterM(), rule.jitterM());
            z += random.nextFloat(-rule.jitterM(), rule.jitterM());
        }
        return new Vector3(x, groundHeight(arena, terrain, x, z), z);
    }

    private static Vector3 alongRoad(ArenaDef arena, StructurePlacementRule rule, Pcg32 random) {
        if (arena.roads().isEmpty()) {
            return null;
        }
        RoadSpec road = arena.roads().get(random.nextInt(arena.roads().size()));
        List<RoadSpec.Point> spline = road.spline();
        if (spline.size() < 2) {
            return null;
        }
        int segment = random.nextInt(spline.size() - 1);
        RoadSpec.Point from = spline.get(segment);
        RoadSpec.Point to = spline.get(segment + 1);
        float t = random.nextFloat();
        float x = from.x() + (to.x() - from.x()) * t;
        float z = from.z() + (to.z() - from.z()) * t;
        if (rule.placement() == StructurePlacementRule.Placement.ROAD_VERGE) {
            // Perpendicular to the segment, one half-width plus a shoulder out, on either side.
            float dx = to.x() - from.x();
            float dz = to.z() - from.z();
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length <= 0f) {
                return null;
            }
            float offset = road.widthM() * 0.5f + road.shoulderM() + rule.spacingM() * 0.25f;
            float side = random.nextBoolean() ? 1f : -1f;
            x += -dz / length * offset * side;
            z += dx / length * offset * side;
        }
        return new Vector3(x, 0f, z);
    }

    /** D16-R23's four conditions, in the order that rejects most cheaply first. */
    private static boolean accepts(
            ArenaDef arena,
            TerrainField terrain,
            StructureDef definition,
            Vector3 candidate,
            List<Placement> placed,
            StructurePlacementRule rule) {

        float radius = definition.footprintRadiusM();
        for (ArenaDef.SpawnPoint spawn : arena.spawnPoints()) {
            float dx = spawn.position().x - candidate.x;
            float dz = spawn.position().z - candidate.z;
            float clearance = spawn.clearanceRadiusM() + radius;
            if (dx * dx + dz * dz < clearance * clearance) {
                return false;
            }
        }
        for (Placement other : placed) {
            float dx = other.position().x - candidate.x;
            float dz = other.position().z - candidate.z;
            float clearance = other.footprintRadiusM() + radius;
            if (dx * dx + dz * dz < clearance * clearance) {
                return false;
            }
        }
        if (terrain != null) {
            if (terrain.slopeDegAt(candidate.x, candidate.z) > terrain.params().maxDrivableSlopeDeg()) {
                return false;
            }
            if (!rule.placement().mayStandOnRoad() && terrain.surfaceAt(candidate.x, candidate.z) == Surface.TARMAC) {
                return false;
            }
        }
        return true;
    }

    /**
     * Levels the ground under a structure to the height at its centre (D16-R45).
     *
     * <p>Hard inside the footprint and eased over the margin, so the pad does not stand as a
     * cylinder of raised earth. Nothing is done when the arena has no terrain: a flat arena is
     * already flat, and this is the one place where "no terrain" needs no special case at all.
     */
    public static void flattenPad(TerrainField terrain, Vector3 centre, float radiusM) {
        if (terrain == null || radiusM <= 0f) {
            return;
        }
        TerrainParams params = terrain.params();
        float cell = params.cellSizeM();
        float target = terrain.heightAt(centre.x, centre.z) - terrain.groundY();
        float[] heights = terrain.heights();
        int grid = params.gridSize();
        int reach = (int) Math.ceil((radiusM + STRUCTURE_PAD_MARGIN_M) / cell);
        int centreI = Math.round((centre.x - terrain.sampleX(0)) / cell);
        int centreJ = Math.round((centre.z - terrain.sampleZ(0)) / cell);
        for (int j = Math.max(0, centreJ - reach); j <= Math.min(grid - 1, centreJ + reach); j++) {
            for (int i = Math.max(0, centreI - reach); i <= Math.min(grid - 1, centreI + reach); i++) {
                float dx = terrain.sampleX(i) - centre.x;
                float dz = terrain.sampleZ(j) - centre.z;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                if (distance > radiusM + STRUCTURE_PAD_MARGIN_M) {
                    continue;
                }
                float weight = distance <= radiusM ? 1f : 1f - (distance - radiusM) / STRUCTURE_PAD_MARGIN_M;
                int index = j * grid + i;
                heights[index] = heights[index] + (target - heights[index]) * weight;
            }
        }
    }

    private static float groundHeight(ArenaDef arena, TerrainField terrain, float x, float z) {
        return terrain == null ? arena.groundY() : terrain.heightAt(x, z);
    }
}
