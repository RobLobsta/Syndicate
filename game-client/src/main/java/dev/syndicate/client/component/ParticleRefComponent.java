/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.component;

import com.badlogic.gdx.graphics.Color;
import dev.syndicate.core.ecs.Component;

/**
 * One burst of particles (docs/04_entity_component_model.md#D04-S4.2, {@code EFFECT} archetype).
 *
 * <p>Classified {@code C}: client-local, never replicated (G6). An entity carries the whole burst
 * rather than one particle each, which is the difference between a hit costing one entity and
 * costing thirty — and D04-R10's 16,384 entities are a budget for a match, not for a firefight.
 *
 * <p>Particle state is kept in flat arrays rather than objects because a burst is created inside a
 * frame and every field is read every frame after it. The arrays are sized once at
 * {@link #MAX_PARTICLES} and reused through the component pool, so a burst allocates nothing.
 */
public final class ParticleRefComponent implements Component {

    /** What a burst is made of, which decides how it moves and what it looks like. */
    public enum Kind {
        /** A hard contact: bright, fast, short, gravity-bound. */
        SPARKS,
        /** A part shattering: many small pieces, wide cone. */
        SHARDS,
        /** A part tearing off: slower, heavier, fewer. */
        DEBRIS_PUFF,
        /** A vehicle dying: a slow dark plume that rises. */
        SMOKE
    }

    /** The most particles one burst holds. */
    public static final int MAX_PARTICLES = 32;

    /** What this burst is. */
    public Kind kind = Kind.SPARKS;

    /** How many entries of the arrays below are live. */
    public int count;

    /** Seconds since the burst was created. */
    public float ageSeconds;

    /** Seconds the burst lasts. Past this the entity is destroyed by its {@code LifetimeComponent}. */
    public float lifespanSeconds = 1f;

    /** World-space offsets from the entity's transform, metres. */
    public final float[] offsetX = new float[MAX_PARTICLES];

    public final float[] offsetY = new float[MAX_PARTICLES];
    public final float[] offsetZ = new float[MAX_PARTICLES];

    /** Metres per second, in world space. */
    public final float[] velocityX = new float[MAX_PARTICLES];

    public final float[] velocityY = new float[MAX_PARTICLES];
    public final float[] velocityZ = new float[MAX_PARTICLES];

    /** Metres. Half the width of the drawn quad. */
    public final float[] sizeM = new float[MAX_PARTICLES];

    /** The burst's colour. Every particle in one burst shares it; brightness fades with age. */
    public final Color colour = new Color(1f, 0.75f, 0.35f, 1f);

    @Override
    public void reset() {
        kind = Kind.SPARKS;
        count = 0;
        ageSeconds = 0f;
        lifespanSeconds = 1f;
        colour.set(1f, 0.75f, 0.35f, 1f);
        // The arrays are not cleared: `count` bounds every read of them, and clearing 32 floats
        // eight times per burst is work that changes nothing.
    }
}
