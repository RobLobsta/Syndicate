/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.EntityId;

/**
 * One vehicle as a bot currently believes it to be
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.3).
 *
 * <p>The position and velocity here are the true values <em>as of</em>
 * {@link SensorSnapshot#capturedTick}, with difficulty-scaled error applied. They are deliberately
 * not the current true values: a bot that read live world state would be perfect regardless of its
 * difficulty setting, which is what AC-D11-2 forbids.
 *
 * <p>{@link #hasLineOfSight} distinguishes a target being <em>seen</em> from one being
 * <em>remembered</em>. Only the first may be fired at (D11-S5.3 step 6); the second is what the
 * {@code hunt} behaviour drives toward.
 */
public final class PerceivedTarget {

    /** Which entity this belief is about. */
    public int entity = EntityId.NULL;

    /** Believed world position, with perception error applied. */
    public final Vector3 position = new Vector3();

    /** Believed world velocity, with perception error applied. */
    public final Vector3 velocity = new Vector3();

    /** Believed aggregate health fraction, {@code [0,1]}. */
    public float integrity;

    /** The target's team, or {@code -1} in free-for-all. */
    public int teamId = -1;

    /** Whether the target is visible right now, as opposed to remembered (D11-R6). */
    public boolean hasLineOfSight;

    /** The tick this belief was last refreshed by an actual sighting. */
    public long lastSeenTick;

    /** How dangerous the target selector rated this target. Written by {@code TargetSelection}. */
    public float threatScore;

    /** Returns to a pristine, unoccupied slot (D04-R17). */
    public void reset() {
        entity = EntityId.NULL;
        position.set(0f, 0f, 0f);
        velocity.set(0f, 0f, 0f);
        integrity = 0f;
        teamId = -1;
        hasLineOfSight = false;
        lastSeenTick = 0L;
        threatScore = 0f;
    }
}
