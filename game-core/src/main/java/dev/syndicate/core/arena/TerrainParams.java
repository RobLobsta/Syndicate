/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

/**
 * An arena's {@code terrain} block (docs/16_procedural_arena_generation.md#D16-S4.2).
 *
 * <p>Everything the generator needs and nothing it does not. An arena that declares no terrain block
 * has no {@code TerrainParams} and is the flat floor and box walls that shipped before this existed
 * (D16-R4) — which remains a legal arena, and is what every physics regression fixture is.
 *
 * @param seed the terrain seed; the whole field is a pure function of this and these parameters
 * @param cellSizeM metres between height samples
 * @param gridSize samples per side; odd, at least {@link #MIN_GRID}, at most {@link #MAX_GRID}
 * @param theme what kind of place this is; it fixes the relief layer and the surface palette
 * @param reliefM peak-to-trough amplitude of the broad landform, metres
 * @param baseFrequency cycles per metre of the landform's first octave
 * @param octaves octaves of fractal noise in the landform
 * @param featureBearingDeg bearing the relief layer is oriented to, where it has one (D00-R17)
 * @param featureWavelengthM metres between relief features — dune crests, or heap centres
 * @param featureHeightM height of a relief feature, metres, before any correction
 * @param borderWidthM width of the impassable rim at the arena edge, metres
 * @param borderRiseM how far that rim rises, metres
 * @param maxDrivableSlopeDeg at or below this, ground is drivable and navigable
 */
public record TerrainParams(
        long seed,
        float cellSizeM,
        int gridSize,
        ArenaTheme theme,
        float reliefM,
        float baseFrequency,
        int octaves,
        float featureBearingDeg,
        float featureWavelengthM,
        float featureHeightM,
        float borderWidthM,
        float borderRiseM,
        float maxDrivableSlopeDeg) {

    /** Default metres between height samples (D16-R1, {@code TERRAIN_CELL_M}). */
    public static final float DEFAULT_CELL_SIZE_M = 1.0f;

    /** Hard cap on samples per side (D16-R1, {@code TERRAIN_MAX_GRID}). */
    public static final int MAX_GRID = 1025;

    /** Fewest samples per side. Below this the border rim and the landform have no room apart. */
    public static final int MIN_GRID = 65;

    /**
     * Degrees. At or below this, terrain is drivable and navigable (D16-R1).
     *
     * <p>This sits <b>below</b> {@link #SAND_REPOSE_DEG} deliberately, and the gap is the game
     * (D16-R2): a dune's windward face is shallow enough to climb and its slip face stands at the
     * angle of repose, which is not. One physical constant produces a surface that is a ramp from one
     * direction and a wall from the other. Bringing these two numbers together loses that, and
     * should only be done by someone who has decided to.
     */
    public static final float MAX_DRIVABLE_SLOPE_DEG = 25.0f;

    /** Degrees. The angle of repose of dry sand; a dune's slip face stands here (D16-R1). */
    public static final float SAND_REPOSE_DEG = 33.0f;

    public TerrainParams {
        if (gridSize < MIN_GRID || gridSize > MAX_GRID) {
            throw new IllegalArgumentException(
                    "gridSize must be in [" + MIN_GRID + ", " + MAX_GRID + "], got " + gridSize);
        }
        if ((gridSize & 1) == 0) {
            // Odd, so the grid has a centre sample rather than a centre cell. Every symmetry in the
            // border falloff and every "distance from the middle" test is then exact rather than
            // half a cell out on one side.
            throw new IllegalArgumentException("gridSize must be odd, got " + gridSize);
        }
        if (cellSizeM <= 0f) {
            throw new IllegalArgumentException("cellSizeM must be positive, got " + cellSizeM);
        }
        if (octaves < 1) {
            throw new IllegalArgumentException("octaves must be at least 1, got " + octaves);
        }
        if (featureWavelengthM <= 0f) {
            throw new IllegalArgumentException("featureWavelengthM must be positive, got " + featureWavelengthM);
        }
        if (maxDrivableSlopeDeg <= 0f || maxDrivableSlopeDeg >= 90f) {
            throw new IllegalArgumentException("maxDrivableSlopeDeg must be in (0, 90), got " + maxDrivableSlopeDeg);
        }
        theme = theme == null ? ArenaTheme.DESERT_HIGHWAY : theme;
    }

    /** The span this grid covers, metres. Must equal the arena's bounds span (D16-R5). */
    public float spanM() {
        return (gridSize - 1) * cellSizeM;
    }

    /** The number of samples in the field. */
    public int sampleCount() {
        return gridSize * gridSize;
    }

    /**
     * A terrain of the given theme, span and seed, on the theme's own numbers.
     *
     * <p>This is how an arena should be built: a theme and a seed. Every value below comes from
     * {@link ArenaTheme.Shape}, so "make a scrapyard" is one argument rather than eight numbers a
     * caller has to know go together.
     */
    public static TerrainParams of(ArenaTheme theme, long seed, float spanM) {
        int grid = Math.round(spanM / DEFAULT_CELL_SIZE_M) + 1;
        if ((grid & 1) == 0) {
            grid++;
        }
        ArenaTheme.Shape shape = theme.shape();
        return new TerrainParams(
                seed,
                DEFAULT_CELL_SIZE_M,
                grid,
                theme,
                shape.reliefM(),
                shape.baseFrequency(),
                shape.octaves(),
                shape.featureBearingDeg(),
                shape.featureWavelengthM(),
                shape.featureHeightM(),
                shape.borderWidthM(),
                shape.borderRiseM(),
                MAX_DRIVABLE_SLOPE_DEG);
    }

    /** A desert of the given span and seed. */
    public static TerrainParams desert(long seed, float spanM) {
        return of(ArenaTheme.DESERT_HIGHWAY, seed, spanM);
    }
}
