/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import com.badlogic.gdx.math.Vector3;
import java.util.BitSet;
import java.util.Objects;

/**
 * The generated ground, and the one way anything asks about it
 * (docs/16_procedural_arena_generation.md#D16-S5.2, #D16-S5.9).
 *
 * <p>Three grids of the same shape, generated once at load and immutable for the match: the height
 * of the ground at every sample, what that ground is made of, and whether a vehicle can be there.
 * Everything downstream — collision, the render mesh, wheel grip, tyre audio, bot navigation, spawn
 * validation and structure placement — reads these and nothing else.
 *
 * <p><b>Why a regular grid and not a mesh</b> (DEC-069). A query here is index arithmetic: a world
 * position maps to a cell by division, and four samples give a height. The same question against a
 * triangle mesh is a ray cast, and the callers asking it are wheel physics and bot steering, sixty
 * times a second, several times each. The other half of the reason is Bullet: its convex ray test
 * degrades with shape size (DISC-017), which is why the flat arena's floor is an infinite plane
 * rather than a box, and a height field is ray-tested per triangle at any extent.
 *
 * <p><b>Heights are stored relative to {@code groundY}</b> (D16-R28) and every query returns world
 * space. The offset lives in one place so that a caller cannot forget it — which matters more than
 * usual here, because Bullet centres a height field on its own bounding box rather than on either
 * end of it, and there is already one offset in this system waiting to be got wrong (D16-R48).
 *
 * <p>Immutable and shared: one instance per arena per process, like {@code ArenaDef}.
 */
public final class TerrainField {

    private final TerrainParams params;
    private final float originX;
    private final float originZ;
    private final float groundY;

    /** Metres above {@code groundY}, row-major in {@code (z, x)} order (D16-R28). */
    private final float[] heights;

    /** {@link Surface} ordinals, one per sample, parallel to {@link #heights}. */
    private final byte[] surfaces;

    /** Set where a vehicle can be: slope within budget, and nothing standing there. */
    private final BitSet drivable;

    private final float minHeight;
    private final float maxHeight;

    TerrainField(
            TerrainParams params,
            float originX,
            float originZ,
            float groundY,
            float[] heights,
            byte[] surfaces,
            BitSet drivable) {

        this.params = Objects.requireNonNull(params, "params");
        this.originX = originX;
        this.originZ = originZ;
        this.groundY = groundY;
        this.heights = Objects.requireNonNull(heights, "heights");
        this.surfaces = Objects.requireNonNull(surfaces, "surfaces");
        this.drivable = Objects.requireNonNull(drivable, "drivable");
        if (heights.length != params.sampleCount() || surfaces.length != params.sampleCount()) {
            throw new IllegalArgumentException("grid arrays must hold " + params.sampleCount() + " samples");
        }
        float lo = Float.POSITIVE_INFINITY;
        float hi = Float.NEGATIVE_INFINITY;
        for (float h : heights) {
            lo = Math.min(lo, h);
            hi = Math.max(hi, h);
        }
        this.minHeight = lo;
        this.maxHeight = hi;
    }

    public TerrainParams params() {
        return params;
    }

    /** The datum heights are stored above; world Y of a sample is {@code groundY + height}. */
    public float groundY() {
        return groundY;
    }

    /** Lowest sample, metres above {@link #groundY()}. */
    public float minHeight() {
        return minHeight;
    }

    /** Highest sample, metres above {@link #groundY()}. */
    public float maxHeight() {
        return maxHeight;
    }

    /**
     * The raw height grid, metres above {@link #groundY()}, row-major in {@code (z, x)}.
     *
     * <p>Exposed rather than copied because the two callers that want it — the collision shape and
     * the render mesh — want all of it, and copying 1.4 MB to hand it over twice is not a safety
     * property worth paying for. Nothing writes to it after construction.
     */
    public float[] heights() {
        return heights;
    }

    /** World X of sample column {@code i}. */
    public float sampleX(int i) {
        return originX + i * params.cellSizeM();
    }

    /** World Z of sample row {@code j}. */
    public float sampleZ(int j) {
        return originZ + j * params.cellSizeM();
    }

    /** Height at a sample, metres above {@link #groundY()}. Indices are clamped (D16-R53). */
    public float heightAtSample(int i, int j) {
        return heights[index(clampIndex(i), clampIndex(j))];
    }

    /** Surface at a sample. Indices are clamped. */
    public Surface surfaceAtSample(int i, int j) {
        return Surface.values()[surfaces[index(clampIndex(i), clampIndex(j))]];
    }

    /**
     * Ground height at a world position, metres in world space, bilinear between samples.
     *
     * <p>Out of bounds clamps to the edge rather than returning a sentinel (D16-R53). A vehicle
     * outside the grid is up the border rim or already falling to the kill plane, and a query that
     * answered "no ground" there would force wheel physics and bot steering — the two callers — to
     * each invent a fallback.
     */
    public float heightAt(float worldX, float worldZ) {
        float gx = clampCoord((worldX - originX) / params.cellSizeM());
        float gz = clampCoord((worldZ - originZ) / params.cellSizeM());
        int i0 = (int) gx;
        int j0 = (int) gz;
        int i1 = clampIndex(i0 + 1);
        int j1 = clampIndex(j0 + 1);
        float fx = gx - i0;
        float fz = gz - j0;

        float h00 = heights[index(i0, j0)];
        float h10 = heights[index(i1, j0)];
        float h01 = heights[index(i0, j1)];
        float h11 = heights[index(i1, j1)];
        float a = h00 + fx * (h10 - h00);
        float b = h01 + fx * (h11 - h01);
        return groundY + a + fz * (b - a);
    }

    /**
     * The upward unit normal at a world position, from central differences on the height grid.
     *
     * @param out written and returned, so a per-tick caller allocates nothing
     */
    public Vector3 normalAt(float worldX, float worldZ, Vector3 out) {
        int i = clampIndex(Math.round((worldX - originX) / params.cellSizeM()));
        int j = clampIndex(Math.round((worldZ - originZ) / params.cellSizeM()));
        float twoCells = 2f * params.cellSizeM();
        float dhdx = (heightAtSample(i + 1, j) - heightAtSample(i - 1, j)) / twoCells;
        float dhdz = (heightAtSample(i, j + 1) - heightAtSample(i, j - 1)) / twoCells;
        return out.set(-dhdx, 1f, -dhdz).nor();
    }

    /** Slope from horizontal at a world position, degrees. */
    public float slopeDegAt(float worldX, float worldZ) {
        int i = clampIndex(Math.round((worldX - originX) / params.cellSizeM()));
        int j = clampIndex(Math.round((worldZ - originZ) / params.cellSizeM()));
        return slopeDegAtSample(i, j);
    }

    /** Slope from horizontal at a sample, degrees. Indices are clamped. */
    public float slopeDegAtSample(int i, int j) {
        float twoCells = 2f * params.cellSizeM();
        float dhdx = (heightAtSample(i + 1, j) - heightAtSample(i - 1, j)) / twoCells;
        float dhdz = (heightAtSample(i, j + 1) - heightAtSample(i, j - 1)) / twoCells;
        double gradient = Math.sqrt((double) dhdx * dhdx + (double) dhdz * dhdz);
        return (float) StrictMath.toDegrees(StrictMath.atan(gradient));
    }

    /**
     * What the ground is made of at a world position.
     *
     * <p><b>Nearest sample, never interpolated</b> (D16-R52). A surface is a discrete kind and a lerp
     * between sand and tarmac is not a surface. A road edge is therefore a hard line one cell wide,
     * which is what a road edge is.
     */
    public Surface surfaceAt(float worldX, float worldZ) {
        int i = clampIndex(Math.round((worldX - originX) / params.cellSizeM()));
        int j = clampIndex(Math.round((worldZ - originZ) / params.cellSizeM()));
        return Surface.values()[surfaces[index(i, j)]];
    }

    /** Whether a vehicle can be at a world position: slope within budget, nothing standing there. */
    public boolean isDrivable(float worldX, float worldZ) {
        int i = clampIndex(Math.round((worldX - originX) / params.cellSizeM()));
        int j = clampIndex(Math.round((worldZ - originZ) / params.cellSizeM()));
        return drivable.get(index(i, j));
    }

    /** Whether a sample is drivable. Indices are clamped. */
    public boolean isDrivableSample(int i, int j) {
        return drivable.get(index(clampIndex(i), clampIndex(j)));
    }

    /** How much of the arena a vehicle can be on, as a fraction of its samples. */
    public float drivableFraction() {
        return drivable.cardinality() / (float) params.sampleCount();
    }

    /**
     * A hash of the quantised field, for the cross-process agreement check (D16-R25, R62).
     *
     * <p>Heights are quantised to a millimetre before hashing. Comparing raw bits would make the
     * check stricter than the claim it is testing — two processes must agree on the ground, not on
     * the last bit of a float — and would fail on a difference no vehicle could ever be affected by.
     * A millimetre is four orders of magnitude below the collision margin.
     */
    public long fieldHash() {
        long h = 0xCBF29CE484222325L;
        for (int k = 0; k < heights.length; k++) {
            long q = Math.round((double) heights[k] * 1000.0);
            h = (h ^ q) * 0x100000001B3L;
            h = (h ^ surfaces[k]) * 0x100000001B3L;
        }
        return h;
    }

    private int index(int i, int j) {
        return j * params.gridSize() + i;
    }

    private int clampIndex(int i) {
        return i < 0 ? 0 : Math.min(i, params.gridSize() - 1);
    }

    /** Clamps a grid coordinate so that the sample after it is also in range. */
    private float clampCoord(float g) {
        float max = params.gridSize() - 1;
        return g < 0f ? 0f : Math.min(g, max);
    }
}
