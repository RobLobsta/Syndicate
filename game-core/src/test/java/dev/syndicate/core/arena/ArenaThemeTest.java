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

/**
 * A theme produces the place it names, at any seed
 * (docs/16_procedural_arena_generation.md#D16-S4.2, #D16-S5.6).
 *
 * <p>The point of a theme is that "scrapyard" is a promise about how a map plays, kept across every
 * landscape the generator makes from it. A theme that produced a fightable yard at the one seed
 * somebody checked and a sealed maze at the next would be worse than a hand-authored arena, because
 * the failure would arrive in front of a player rather than in front of an author.
 *
 * <p>So these tests sweep seeds rather than asserting on one. Each is a property the theme claims.
 */
@Tag("unit")
class ArenaThemeTest {

    private static final float SPAN_M = 300f;
    private static final Vector3 MIN = new Vector3(-150f, -30f, -150f);
    private static final Vector3 MAX = new Vector3(150f, 90f, 150f);

    /** Seeds swept per property. Enough that a one-in-ten failure mode cannot hide. */
    private static final int SEEDS = 12;

    /**
     * A scrapyard is a flat yard with heaps on it, not a field of hills.
     *
     * <p>The distinction is the whole theme: the flatness between the piles is what makes them cover
     * rather than scenery. The heap layer is thresholded to produce that, and this is what says the
     * threshold is doing its job — if {@code HEAP_FLOOR} drifted down, this fails long before anyone
     * noticed the map felt wrong to drive.
     */
    @Test
    void aScrapyardIsMostlyFlatGroundWithHeapsOnIt() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            TerrainField field = generate(ArenaTheme.SCRAPYARD, seed);
            float drivable = drivableFraction(field);
            assertThat(drivable)
                    .as("seed " + seed + ": a yard you can fight in")
                    .isGreaterThan(0.80f);
            assertThat(drivable)
                    .as("seed " + seed + ": heaps exist, so it is not simply a plane")
                    .isLessThan(0.999f);
        }
    }

    /** A desert keeps its walls: dune slip faces are what make it a landscape rather than a field. */
    @Test
    void aDesertHasRealWallsInIt() {
        for (int seed = 1; seed <= SEEDS; seed++) {
            float drivable = drivableFraction(generate(ArenaTheme.DESERT_HIGHWAY, seed));
            assertThat(drivable)
                    .as("seed " + seed + ": enough of the desert is drivable to fight in")
                    .isGreaterThan(0.55f);
            assertThat(drivable)
                    .as("seed " + seed + ": dunes are walls, so a good deal is not drivable")
                    .isLessThan(0.95f);
        }
    }

    /** Each theme lays down the ground it says it does, and not another theme's. */
    @Test
    void eachThemeLaysDownItsOwnSurfaces() {
        assertThat(surfacesPresent(ArenaTheme.DESERT_HIGHWAY))
                .as("a desert is sand, with rock where sand will not sit")
                .contains(Surface.SAND)
                .doesNotContain(Surface.TARMAC);

        assertThat(surfacesPresent(ArenaTheme.SCRAPYARD))
                .as("a yard is gravel and hardcore over an old slab, and never sand")
                .contains(Surface.GRAVEL)
                .doesNotContain(Surface.SAND);

        assertThat(surfacesPresent(ArenaTheme.PROVING_GROUND))
                .as("a proving ground is one surface, so a handling number means one thing")
                .containsExactly(Surface.TARMAC);
    }

    /** The theme supplies every generator number, so an arena needs only a theme and a seed. */
    @Test
    void aThemeSuppliesItsOwnShape() {
        for (ArenaTheme theme : ArenaTheme.values()) {
            TerrainParams params = TerrainParams.of(theme, 1L, SPAN_M);
            assertThat(params.theme()).isEqualTo(theme);
            assertThat(params.reliefM()).isEqualTo(theme.shape().reliefM());
            assertThat(params.featureWavelengthM()).isEqualTo(theme.shape().featureWavelengthM());
            assertThat(params.spanM()).isEqualTo(SPAN_M);
        }
    }

    /** The generator stays a pure function of theme and seed: same input, same ground (G3). */
    @Test
    void thesameThemeAndSeedGiveTheSameGround() {
        for (ArenaTheme theme : ArenaTheme.values()) {
            assertThat(generate(theme, 7).fieldHash())
                    .as(theme.name() + " is reproducible from its seed")
                    .isEqualTo(generate(theme, 7).fieldHash());
        }
    }

    /** A theme name round-trips from the arena file in any case, and nonsense is rejected. */
    @Test
    void themeNamesParseFromContent() {
        assertThat(ArenaTheme.parse("scrapyard")).isEqualTo(ArenaTheme.SCRAPYARD);
        assertThat(ArenaTheme.parse("DESERT_HIGHWAY")).isEqualTo(ArenaTheme.DESERT_HIGHWAY);
        assertThat(ArenaTheme.parse("swamp")).isNull();
        assertThat(ArenaTheme.parse(null)).isNull();
    }

    private static TerrainField generate(ArenaTheme theme, long seed) {
        return TerrainGenerator.generate(MIN, MAX, 0f, TerrainParams.of(theme, seed, SPAN_M), List.of());
    }

    /** Fraction of the field inside the rim that a vehicle can drive on. */
    private static float drivableFraction(TerrainField field) {
        int grid = field.params().gridSize();
        // The border rim is impassable by design and would swamp the figure on a small arena, so it
        // is excluded: what is being measured is the playable interior.
        int margin = grid / 6;
        int drivable = 0;
        int total = 0;
        for (int j = margin; j < grid - margin; j++) {
            for (int i = margin; i < grid - margin; i++) {
                total++;
                if (field.isDrivableSample(i, j)) {
                    drivable++;
                }
            }
        }
        return drivable / (float) total;
    }

    private static java.util.Set<Surface> surfacesPresent(ArenaTheme theme) {
        java.util.Set<Surface> found = java.util.EnumSet.noneOf(Surface.class);
        for (int seed = 1; seed <= SEEDS; seed++) {
            TerrainField field = generate(theme, seed);
            int grid = field.params().gridSize();
            for (int j = 0; j < grid; j += 3) {
                for (int i = 0; i < grid; i += 3) {
                    found.add(field.surfaceAtSample(i, j));
                }
            }
        }
        return found;
    }
}
