/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The generator of docs/16_procedural_arena_generation.md#D16-S5.1.
 *
 * <p>Two kinds of assertion here, and the distinction matters. The determinism tests
 * (T-D16-1 to T-D16-3) are the ones the wire format depends on: terrain is derived on every peer
 * rather than replicated (DEC-069), and that trade is only sound while two processes agree bit for
 * bit. The rest are <em>gameplay</em> properties — that a dune's slip face is a wall, that the rim
 * is impassable, that a spawn point can get out of where it started — each of which would otherwise
 * be discovered by driving into it.
 */
@Tag("unit")
class TerrainGeneratorTest {

    private static final long SEED = 20260814L;
    private static final float SPAN_M = 600f;
    private static final Vector3 MIN = new Vector3(-300f, -30f, -300f);
    private static final Vector3 MAX = new Vector3(300f, 90f, 300f);

    private static TerrainField desert() {
        return TerrainGenerator.generate(MIN, MAX, 0f, TerrainParams.desert(SEED, SPAN_M));
    }

    /** T-D16-1: the noise is a pure function of position and seed, to the last bit. */
    @Test
    void noiseIsAPureFunctionOfPositionAndSeed() {
        double a = TerrainNoise.fbm(123.25, -87.5, 0.0035, 5, SEED);
        double b = TerrainNoise.fbm(123.25, -87.5, 0.0035, 5, SEED);
        assertThat(a).as("same inputs, same bits").isEqualTo(b);

        // A different seed must actually change the field. A hash that folded the seed away — which
        // is an easy thing to write and impossible to see — would make every arena identical and
        // every determinism test above still pass.
        double other = TerrainNoise.fbm(123.25, -87.5, 0.0035, 5, SEED + 1);
        assertThat(other).as("a different seed is a different field").isNotEqualTo(a);

        // Negative coordinates are half the arena, and the floor-versus-truncate bug shows up only
        // there — as a seam through x=0 and z=0.
        assertThat(TerrainNoise.fbm(-40.5, -40.5, 0.01, 4, SEED))
                .isEqualTo(TerrainNoise.fbm(-40.5, -40.5, 0.01, 4, SEED));
        assertThat(TerrainNoise.fbm(-0.25, 0.25, 0.01, 4, SEED))
                .as("either side of the origin is not the same point")
                .isNotEqualTo(TerrainNoise.fbm(0.25, 0.25, 0.01, 4, SEED));
    }

    /** T-D16-2: the same seed generates the same grid, byte for byte. */
    @Test
    void theSameSeedGeneratesTheSameGrid() {
        TerrainField first = desert();
        TerrainField second = desert();
        assertThat(second.heights()).isEqualTo(first.heights());
        assertThat(second.fieldHash()).isEqualTo(first.fieldHash());
    }

    /**
     * T-D16-3: two independently built parameter sets agree.
     *
     * <p>Distinct from T-D16-2 on purpose. That one would still pass if the generator cached its
     * last result, or if {@code TerrainParams} were somehow carrying state from its first use. This
     * one constructs everything twice from literals.
     */
    @Test
    void twoIndependentlyBuiltGeneratorsAgree() {
        TerrainParams a = new TerrainParams(
                SEED, 1f, 601, TerrainParams.Biome.DESERT, 16f, 0.0035f, 5, 115f, 90f, 9f, 60f, 28f, 25f);
        TerrainParams b = new TerrainParams(
                SEED, 1f, 601, TerrainParams.Biome.DESERT, 16f, 0.0035f, 5, 115f, 90f, 9f, 60f, 28f, 25f);
        assertThat(TerrainGenerator.generate(MIN, MAX, 0f, b).fieldHash())
                .isEqualTo(TerrainGenerator.generate(MIN, MAX, 0f, a).fieldHash());
    }

    /**
     * T-D16-4: a dune's slip face stands at the angle of repose.
     *
     * <p>This is the property the desert biome's whole gameplay hangs on (D16-R2): the face is above
     * {@code MAX_DRIVABLE_SLOPE_DEG} and therefore a wall, while the windward side is below it and
     * therefore a ramp. Measured on the dune layer alone, because that is what the property is
     * about — the finished field adds the broad landform's own slope on top, which tilts individual
     * faces both ways and is measured separately by {@link #theFieldIsMostlyDrivableWithRealWalls()}.
     */
    @Test
    void duneSlipFacesStandAtTheAngleOfRepose() {
        TerrainParams params = TerrainParams.desert(SEED, SPAN_M);
        TerrainParams duneOnly = new TerrainParams(
                params.seed(),
                params.cellSizeM(),
                params.gridSize(),
                TerrainParams.Biome.DESERT,
                0f, // no broad relief
                params.baseFrequency(),
                params.octaves(),
                params.duneWindDeg(),
                params.duneWavelengthM(),
                params.duneHeightM(),
                0f, // no border rise
                0f,
                params.maxDrivableSlopeDeg());
        TerrainField field = TerrainGenerator.generate(MIN, MAX, 0f, duneOnly);

        // A slip face is a cell the ground drops off: steeper than a vehicle can climb. Every such
        // cell counts, not the steepest decile of them — a mean over a biased sample is how a
        // distribution that is wrong in its tail passes as a total that is right (DISC-033).
        int grid = params.gridSize();
        java.util.List<Float> faces = new java.util.ArrayList<>();
        for (int j = 2; j < grid - 2; j += 3) {
            for (int i = 2; i < grid - 2; i += 3) {
                float slope = field.slopeDegAtSample(i, j);
                if (slope > TerrainParams.MAX_DRIVABLE_SLOPE_DEG) {
                    faces.add(slope);
                }
            }
        }
        assertThat(faces).as("a dune field has faces at all").isNotEmpty();
        faces.sort(java.util.Comparator.naturalOrder());
        double sum = 0;
        for (float slope : faces) {
            sum += slope;
        }
        float mean = (float) (sum / faces.size());
        float p90 = faces.get((int) (faces.size() * 0.9));

        assertThat(mean)
                .as("slip faces stand at the angle of repose (D16-R33)")
                .isCloseTo(TerrainParams.SAND_REPOSE_DEG, within(4f));
        assertThat(mean)
                .as("and are therefore a wall, not a ramp (D16-R2)")
                .isGreaterThan(TerrainParams.MAX_DRIVABLE_SLOPE_DEG);
        // The tail as well as the mean: solving the slip width from the *local* phase gradient is
        // what keeps a warped-together pair of dunes from standing at a cliff, and a mean alone
        // cannot tell whether that worked.
        assertThat(p90)
                .as("and the steep tail is a dune face, not a cliff")
                .isLessThan(TerrainParams.SAND_REPOSE_DEG + 12f);
    }

    /**
     * T-D16-8: the border rim cannot be driven over, on any side, so it can replace the four walls.
     *
     * <p>Asserted as a <b>path</b> property rather than a slope one, and the difference matters. The
     * first version of this test took the steepest slope on a ring near the edge and required it to
     * pass the angle of repose. That is neither necessary nor sufficient: a rim can be impassable
     * with no single cell past repose, because a vehicle has to climb the whole 60 m of it, and it
     * can be passable with several — through a gully the ring sample never lands in. What is being
     * claimed is that a car cannot get out, so what is measured is whether drivable ground reaches
     * the arena's edge.
     */
    @Test
    void theBorderRimCannotBeDrivenOver() {
        TerrainField field = desert();
        int grid = field.params().gridSize();

        java.util.BitSet seen = new java.util.BitSet(grid * grid);
        java.util.Deque<int[]> queue = new java.util.ArrayDeque<>();
        int centre = grid / 2;
        seen.set(centre * grid + centre);
        queue.add(new int[] {centre, centre});
        int closestToEdge = grid;
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            int i = cell[0];
            int j = cell[1];
            closestToEdge = Math.min(closestToEdge, Math.min(Math.min(i, grid - 1 - i), Math.min(j, grid - 1 - j)));
            for (int[] next : new int[][] {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}}) {
                if (next[0] < 0 || next[1] < 0 || next[0] >= grid || next[1] >= grid) {
                    continue;
                }
                int index = next[1] * grid + next[0];
                if (seen.get(index) || !field.isDrivableSample(next[0], next[1])) {
                    continue;
                }
                seen.set(index);
                queue.add(next);
            }
        }

        assertThat(closestToEdge)
                .as("drivable ground never reaches the arena edge on any side (D16-R38, R39)")
                .isGreaterThan(0);
        // And not by one cell. A rim a car stops one metre short of is a rim it crests with momentum,
        // which the drivability grid — a static slope test — has no way to represent.
        assertThat(closestToEdge)
                .as("with real depth of rim left, not a one-cell margin")
                .isGreaterThan(10);
    }

    /** The dune field is one arena, not a set of parallel corridors (DISC-045). */
    @Test
    void theDrivableGroundIsOneConnectedRegion() {
        TerrainField field = desert();
        int grid = field.params().gridSize();
        int[] label = new int[grid * grid];
        int regions = 0;
        int largest = 0;
        for (int j = 0; j < grid; j++) {
            for (int i = 0; i < grid; i++) {
                if (label[j * grid + i] != 0 || !field.isDrivableSample(i, j)) {
                    continue;
                }
                regions++;
                int size = 0;
                java.util.Deque<int[]> queue = new java.util.ArrayDeque<>();
                label[j * grid + i] = regions;
                queue.add(new int[] {i, j});
                while (!queue.isEmpty()) {
                    int[] cell = queue.poll();
                    size++;
                    for (int[] next : new int[][] {
                        {cell[0] + 1, cell[1]}, {cell[0] - 1, cell[1]},
                        {cell[0], cell[1] + 1}, {cell[0], cell[1] - 1}
                    }) {
                        if (next[0] < 0 || next[1] < 0 || next[0] >= grid || next[1] >= grid) {
                            continue;
                        }
                        int index = next[1] * grid + next[0];
                        if (label[index] != 0 || !field.isDrivableSample(next[0], next[1])) {
                            continue;
                        }
                        label[index] = regions;
                        queue.add(next);
                    }
                }
                largest = Math.max(largest, size);
            }
        }

        float largestFraction = largest / (float) (grid * grid);
        // The failure this catches is not hypothetical: with no gaps in the crest field, every slip
        // face is a continuous wall and this arena measured 42 regions with the largest at 0.24 of
        // the arena. It now measures 0.69, which is 94% of all drivable ground.
        assertThat(largestFraction)
                .as("most of the arena is one region (DISC-045)")
                .isGreaterThan(0.6f);
        // The ratio is the assertion that means something: the absolute figure moves whenever the
        // rim or the relief changes how much of the arena is drivable at all, and says nothing about
        // whether what remains is connected.
        assertThat(largestFraction / field.drivableFraction())
                .as("and the isolated pockets are a small remainder, not a partition")
                .isGreaterThan(0.9f);
    }

    /**
     * The field is mostly drivable, and what is not is genuinely not.
     *
     * <p>The number that would make this arena bad is not "too steep" but "too flat": a dune field
     * whose faces all came out climbable is a field of bumps, and every claim made for the desert
     * biome would be false while every other test still passed.
     */
    @Test
    void theFieldIsMostlyDrivableWithRealWalls() {
        TerrainField field = desert();
        assertThat(field.drivableFraction())
                .as("most of the arena is drivable, or there is nowhere to fight")
                .isGreaterThan(0.55f);
        assertThat(field.drivableFraction())
                .as("but not all of it, or nothing is cover")
                .isLessThan(0.95f);
    }

    /** T-D16-9: interpolated height agrees exactly with the grid at sample points. */
    @Test
    void heightAtIsExactOnSamplesAndBoundedBetweenThem() {
        TerrainField field = desert();
        for (int j = 10; j < 400; j += 97) {
            for (int i = 10; i < 400; i += 89) {
                float world = field.heightAt(field.sampleX(i), field.sampleZ(j));
                assertThat(world).isCloseTo(field.groundY() + field.heightAtSample(i, j), within(1e-3f));
            }
        }
        // Halfway between two samples is between their heights — bilinear, not extrapolating.
        int i = 200;
        int j = 200;
        float a = field.heightAtSample(i, j);
        float b = field.heightAtSample(i + 1, j);
        float mid = field.heightAt(field.sampleX(i) + 0.5f * field.params().cellSizeM(), field.sampleZ(j));
        assertThat(mid).isBetween(Math.min(a, b) - 1e-3f, Math.max(a, b) + 1e-3f);
    }

    /** T-D16-9, out of bounds: every query clamps rather than returning a sentinel (D16-R53). */
    @Test
    void queriesOutsideTheGridClampToItsEdge() {
        TerrainField field = desert();
        assertThat(field.heightAt(-9000f, -9000f)).isEqualTo(field.heightAt(MIN.x, MIN.z));
        assertThat(field.heightAt(9000f, 9000f)).isEqualTo(field.heightAt(MAX.x, MAX.z));
        assertThat(field.surfaceAt(9000f, 0f)).isNotNull();
        assertThat(field.slopeDegAt(-9000f, 9000f)).isNotNaN();
        assertThat(field.normalAt(9000f, 9000f, new Vector3()).len()).isCloseTo(1f, within(1e-4f));
    }

    /** T-D16-10: a surface is a discrete kind, never a blend (D16-R52). */
    @Test
    void surfaceAtIsNearestSampleNeverInterpolated() {
        TerrainField field = desert();
        for (int j = 5; j < 400; j += 61) {
            for (int i = 5; i < 400; i += 53) {
                float x = field.sampleX(i);
                float z = field.sampleZ(j);
                Surface atSample = field.surfaceAtSample(i, j);
                assertThat(field.surfaceAt(x, z)).isEqualTo(atSample);
                // A quarter cell away is still the same sample.
                assertThat(field.surfaceAt(x + 0.24f * field.params().cellSizeM(), z))
                        .isEqualTo(atSample);
            }
        }
    }

    /** Surfaces are classified physically: nothing past the angle of repose is sand (D16-R42). */
    @Test
    void sandNeverSitsOnAFaceSteeperThanItsAngleOfRepose() {
        TerrainField field = desert();
        int grid = field.params().gridSize();
        for (int j = 1; j < grid - 1; j += 7) {
            for (int i = 1; i < grid - 1; i += 7) {
                if (field.slopeDegAtSample(i, j) > TerrainParams.SAND_REPOSE_DEG + 2f) {
                    assertThat(field.surfaceAtSample(i, j))
                            .as("sample (" + i + "," + j + ") is past repose")
                            .isEqualTo(Surface.ROCK);
                }
            }
        }
    }

    /** T-D16-11: a spawn point that cannot get out of where it started is detected. */
    @Test
    void anUnreachableSpawnPointIsDetected() {
        TerrainField field = desert();
        List<Vector3> reachable = List.of(new Vector3(0f, 1f, 0f), new Vector3(20f, 1f, 20f));
        // Both near the centre of a drivable arena — if these are not connected the fixture is wrong
        // rather than the code, so assert it before asserting the negative case.
        assertThat(TerrainGenerator.spawnPointsConnected(field, reachable))
                .as("two points in the open are connected")
                .isTrue();

        // On the rim, which is past the drivable slope on every side by the test above.
        List<Vector3> onTheRim = List.of(new Vector3(0f, 1f, 0f), new Vector3(MAX.x - 2f, 1f, MAX.z - 2f));
        assertThat(TerrainGenerator.spawnPointsConnected(field, onTheRim))
                .as("a point up the border rim cannot be reached (D16-R58)")
                .isFalse();
    }

    /** A410: a grid that does not cover the arena's bounds is refused, not silently offset. */
    @Test
    void aGridThatDoesNotMatchTheBoundsIsRefused() {
        TerrainParams tooSmall = TerrainParams.desert(SEED, 400f);
        assertThatThrownBy(() -> TerrainGenerator.generate(MIN, MAX, 0f, tooSmall))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("D16-R5");
    }

    /** D16-R5: the grid is square, odd and within its bounds, and says so rather than misbehaving. */
    @Test
    void gridSizeIsValidatedAtConstruction() {
        assertThatThrownBy(() -> new TerrainParams(
                        1L, 1f, 600, TerrainParams.Biome.DESERT, 16f, 0.0035f, 5, 0f, 90f, 9f, 0f, 0f, 25f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("odd");
        assertThatThrownBy(() -> new TerrainParams(
                        1L, 1f, 63, TerrainParams.Biome.DESERT, 16f, 0.0035f, 5, 0f, 90f, 9f, 0f, 0f, 25f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gridSize");
    }

    /** A flat biome is flat, which is what keeps the existing physics fixtures meaningful. */
    @Test
    void theTarmacBiomeIsTarmacThroughout() {
        TerrainParams params = new TerrainParams(
                SEED, 1f, 601, TerrainParams.Biome.TARMAC_FLAT, 3f, 0.0035f, 4, 0f, 90f, 0f, 0f, 0f, 25f);
        TerrainField field = TerrainGenerator.generate(MIN, MAX, 0f, params);
        assertThat(field.surfaceAt(0f, 0f)).isEqualTo(Surface.TARMAC);
        assertThat(field.surfaceAt(-250f, 180f)).isEqualTo(Surface.TARMAC);
        assertThat(field.drivableFraction()).as("gentle relief is all drivable").isGreaterThan(0.99f);
    }
}
