/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

/**
 * What kind of place an arena is (docs/16_procedural_arena_generation.md#D16-S4.2).
 *
 * <p>A theme is the <b>whole</b> answer to "what does this map look like and drive like": which
 * relief layer the generator lays over the broad landform, what the ground is made of at each
 * height and slope, and what colour it bounces into the sky. An arena declares a theme and a seed
 * and gets a coherent landscape; it does not declare dune wavelengths and surface thresholds
 * separately and hope they agree.
 *
 * <p>That is the difference between this and the {@code biome} field it replaces. A biome was a
 * switch inside the generator with the interesting numbers still in the arena file, so "make a
 * scrapyard" meant knowing which eight numbers to change. A theme owns those numbers, so it means
 * writing {@code "theme": "scrapyard"}.
 *
 * <p><b>Closed, deliberately.</b> Every member costs a relief layer, a surface palette and — once
 * D16-S6 exists — a texture set and a sky. A theme nobody has generated is worse than one that does
 * not exist (D16-R6).
 */
public enum ArenaTheme {

    /**
     * Open dune sea with a road through it. Sand underfoot, rock where sand cannot sit.
     *
     * <p>The dune layer is directional: crests run transverse to the prevailing wind, so every dune
     * is a ramp from one side and a wall from the other. That asymmetry is the gameplay — see
     * {@link TerrainParams#MAX_DRIVABLE_SLOPE_DEG} against {@link TerrainParams#SAND_REPOSE_DEG}.
     */
    DESERT_HIGHWAY(
            "Desert Highway",
            Relief.DUNES,
            new Palette(Surface.ROCK, Surface.ROCK, Surface.GRAVEL, Surface.SAND),
            0.66f,
            0.55f,
            0.38f,
            new Shape(16.0f, 0.0035f, 5, 115.0f, 90.0f, 9.0f, 60.0f, 40.0f)),

    /**
     * A breaker's yard: flat compacted ground with heaps of crushed scrap piled across it.
     *
     * <p>Deliberately <em>not</em> rolling hills. The heap layer is isotropic and thresholded, so
     * most of the map stays flat enough to fight on and the relief arrives as discrete piles you go
     * around, get pushed into, or climb to shoot from. Underfoot it is gravel and hardcore, with the
     * old yard slab showing through wherever the ground is flattest.
     */
    SCRAPYARD(
            "Scrapyard",
            Relief.HEAPS,
            new Palette(Surface.ROCK, Surface.ROCK, Surface.GRAVEL, Surface.TARMAC),
            0.34f,
            0.32f,
            0.30f,
            new Shape(5.0f, 0.0060f, 4, 0.0f, 45.0f, 9.0f, 45.0f, 26.0f)),

    /**
     * Gentle slopes, tarmac throughout. Somewhere to measure a car rather than somewhere to fight.
     *
     * <p>This is the theme a physics fixture wants: relief enough that a suspension does something,
     * one surface so a handling number means the same thing everywhere on the map.
     */
    PROVING_GROUND(
            "Proving Ground",
            Relief.NONE,
            new Palette(Surface.TARMAC, Surface.TARMAC, Surface.TARMAC, Surface.TARMAC),
            0.28f,
            0.28f,
            0.29f,
            new Shape(4.0f, 0.0030f, 3, 0.0f, 60.0f, 0.0f, 50.0f, 22.0f));

    /** The relief layer a theme lays over the broad landform. */
    public enum Relief {
        /** Nothing. The landform alone. */
        NONE,
        /** Wind-oriented dunes with a slip face at the angle of repose. */
        DUNES,
        /** Isotropic piles, thresholded so the ground between them stays flat. */
        HEAPS
    }

    /**
     * Which surface goes where, from steepest to flattest.
     *
     * <p>Ordered by the test that selects it, not by prominence: anything past the angle of repose
     * is {@code pastRepose}, anything above the exposure height is {@code high}, anything steeper
     * than the gravel threshold is {@code slope}, and the rest is {@code floor}. A theme that wants
     * one surface everywhere says so by repeating it.
     */
    public record Palette(Surface pastRepose, Surface high, Surface slope, Surface floor) {

        /** True when every row is the same surface, so classification can skip the per-sample work. */
        public boolean isUniform() {
            return pastRepose == high && high == slope && slope == floor;
        }
    }

    /**
     * The generator numbers a theme fixes.
     *
     * @param reliefM peak-to-trough amplitude of the broad landform, metres
     * @param baseFrequency cycles per metre of the landform's first octave
     * @param octaves octaves of fractal noise in the landform
     * @param featureBearingDeg bearing the relief layer is oriented to, where it has one (D00-R17)
     * @param featureWavelengthM metres between relief features — dune crests, or heap centres
     * @param featureHeightM height of a relief feature, metres, before any correction
     * @param borderWidthM width of the impassable rim at the arena edge, metres
     * @param borderRiseM how far that rim rises, metres
     */
    public record Shape(
            float reliefM,
            float baseFrequency,
            int octaves,
            float featureBearingDeg,
            float featureWavelengthM,
            float featureHeightM,
            float borderWidthM,
            float borderRiseM) {}

    private final String displayName;
    private final Relief relief;
    private final Palette palette;
    private final float albedoR;
    private final float albedoG;
    private final float albedoB;
    private final Shape shape;

    ArenaTheme(
            String displayName,
            Relief relief,
            Palette palette,
            float albedoR,
            float albedoG,
            float albedoB,
            Shape shape) {
        this.displayName = displayName;
        this.relief = relief;
        this.palette = palette;
        // The ground goes through the same house style the vehicles do (DEC-079), applied here so
        // that no consumer can read a theme's colour without it. The authored triple above is what
        // an arena designer picked; these three are what the scene is allowed to show.
        float[] styled = GroundStyle.apply(albedoR, albedoG, albedoB);
        this.albedoR = styled[0];
        this.albedoG = styled[1];
        this.albedoB = styled[2];
        this.shape = shape;
    }

    /** What a player is told this place is called, when the arena does not name itself. */
    public String displayName() {
        return displayName;
    }

    public Relief relief() {
        return relief;
    }

    public Palette palette() {
        return palette;
    }

    public Shape shape() {
        return shape;
    }

    /**
     * The average ground colour, for the sky model's ground bounce (D16-R17).
     *
     * <p>Not decoration: sand bouncing warm light into shadowed surfaces is most of why a desert
     * looks like a desert rather than a blue-lit quarry. Nothing reads this until D16-S6 exists,
     * and it lives here now so that when the renderer arrives it does not have to invent a table.
     */
    public float albedoR() {
        return albedoR;
    }

    public float albedoG() {
        return albedoG;
    }

    public float albedoB() {
        return albedoB;
    }

    /** Parses the {@code theme} field of an arena's terrain block, case-insensitively. */
    public static ArenaTheme parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ArenaTheme theme : values()) {
            if (theme.name().equalsIgnoreCase(raw.trim())) {
                return theme;
            }
        }
        return null;
    }
}
