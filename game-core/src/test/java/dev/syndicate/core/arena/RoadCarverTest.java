/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Road carving (docs/16_procedural_arena_generation.md#D16-S5.4). */
@Tag("unit")
class RoadCarverTest {

    /**
     * Per cent. Slack for measuring a grade back out of the floats it was clamped in.
     *
     * <p>The limiter clamps each station to exactly {@code +/- limit} of its neighbour, so a road
     * against a landform it cannot follow sits *on* its budget rather than under it — and computing
     * the grade back out of two clamped floats lands a hundred-thousandth over. Without this the
     * tight-budget case fails at 1.5000105 against 1.5, which measures the arithmetic and not the road.
     */
    private static final float GRADE_ROUNDING_PCT = 1e-3f;

    /** A road straight across the middle of the arena, corner to corner. */
    private static RoadSpec diagonalRoad(float maxGradePct) {
        return new RoadSpec(
                "test_road",
                Surface.TARMAC,
                14.0f,
                3.0f,
                Surface.GRAVEL,
                maxGradePct,
                List.of(new RoadSpec.Point(-250f, -250f), new RoadSpec.Point(0f, 0f), new RoadSpec.Point(250f, 250f)));
    }

    private static TerrainField desert(long seed, List<RoadSpec> roads) {
        TerrainParams params = TerrainParams.of(ArenaTheme.DESERT_HIGHWAY, seed, 600f);
        return TerrainGenerator.generate(
                new Vector3(-300f, -40f, -300f), new Vector3(300f, 120f, 300f), 0f, params, List.of(), roads);
    }

    /** T-D16-6: the carve lays a contiguous ribbon of its own surface along the centreline. */
    @Test
    void theCarriagewayIsTarmacAlongTheWholeSpline() {
        TerrainField field = desert(11L, List.of(diagonalRoad(6f)));
        int tarmacStations = 0;
        for (float t = -240f; t <= 240f; t += 10f) {
            if (field.surfaceAt(t, t) == Surface.TARMAC) {
                tarmacStations++;
            }
        }
        assertThat(tarmacStations)
                .as("every station on the centreline is carriageway")
                .isEqualTo(49);
    }

    /** D16-R11: without a road the desert has no tarmac at all, so the ribbon is the carve's doing. */
    @Test
    void anUncarvedDesertHasNoTarmac() {
        TerrainField field = desert(11L, List.of());
        for (float t = -240f; t <= 240f; t += 10f) {
            assertThat(field.surfaceAt(t, t)).isNotEqualTo(Surface.TARMAC);
        }
    }

    /**
     * AC-D16-5: the finished profile holds its grade budget at every station.
     *
     * <p>Asserted on the carve's own report rather than by sampling the height grid along the
     * centreline. The rule is about the <b>profile</b> — the elevation the limiter produced — and a
     * grid read is that profile plus two artefacts that have nothing to do with grade: cells are
     * assigned to their nearest station, so a diagonal road's cells straddle 1.4 stations each, and
     * {@code heightAt} interpolates between cells carved at different crossfall distances. Sampling
     * the grid measures 9% on a road whose profile never exceeds 6%, and would have this test
     * failing for a reason AC-D16-5 does not care about.
     */
    @Test
    void theCarvedGradeStaysWithinItsBudget() {
        TerrainParams params = TerrainParams.of(ArenaTheme.DESERT_HIGHWAY, 11L, 600f);
        TerrainField bare = TerrainGenerator.generate(
                new Vector3(-300f, -40f, -300f), new Vector3(300f, 120f, 300f), 0f, params, List.of(), List.of());
        float[] heights = new float[params.sampleCount()];
        byte[] surfaces = new byte[params.sampleCount()];
        for (int j = 0; j < params.gridSize(); j++) {
            for (int i = 0; i < params.gridSize(); i++) {
                heights[j * params.gridSize() + i] = bare.heightAtSample(i, j);
            }
        }
        List<RoadCarver.Report> reports =
                RoadCarver.carve(heights, surfaces, params, -300f, -300f, List.of(diagonalRoad(6f)));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).maxGradePct())
                .as("steepest longitudinal grade the limited profile holds")
                .isLessThanOrEqualTo(6f + GRADE_ROUNDING_PCT);
    }

    /** A grade budget the landform cannot meet is honoured anyway: the road cuts through (D16-E6). */
    @Test
    void aTightGradeBudgetIsHonouredByCuttingThroughTheLandform() {
        TerrainParams params = TerrainParams.of(ArenaTheme.DESERT_HIGHWAY, 11L, 600f);
        TerrainField bare = TerrainGenerator.generate(
                new Vector3(-300f, -40f, -300f), new Vector3(300f, 120f, 300f), 0f, params, List.of(), List.of());
        float[] heights = new float[params.sampleCount()];
        byte[] surfaces = new byte[params.sampleCount()];
        for (int j = 0; j < params.gridSize(); j++) {
            for (int i = 0; i < params.gridSize(); i++) {
                heights[j * params.gridSize() + i] = bare.heightAtSample(i, j);
            }
        }
        List<RoadCarver.Report> reports =
                RoadCarver.carve(heights, surfaces, params, -300f, -300f, List.of(diagonalRoad(1.5f)));
        assertThat(reports.get(0).maxGradePct()).isLessThanOrEqualTo(1.5f + GRADE_ROUNDING_PCT);
        // A road that flat across dune country has to dig, which is D16-R36's cutting.
        assertThat(reports.get(0).maxCutM()).isGreaterThan(1f);
    }

    /** D16-R36: cut and fill fall out of the falloff, so a carve through dunes does both. */
    @Test
    void carvingThroughDunesProducesBothCuttingsAndEmbankments() {
        TerrainParams params = TerrainParams.of(ArenaTheme.DESERT_HIGHWAY, 11L, 600f);
        Vector3 min = new Vector3(-300f, -40f, -300f);
        Vector3 max = new Vector3(300f, 120f, 300f);
        TerrainField bare = TerrainGenerator.generate(min, max, 0f, params, List.of(), List.of());
        TerrainField carved = TerrainGenerator.generate(min, max, 0f, params, List.of(), List.of(diagonalRoad(6f)));

        float deepestCut = 0f;
        float highestFill = 0f;
        for (float t = -240f; t <= 240f; t += 2f) {
            float delta = carved.heightAt(t, t) - bare.heightAt(t, t);
            deepestCut = Math.min(deepestCut, delta);
            highestFill = Math.max(highestFill, delta);
        }
        assertThat(deepestCut).as("the road cuts into at least one dune").isLessThan(-1f);
        assertThat(highestFill)
                .as("the road is embanked across at least one trough")
                .isGreaterThan(1f);
    }

    /** D16-R9: a road narrower than a corridor is a content error, not a narrow road. */
    @Test
    void aRoadNarrowerThanTheMinimumIsRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RoadSpec(
                        "rut",
                        Surface.TARMAC,
                        4.0f,
                        1.0f,
                        Surface.GRAVEL,
                        6.0f,
                        List.of(new RoadSpec.Point(0f, 0f), new RoadSpec.Point(10f, 10f)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** D16-R8: a spline needs two points to be a line. */
    @Test
    void aSplineOfOnePointIsRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new RoadSpec(
                        "dot", Surface.TARMAC, 14.0f, 1.0f, Surface.GRAVEL, 6.0f, List.of(new RoadSpec.Point(0f, 0f)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** G11: the same seed and the same road carve to the same ground, every time. */
    @Test
    void theCarveIsDeterministic() {
        TerrainField a = desert(29L, List.of(diagonalRoad(6f)));
        TerrainField b = desert(29L, List.of(diagonalRoad(6f)));
        for (float t = -240f; t <= 240f; t += 7f) {
            assertThat(a.heightAt(t, t)).isEqualTo(b.heightAt(t, t));
            assertThat(a.surfaceAt(t, t)).isEqualTo(b.surfaceAt(t, t));
        }
    }
}
