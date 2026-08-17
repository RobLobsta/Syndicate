/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stage 4: cuts a road into the generated ground (docs/16_procedural_arena_generation.md#D16-S5.4).
 *
 * <p>Three passes, in D16-R35's order. Sample the land the road crosses and smooth it hard, so the
 * road <em>follows</em> the landform without copying its every bump. Clamp the longitudinal grade,
 * forward then backward — each pass makes the profile legal in one direction and cannot break the
 * other. Then blend the terrain toward the road surface with a falloff that reaches untouched ground
 * smoothly.
 *
 * <p><b>The falloff is the feature</b> (D16-R36). Where the terrain was above the road the blend digs
 * a cutting and the road runs between two banks; where it was below, the blend raises an embankment
 * and the road becomes a ridge you can be pushed off. Neither is authored, both fall out of one lerp,
 * and together they are the reason a highway is worth fighting on rather than a flat strip.
 *
 * <p>Runs after the landform and the border rise and before classification and drivability, because
 * it changes the heights both of those read (D16-S5.1). Roads are carved in array order and later
 * roads win where they overlap, which is how a junction is expressible without a junction primitive
 * (D16-R10).
 */
public final class RoadCarver {

    /**
     * Metres between polyline samples along a centreline.
     *
     * <p>One cell. Finer would place stations the height grid cannot distinguish; coarser would let
     * the grade limiter step over a hill the road is supposed to climb.
     */
    private static final float STATION_STEP_FACTOR = 1.0f;

    private RoadCarver() {
        throw new AssertionError("no instances");
    }

    /**
     * Metres. The deepest cutting a road may dig before it is treated as an authoring error
     * (D16-S5.5, DISC-062).
     *
     * <p>A road that reaches into the border rise does not climb it. The profile is smoothed over
     * {@link RoadSpec#PROFILE_SMOOTH_SIGMA_M} and then grade-limited, so a centreline ending 36 m
     * up the rim flattens to something near the arena floor and the blend drags the rim down to
     * meet it — opening a drivable canyon straight through the wall that exists to contain the
     * arena.
     *
     * <p>Calibrated against the measurements in DISC-062, on the desert at seed 12345:
     *
     * <pre>
     *   spline +/-200 m   cut  2.7 m      inside the playable area
     *   spline +/-240 m   cut  3.3 m      at the foot of the rim
     *   spline +/-290 m   cut 30.8 m      50 m into the rim: a canyon
     * </pre>
     *
     * <p>Ten metres sits an order of magnitude clear of what a road across open dunes digs and far
     * below what reaching the rim costs — there is a cliff between the two rows, not a gradient, so
     * the threshold does not need to be precise. It is deliberately a measurement of the carve
     * rather than a rule about spline length: the two shipped arenas have different cell sizes and
     * different rim positions, so an extent verified on one says nothing about the other, and a
     * canyon dug for some other reason is just as fatal.
     */
    public static final float MAX_CUT_M = 10.0f;

    /**
     * Rejects a carve that dug through the border rise (D16-S5.5, DISC-062).
     *
     * <p>A hard failure at load, because the alternative is what DISC-062 cost: the damage surfaces
     * three stages downstream as {@code D16-R58} rejecting spawn connectivity or {@code D16-R38}
     * finding drivable ground at the arena edge, on eight consecutive seeds, and neither message
     * says the word "road".
     *
     * @throws IllegalArgumentException naming the road and its cut
     */
    public static void validateCuts(List<Report> reports) {
        if (reports == null) {
            return;
        }
        for (Report report : reports) {
            if (report.maxCutM() > MAX_CUT_M) {
                throw new IllegalArgumentException(String.format(
                        "road '%s' cut %.1f m, over the %.1f m limit: its spline reaches into the border rise and "
                                + "has carved a drivable canyon through it. Shorten the spline so the corridor "
                                + "stays inside the playable area (D16-S5.5, DISC-062)",
                        report.roadId(), report.maxCutM(), MAX_CUT_M));
            }
        }
    }

    /**
     * Carves every road into {@code heights} and paints {@code surfaces}, in array order (D16-R10).
     *
     * @param heights metres above {@code groundY}, row-major in {@code (z, x)}; modified in place
     * @param surfaces {@link Surface} ordinals parallel to {@code heights}; modified in place
     * @param originX world x of grid column 0
     * @param originZ world z of grid row 0
     * @return one report per road, in the order they were carved
     */
    public static List<Report> carve(
            float[] heights,
            byte[] surfaces,
            TerrainParams params,
            float originX,
            float originZ,
            List<RoadSpec> roads) {

        Objects.requireNonNull(heights, "heights");
        Objects.requireNonNull(surfaces, "surfaces");
        Objects.requireNonNull(params, "params");
        List<Report> reports = new ArrayList<>();
        if (roads == null || roads.isEmpty()) {
            return reports;
        }
        for (RoadSpec road : roads) {
            reports.add(carveOne(heights, surfaces, params, originX, originZ, road));
        }
        return reports;
    }

    private static Report carveOne(
            float[] heights, byte[] surfaces, TerrainParams params, float originX, float originZ, RoadSpec road) {

        float cell = params.cellSizeM();
        int grid = params.gridSize();

        // [1] Sample the land the road crosses, then smooth it hard.
        List<RoadSpec.Point> centre = catmullRom(road.spline(), cell * STATION_STEP_FACTOR);
        float[] profile = new float[centre.size()];
        for (int s = 0; s < centre.size(); s++) {
            profile[s] = sampleHeight(heights, params, originX, originZ, centre.get(s));
        }
        gaussianSmooth(profile, RoadSpec.PROFILE_SMOOTH_SIGMA_M / cell);

        // [2] Clamp the longitudinal grade in both directions.
        float limit = road.maxGradePct() / 100f * cell * STATION_STEP_FACTOR;
        for (int s = 1; s < profile.length; s++) {
            profile[s] = clamp(profile[s], profile[s - 1] - limit, profile[s - 1] + limit);
        }
        for (int s = profile.length - 2; s >= 0; s--) {
            profile[s] = clamp(profile[s], profile[s + 1] - limit, profile[s + 1] + limit);
        }

        // [3] Blend the terrain toward the road surface.
        float halfWidth = road.halfWidthM();
        float shoulderEdge = halfWidth + road.shoulderM();
        float reach = road.reachM();
        int carriagewayCells = 0;
        int vergeCells = 0;
        float maxCutM = 0f;
        float maxFillM = 0f;

        // Only the cells the corridor can possibly touch, found from the centreline's bounding box
        // rather than by sweeping the whole arena: a 600 m grid is 361,000 samples and a road
        // reaches 20 m either side of a line across it.
        int[] bounds = corridorBounds(centre, reach, params, originX, originZ);
        for (int j = bounds[1]; j <= bounds[3]; j++) {
            float worldZ = originZ + j * cell;
            for (int i = bounds[0]; i <= bounds[2]; i++) {
                float worldX = originX + i * cell;
                int station = nearestStation(centre, worldX, worldZ);
                float d = distanceTo(centre.get(station), worldX, worldZ);
                if (d > reach) {
                    continue;
                }
                int index = j * grid + i;
                float target = profile[station] - crossfall(d, halfWidth);
                float before = heights[index];
                if (d <= halfWidth) {
                    heights[index] = target;
                    surfaces[index] = (byte) road.surface().ordinal();
                    carriagewayCells++;
                } else if (d <= shoulderEdge) {
                    heights[index] = target;
                    surfaces[index] = (byte) road.verge().ordinal();
                    vergeCells++;
                } else {
                    float t = (d - shoulderEdge) / RoadSpec.FALLOFF_M;
                    // smoothstep(1, 0, t): full weight at the shoulder, none at the outer edge.
                    float w = smoothstep(1f, 0f, t);
                    heights[index] = before + (target - before) * w;
                }
                float delta = heights[index] - before;
                if (delta < 0f) {
                    maxCutM = Math.max(maxCutM, -delta);
                } else {
                    maxFillM = Math.max(maxFillM, delta);
                }
            }
        }
        return new Report(
                road.id(), lengthOf(centre), carriagewayCells, vergeCells, maxCutM, maxFillM, gradeOf(profile, cell));
    }

    // ---- Geometry -------------------------------------------------------------------

    /**
     * A Catmull-Rom polyline through the control points, roughly {@code stepM} apart.
     *
     * <p>The end control points are duplicated rather than extrapolated, which makes the spline pass
     * through every authored point including the first and last — an extrapolated phantom point
     * would move the road's ends away from where the arena author put them.
     */
    static List<RoadSpec.Point> catmullRom(List<RoadSpec.Point> control, float stepM) {
        List<RoadSpec.Point> out = new ArrayList<>();
        int n = control.size();
        for (int segment = 0; segment < n - 1; segment++) {
            RoadSpec.Point p0 = control.get(Math.max(0, segment - 1));
            RoadSpec.Point p1 = control.get(segment);
            RoadSpec.Point p2 = control.get(segment + 1);
            RoadSpec.Point p3 = control.get(Math.min(n - 1, segment + 2));
            float chord = (float) Math.hypot(p2.x() - p1.x(), p2.z() - p1.z());
            int steps = Math.max(1, Math.round(chord / stepM));
            for (int k = 0; k < steps; k++) {
                float t = k / (float) steps;
                out.add(new RoadSpec.Point(
                        catmull(p0.x(), p1.x(), p2.x(), p3.x(), t), catmull(p0.z(), p1.z(), p2.z(), p3.z(), t)));
            }
        }
        out.add(control.get(n - 1));
        return out;
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f
                * ((2f * p1)
                        + (-p0 + p2) * t
                        + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                        + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }

    /** In-place Gaussian blur of a 1-D profile, sigma in samples. */
    static void gaussianSmooth(float[] profile, float sigmaSamples) {
        if (profile.length < 3 || sigmaSamples <= 0f) {
            return;
        }
        int radius = Math.max(1, Math.round(sigmaSamples * 3f));
        float[] kernel = new float[radius * 2 + 1];
        float sum = 0f;
        for (int k = -radius; k <= radius; k++) {
            float v = (float) StrictMath.exp(-(k * k) / (2.0 * sigmaSamples * sigmaSamples));
            kernel[k + radius] = v;
            sum += v;
        }
        for (int k = 0; k < kernel.length; k++) {
            kernel[k] /= sum;
        }
        float[] source = profile.clone();
        for (int s = 0; s < profile.length; s++) {
            float acc = 0f;
            for (int k = -radius; k <= radius; k++) {
                // Clamped at the ends: a road's first station has no ground before it, and wrapping
                // would smooth the start of the road toward its finish.
                int sample = Math.min(source.length - 1, Math.max(0, s + k));
                acc += source[sample] * kernel[k + radius];
            }
            profile[s] = acc;
        }
    }

    /** The camber, dropping from crown to edge at {@link RoadSpec#MAX_CROSSFALL_PCT}. */
    private static float crossfall(float distanceM, float halfWidthM) {
        float across = Math.min(distanceM, halfWidthM);
        return across * (RoadSpec.MAX_CROSSFALL_PCT / 100f);
    }

    private static float smoothstep(float edge0, float edge1, float t) {
        float x = clamp((t - 0f) / 1f, 0f, 1f);
        float s = x * x * (3f - 2f * x);
        return edge0 + (edge1 - edge0) * s;
    }

    /**
     * The station nearest a world point.
     *
     * <p>Linear over the polyline. A spatial index would be faster and is not worth it: this runs
     * once per arena load over the corridor's bounding box, not per tick.
     */
    private static int nearestStation(List<RoadSpec.Point> centre, float x, float z) {
        int best = 0;
        float bestSq = Float.MAX_VALUE;
        for (int s = 0; s < centre.size(); s++) {
            RoadSpec.Point p = centre.get(s);
            float dx = p.x() - x;
            float dz = p.z() - z;
            float sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = s;
            }
        }
        return best;
    }

    private static float distanceTo(RoadSpec.Point p, float x, float z) {
        return (float) Math.hypot(p.x() - x, p.z() - z);
    }

    /** {@code [minI, minJ, maxI, maxJ]} of the cells a corridor can reach, clamped to the grid. */
    private static int[] corridorBounds(
            List<RoadSpec.Point> centre, float reach, TerrainParams params, float originX, float originZ) {

        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (RoadSpec.Point p : centre) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        int grid = params.gridSize();
        float cell = params.cellSizeM();
        return new int[] {
            clampIndex((int) Math.floor((minX - reach - originX) / cell), grid),
            clampIndex((int) Math.floor((minZ - reach - originZ) / cell), grid),
            clampIndex((int) Math.ceil((maxX + reach - originX) / cell), grid),
            clampIndex((int) Math.ceil((maxZ + reach - originZ) / cell), grid)
        };
    }

    private static float sampleHeight(
            float[] heights, TerrainParams params, float originX, float originZ, RoadSpec.Point p) {
        int grid = params.gridSize();
        int i = clampIndex(Math.round((p.x() - originX) / params.cellSizeM()), grid);
        int j = clampIndex(Math.round((p.z() - originZ) / params.cellSizeM()), grid);
        return heights[j * grid + i];
    }

    private static float lengthOf(List<RoadSpec.Point> centre) {
        float total = 0f;
        for (int s = 1; s < centre.size(); s++) {
            total += distanceTo(
                    centre.get(s - 1), centre.get(s).x(), centre.get(s).z());
        }
        return total;
    }

    /** The steepest longitudinal grade the finished profile holds, per cent. */
    private static float gradeOf(float[] profile, float stepM) {
        float worst = 0f;
        for (int s = 1; s < profile.length; s++) {
            worst = Math.max(worst, Math.abs(profile[s] - profile[s - 1]) / stepM * 100f);
        }
        return worst;
    }

    private static int clampIndex(int i, int grid) {
        return Math.max(0, Math.min(i, grid - 1));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * What one carve did, for the arena's generation report (D16-S4.7).
     *
     * @param maxCutM the deepest the carve dug below the original ground — a cutting
     * @param maxFillM the highest it raised it — an embankment
     * @param maxGradePct the steepest grade the finished profile holds, which AC-D16-5 checks
     */
    public record Report(
            String roadId,
            float lengthM,
            int carriagewayCells,
            int vergeCells,
            float maxCutM,
            float maxFillM,
            float maxGradePct) {}
}
