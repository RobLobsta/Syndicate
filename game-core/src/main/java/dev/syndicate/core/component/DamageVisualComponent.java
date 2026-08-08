/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import java.util.Arrays;

/**
 * How damaged a part <em>looks</em> (docs/04_entity_component_model.md#D04-S4.3.3,
 * docs/07_damage_destruction_model.md#D07-S5.5).
 *
 * <p>Every field is classified {@code C}: client-local, never replicated, and — the part that
 * matters — never read by a gameplay system. G6 is one-directional. Health drives morph weights;
 * morph weights drive nothing. A dedicated server never even creates this component, which is why
 * it can live in {@code game-core} without violating G17: it is data with no renderer attached.
 *
 * <p>The reason it is not simply derived per frame from health is {@link #targetMorphWeights}: the
 * mesh eases toward a new damage state over a few frames instead of snapping, so the current
 * weights are genuine per-entity state that outlives the frame that set the target.
 */
public final class DamageVisualComponent implements Component {

    /** One weight per damage shape key, in {@code dmg_25 .. dmg_100} order (D07-S5.5). */
    public final float[] morphWeights = new float[MORPH_COUNT];

    /** What {@link #morphWeights} is easing toward. */
    public final float[] targetMorphWeights = new float[MORPH_COUNT];

    /** Scorch and decal blend, {@code [0,1]}. */
    public float charLevel;

    /** Emissive fire intensity, {@code [0,1]}. */
    public float emissiveFireLevel;

    /** The four damage shape keys every processed part carries (D09-S5.3). */
    public static final int MORPH_COUNT = 4;

    @Override
    public void reset() {
        Arrays.fill(morphWeights, 0f);
        Arrays.fill(targetMorphWeights, 0f);
        charLevel = 0f;
        emissiveFireLevel = 0f;
    }
}
