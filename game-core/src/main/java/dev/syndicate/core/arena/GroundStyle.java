/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

/**
 * Holds an arena's ground inside the same house style the vehicles are held in
 * (docs/15_vehicle_preparation_pipeline.md#D15-S4.5, DEC-079).
 *
 * <p><b>Why this exists.</b> Every vehicle in the game is moved onto a six-hue palette and clamped
 * into one global luminance band before it ships, so a photoscanned supercar and a flat-shaded
 * cartoon come out looking like one artist's work. The generated ground went through none of that:
 * it came from {@link ArenaTheme}'s own albedo, which nobody measured against the cars, and read
 * noticeably lighter than the vehicles sitting on it. Two things in one frame styled by two
 * different rules is the single loudest way a scene reads as assembled rather than made.
 *
 * <p><b>The rule is the vehicles' rule, not a second one.</b> Both mechanisms of DEC-079 apply, in
 * the same order and with the same numbers: hue is snapped onto the palette, then finished luminance
 * is clamped into the tone band. {@code style.json} remains the authority for both, and
 * {@code GroundStyleTest} fails if these constants and that file drift apart — which is what stops
 * this from becoming a second style table pretending to be the first.
 *
 * <p>Applied to the theme's authored albedo at construction, so every consumer — the render mesh,
 * the minimap, anything later — sees a colour that is already in the band. A caller that could
 * forget to apply it is a caller that will.
 */
public final class GroundStyle {

    /**
     * The palette's hues, degrees, from {@code assets/materials/style.json}.
     *
     * <p>Six and no more; that restriction is the style. Two warm, two cold, one faction red, one
     * sodium amber.
     */
    public static final float[] PALETTE_HUES_DEG = {22.0f, 41.0f, 33.0f, 6.0f, 210.0f, 188.0f};

    /** How hard a colour is pulled onto the nearest palette hue, {@code [0,1]}. */
    public static final float PALETTE_PULL = 0.75f;

    /** Rec. 709 luma floor: below this a surface is a hole in the frame rather than a dark surface. */
    public static final float LUMINANCE_MIN = 0.03f;

    /** Rec. 709 luma ceiling: nothing imported may clash on brightness. */
    public static final float LUMINANCE_MAX = 0.62f;

    /**
     * The ground's share of the tone band's ceiling.
     *
     * <p>The band is a ceiling for <em>everything</em>, and a ground held exactly at it is as bright
     * as the brightest panel on the brightest car — which is what made the terrain read as a
     * snowfield the first time it was looked at (DISC-047). Ground is the backdrop the vehicles are
     * read against, so it takes the lower two thirds of the band and leaves the top third to the
     * things that move on it. That is a composition decision rather than a measurement, and it is
     * the number to turn if the scene still reads wrong.
     */
    public static final float GROUND_CEILING_FRACTION = 0.66f;

    private GroundStyle() {
        throw new AssertionError("no instances");
    }

    /** The styled form of an authored RGB triple, as {@code {r, g, b}} in {@code [0,1]}. */
    public static float[] apply(float r, float g, float b) {
        float[] hsv = toHsv(r, g, b);
        hsv[0] = snapHue(hsv[0]);
        float[] rgb = toRgb(hsv[0], hsv[1], hsv[2]);
        return clampLuma(rgb, LUMINANCE_MIN, LUMINANCE_MAX * GROUND_CEILING_FRACTION);
    }

    /** Rec. 709 luma, which is the measure the tone band is expressed in. */
    public static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /** Pulls a hue {@code PALETTE_PULL} of the way onto the nearest palette hue. */
    static float snapHue(float hueDeg) {
        float nearest = PALETTE_HUES_DEG[0];
        float bestDistance = Float.MAX_VALUE;
        for (float candidate : PALETTE_HUES_DEG) {
            float d = Math.abs(shortestArc(hueDeg, candidate));
            if (d < bestDistance) {
                bestDistance = d;
                nearest = candidate;
            }
        }
        float moved = hueDeg + shortestArc(hueDeg, nearest) * PALETTE_PULL;
        return (moved % 360f + 360f) % 360f;
    }

    /** The signed shortest way round the wheel from {@code from} to {@code to}, in degrees. */
    private static float shortestArc(float from, float to) {
        float delta = (to - from) % 360f;
        if (delta > 180f) {
            delta -= 360f;
        } else if (delta < -180f) {
            delta += 360f;
        }
        return delta;
    }

    /**
     * Scales a colour so its luma lands in {@code [min, max]}, keeping its hue and saturation.
     *
     * <p>Scaled rather than lerped toward grey: a colour whose brightness is corrected by desaturating
     * it comes back a different colour, and the whole point of doing the hue snap first is that the
     * hue survives everything after it.
     */
    static float[] clampLuma(float[] rgb, float min, float max) {
        float l = luma(rgb[0], rgb[1], rgb[2]);
        if (l <= 0f) {
            return new float[] {min, min, min};
        }
        float target = Math.min(Math.max(l, min), max);
        float scale = target / l;
        return new float[] {Math.min(1f, rgb[0] * scale), Math.min(1f, rgb[1] * scale), Math.min(1f, rgb[2] * scale)};
    }

    // ---- Colour space ---------------------------------------------------------------

    /** {@code {hueDeg, saturation, value}}. */
    static float[] toHsv(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float chroma = max - min;
        float hue;
        if (chroma == 0f) {
            hue = 0f;
        } else if (max == r) {
            hue = 60f * (((g - b) / chroma) % 6f);
        } else if (max == g) {
            hue = 60f * ((b - r) / chroma + 2f);
        } else {
            hue = 60f * ((r - g) / chroma + 4f);
        }
        return new float[] {(hue % 360f + 360f) % 360f, max == 0f ? 0f : chroma / max, max};
    }

    /** {@code {r, g, b}} from hue in degrees, saturation and value. */
    static float[] toRgb(float hueDeg, float saturation, float value) {
        float c = value * saturation;
        float h = ((hueDeg % 360f) + 360f) % 360f / 60f;
        float x = c * (1f - Math.abs((h % 2f) - 1f));
        float m = value - c;
        float r;
        float g;
        float b;
        if (h < 1f) {
            r = c;
            g = x;
            b = 0f;
        } else if (h < 2f) {
            r = x;
            g = c;
            b = 0f;
        } else if (h < 3f) {
            r = 0f;
            g = c;
            b = x;
        } else if (h < 4f) {
            r = 0f;
            g = x;
            b = c;
        } else if (h < 5f) {
            r = x;
            g = 0f;
            b = c;
        } else {
            r = c;
            g = 0f;
            b = x;
        }
        return new float[] {r + m, g + m, b + m};
    }
}
