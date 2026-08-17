/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import java.util.List;
import java.util.Objects;

/**
 * One road corridor: a centreline, a width, and the surface it lays down
 * (docs/16_procedural_arena_generation.md#D16-S4.3).
 *
 * <p><b>The elevation is not here, and that is the point</b> (D16-R8). A road's height is derived
 * from the terrain it crosses, smoothed and then grade-limited (D16-S5.4). An authored elevation
 * either floats or buries itself the first time the seed changes, and under D16-R6b the seed changes
 * every match.
 *
 * @param id the road's id within its arena, for the report
 * @param surface what the carriageway is made of
 * @param widthM carriageway width, at least {@link #MIN_WIDTH_M} (D16-R9)
 * @param shoulderM verge width either side of the carriageway
 * @param verge what the shoulder is made of
 * @param maxGradePct the longitudinal grade ceiling the limiter enforces (D16-R35 step 2)
 * @param spline a Catmull-Rom control polygon in world XZ, at least two points (D16-R8)
 */
public record RoadSpec(
        String id,
        Surface surface,
        float widthM,
        float shoulderM,
        Surface verge,
        float maxGradePct,
        List<Point> spline) {

    /**
     * Metres. The narrowest a road may be (D16-R9).
     *
     * <p>Below this a road is not a corridor two vehicles fight on, it is a rut — and the carve's
     * falloff would be wider than the flat part it is supposed to blend away from.
     */
    public static final float MIN_WIDTH_M = 6.0f;

    /**
     * Metres. How far past the shoulder the carve blends back into untouched terrain (D16-R35).
     *
     * <p>This is what produces cut and fill for free (D16-R36): where the land was above the road
     * the blend digs a cutting, where it was below the blend raises an embankment. Neither is
     * authored and both are what makes a highway interesting to fight on.
     */
    public static final float FALLOFF_M = 12.0f;

    /** Per cent. The camber from crown to edge, as a fraction of half-width (D16-R35 step 3). */
    public static final float MAX_CROSSFALL_PCT = 2.5f;

    /** Metres. The sigma of the Gaussian that smooths the sampled ground profile (D16-R35 step 1). */
    public static final float PROFILE_SMOOTH_SIGMA_M = 25.0f;

    public RoadSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(verge, "verge");
        if (widthM < MIN_WIDTH_M) {
            throw new IllegalArgumentException(
                    "road " + id + " is " + widthM + " m wide; D16-R9 requires at least " + MIN_WIDTH_M);
        }
        if (shoulderM < 0f) {
            throw new IllegalArgumentException("road " + id + " has a negative shoulder");
        }
        if (!(maxGradePct > 0f)) {
            throw new IllegalArgumentException("road " + id + " has maxGradePct " + maxGradePct
                    + "; a road with no grade budget cannot follow any landform (D16-R35)");
        }
        if (spline == null || spline.size() < 2) {
            throw new IllegalArgumentException("road " + id + " needs at least two spline points (D16-R8)");
        }
        spline = List.copyOf(spline);
    }

    /** Half the carriageway. */
    public float halfWidthM() {
        return widthM * 0.5f;
    }

    /** How far from the centreline the carve reaches at all. */
    public float reachM() {
        return halfWidthM() + shoulderM + FALLOFF_M;
    }

    /** A control point on a road's centreline, in world XZ. */
    public record Point(float x, float z) {}
}
