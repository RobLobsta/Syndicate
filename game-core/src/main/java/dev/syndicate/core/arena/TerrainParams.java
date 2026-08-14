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
 * @param biome which layer stack the generator runs
 * @param reliefM peak-to-trough amplitude of the broad landform, metres
 * @param baseFrequency cycles per metre of the landform's first octave
 * @param octaves octaves of fractal noise in the landform
 * @param duneWindDeg prevailing wind bearing; dunes run transverse to it (D00-R17)
 * @param duneWavelengthM metres between dune crests
 * @param duneHeightM crest-to-trough dune height, metres, before the slip-face correction
 * @param borderWidthM width of the impassable rim at the arena edge, metres
 * @param borderRiseM how far that rim rises, metres
 * @param maxDrivableSlopeDeg at or below this, ground is drivable and navigable
 */
public record TerrainParams(
        long seed,
        float cellSizeM,
        int gridSize,
        Biome biome,
        float reliefM,
        float baseFrequency,
        int octaves,
        float duneWindDeg,
        float duneWavelengthM,
        float duneHeightM,
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

    /** Which layer stack the generator runs, and which surfaces it lays down (D16-R6). */
    public enum Biome {
        /** Dune field with rock showing through on the steepest faces. */
        DESERT,
        /** Gentle relief, tarmac throughout. A flat arena with slopes rather than a landscape. */
        TARMAC_FLAT
    }

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
        if (duneWavelengthM <= 0f) {
            throw new IllegalArgumentException("duneWavelengthM must be positive, got " + duneWavelengthM);
        }
        if (maxDrivableSlopeDeg <= 0f || maxDrivableSlopeDeg >= 90f) {
            throw new IllegalArgumentException("maxDrivableSlopeDeg must be in (0, 90), got " + maxDrivableSlopeDeg);
        }
        biome = biome == null ? Biome.DESERT : biome;
    }

    /** The span this grid covers, metres. Must equal the arena's bounds span (D16-R5). */
    public float spanM() {
        return (gridSize - 1) * cellSizeM;
    }

    /** The number of samples in the field. */
    public int sampleCount() {
        return gridSize * gridSize;
    }

    /** A desert of the given span and seed, on the defaults the shipped arena uses. */
    public static TerrainParams desert(long seed, float spanM) {
        int grid = Math.round(spanM / DEFAULT_CELL_SIZE_M) + 1;
        if ((grid & 1) == 0) {
            grid++;
        }
        return new TerrainParams(
                seed,
                DEFAULT_CELL_SIZE_M,
                grid,
                Biome.DESERT,
                16.0f,
                0.0035f,
                5,
                115.0f,
                90.0f,
                9.0f,
                60.0f,
                40.0f,
                MAX_DRIVABLE_SLOPE_DEG);
    }
}
