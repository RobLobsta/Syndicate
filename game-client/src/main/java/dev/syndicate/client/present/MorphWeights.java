/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.present;

/**
 * Health to damage shape key weights (docs/07_damage_destruction_model.md#D07-S5.5).
 *
 * <p>The Blender tool authors four discrete deformation states — {@code dmg_25}, {@code dmg_50},
 * {@code dmg_75}, {@code dmg_100} (D09-S5.3). Deformation on screen has to be continuous, so a part
 * at 62.5% health is drawn as half of {@code dmg_25} plus half of {@code dmg_50} rather than
 * snapping between authored states. Exactly two weights are ever non-zero: the pair bracketing the
 * current health.
 *
 * <p><b>Cosmetic, one-directional</b> (G6, D07-R18). Health drives these weights; nothing reads them
 * back. A dedicated server never calls this class, and never loads the geometry it would apply to
 * (D03-R13).
 */
public final class MorphWeights {

    /** The health at which each level is pure, descending (D07-S5.5). */
    public static final float[] HEALTH_POINTS = {1.00f, 0.75f, 0.50f, 0.25f, 0.00f};

    /** The shape key names, in weight order. Index {@code i} of a weight array is {@code NAMES[i]}. */
    public static final String[] NAMES = {"dmg_25", "dmg_50", "dmg_75", "dmg_100"};

    /** How many weights a part carries — one per authored damage state. */
    public static final int COUNT = NAMES.length;

    /** Weight units per second the displayed weights ease toward the target at (D07-S5.5). */
    public static final float MORPH_LERP_RATE = 4.0f;

    private MorphWeights() {
        throw new AssertionError("no instances");
    }

    /**
     * The target weights for a health fraction, written into {@code out}.
     *
     * <p>Implements D07-S5.5's {@code morphWeightsForHealth} literally, including its two boundary
     * behaviours: an undamaged part has every weight at zero (it is drawn as its base mesh, not as
     * a blend), and a part at zero health is fully {@code dmg_100} whatever the bracketing arithmetic
     * produced.
     *
     * @param healthFraction {@code [0,1]}; values outside are clamped
     * @param out an array of {@link #COUNT} weights, overwritten
     */
    public static void forHealth(float healthFraction, float[] out) {
        if (out.length != COUNT) {
            throw new IllegalArgumentException("expected " + COUNT + " weights, got " + out.length);
        }
        float health = Math.min(1f, Math.max(0f, healthFraction));
        java.util.Arrays.fill(out, 0f);

        for (int i = 0; i < COUNT; i++) {
            float hi = HEALTH_POINTS[i];
            float lo = HEALTH_POINTS[i + 1];
            if (health <= hi && health >= lo) {
                float t = (hi - health) / (hi - lo);
                if (i > 0) {
                    out[i - 1] = 1f - t;
                }
                out[i] = t;
                break;
            }
        }
        if (health <= 0f) {
            java.util.Arrays.fill(out, 0f);
            out[COUNT - 1] = 1f;
        }
    }

    /**
     * Moves {@code current} toward {@code target} at {@link #MORPH_LERP_RATE} per second.
     *
     * <p>The ease is what makes a burst of damage read as the panel crumpling rather than as the
     * mesh being swapped. It is presentational in the strictest sense: the target is the authoritative
     * value and the displayed weight always converges to it.
     */
    public static void moveToward(float[] current, float[] target, float dtSeconds) {
        float step = MORPH_LERP_RATE * dtSeconds;
        for (int i = 0; i < COUNT; i++) {
            float delta = target[i] - current[i];
            if (Math.abs(delta) <= step) {
                current[i] = target[i];
            } else {
                current[i] += Math.signum(delta) * step;
            }
        }
    }

    /**
     * Folds the weights of shape keys a mesh does not have onto the worst one it does (D07-R17).
     *
     * <p>D07-R17 says missing levels are skipped and the weights renormalise over the morphs that
     * exist. The authored set is a prefix — a mesh with two damage states has {@code dmg_25} and
     * {@code dmg_50}, never {@code dmg_25} and {@code dmg_100} — so "skipped" means the deformation
     * a missing level would have shown is expressed by the deepest level present instead. Summing
     * onto the last available weight does that and preserves the total, so a destroyed part is still
     * fully deformed rather than fading back toward intact as its shape key count drops. A mesh with
     * no damage keys at all gets nothing written and never deforms; it still fractures.
     *
     * @param weights weights for all {@link #COUNT} levels, read but not modified
     * @param available how many shape keys the mesh actually carries
     * @param out {@code available} weights, overwritten; may be zero-length
     */
    public static void renormalise(float[] weights, int available, float[] out) {
        if (available <= 0) {
            return;
        }
        int usable = Math.min(available, COUNT);
        System.arraycopy(weights, 0, out, 0, usable);
        for (int i = usable; i < COUNT; i++) {
            out[usable - 1] += weights[i];
        }
        for (int i = COUNT; i < out.length; i++) {
            // A mesh with more shape keys than damage states carries keys this system does not
            // author. Zeroing rather than skipping them means a stale weight from another source
            // cannot linger on a mesh this system is otherwise driving.
            out[i] = 0f;
        }
    }
}
