/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** A road with the same profile as {@link #diagonalRoad} but reaching into the border rise. */
    private static RoadSpec overlongRoad() {
        return new RoadSpec(
                "test_road_overlong",
                Surface.TARMAC,
                14.0f,
                3.0f,
                Surface.GRAVEL,
                6f,
                List.of(new RoadSpec.Point(-290f, -290f), new RoadSpec.Point(0f, 0f), new RoadSpec.Point(290f, 290f)));
    }

    /**
     * DISC-062: a spline that reaches the rim carves a canyon through it, and that is an authoring
     * error at load rather than eight silently rejected seeds.
     *
     * <p>±290 m is the extent that actually shipped and had to be withdrawn; ±250 m is what
     * {@link #diagonalRoad} uses and is fine. The failure has to name the road, because the whole
     * cost of DISC-062 was three stages of downstream messages that never mentioned one.
     */
    @Test
    void aRoadReachingTheBorderRiseIsRejected() {
        assertThatThrownBy(() -> desert(12345L, List.of(overlongRoad())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test_road_overlong")
                .hasMessageContaining("border rise");
    }

    /**
     * The extent guard is measured against the arena it is generating, not a remembered number.
     *
     * <p>This is the property that makes the guard usable and the reason the scrapyard's haul road
     * could be shipped at all. The two arenas differ in both size and border width — the desert is
     * 601 samples at 1 m behind a 60 m rim, the scrapyard 301 at 1 m behind a 45 m one — so the
     * same spline is safe on one and a canyon on the other, and DISC-062's cost was that there was
     * no way to check the second short of re-deriving the first by hand.
     */
    @Test
    void theExtentGuardIsMeasuredAgainstEachArenaSOwnRim() {
        TerrainParams desert = TerrainParams.of(ArenaTheme.DESERT_HIGHWAY, 12345L, 600f);
        TerrainParams yard = TerrainParams.of(ArenaTheme.SCRAPYARD, 12345L, 300f);

        // A spline that is comfortably inside the desert and straight through the scrapyard's rim.
        List<RoadSpec> road = List.of(new RoadSpec(
                "test_road_shared",
                Surface.TARMAC,
                12.0f,
                2.5f,
                Surface.GRAVEL,
                8f,
                List.of(new RoadSpec.Point(-140f, -140f), new RoadSpec.Point(0f, 0f), new RoadSpec.Point(140f, 140f))));

        assertThatCode(() -> RoadCarver.validateExtent(desert, -300f, -300f, road))
                .as("140 m out in a 600 m arena is nowhere near its rim")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RoadCarver.validateExtent(yard, -150f, -150f, road))
                .as("the same spline is 10 m from the edge of a 300 m one")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("test_road_shared")
                .hasMessageContaining("border rise");

        assertThat(RoadCarver.safeDistanceToEdgeM(desert))
                .as("a taller, wider rim has to be given a wider berth than a shorter, narrower one")
                .isGreaterThan(RoadCarver.safeDistanceToEdgeM(yard));
    }

    /**
     * The scrapyard's haul road carves cleanly on every seed a match can draw (DISC-062).
     *
     * <p>Withdrawn rather than shipped broken when the desert's was fixed, because its rim sits
     * somewhere else and nothing could check it. It ships now, and the thing worth asserting is
     * that it holds across seeds rather than on the one it was authored against: an arena with
     * {@code seed: 0} generates a new landform every match (D16-R6b), so a road that only works on
     * one of them is a road that fails in front of a player.
     */
    @Test
    void theShippedScrapyardHaulRoadCarvesOnEverySeed() {
        RoadSpec haulRoad = new RoadSpec(
                "haul_road_main",
                Surface.TARMAC,
                12.0f,
                2.5f,
                Surface.GRAVEL,
                8f,
                List.of(
                        new RoadSpec.Point(-92f, -55f),
                        new RoadSpec.Point(-45f, -20f),
                        new RoadSpec.Point(10f, -30f),
                        new RoadSpec.Point(55f, 10f),
                        new RoadSpec.Point(92f, 55f)));

        for (long seed = 1L; seed <= 12L; seed++) {
            TerrainParams params = TerrainParams.of(ArenaTheme.SCRAPYARD, seed, 300f);
            TerrainField field = TerrainGenerator.generate(
                    new Vector3(-150f, -30f, -150f),
                    new Vector3(150f, 90f, 150f),
                    0f,
                    params,
                    List.of(),
                    List.of(haulRoad));

            assertThat(field.drivableFraction())
                    .as("the yard is still a yard on seed %d", seed)
                    .isGreaterThan(0.4f);
        }
    }

    /** The same guard does not fire on a road that stays inside the playable area (DISC-062). */
    @Test
    void aRoadInsideThePlayableAreaCarvesWell() {
        TerrainParams params = TerrainParams.of(ArenaTheme.DESERT_HIGHWAY, 12345L, 600f);
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
        assertThat(reports.get(0).maxCutM())
                .as("a road across open dunes digs a cutting, not a canyon")
                .isLessThan(RoadCarver.MAX_CUT_M);
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
