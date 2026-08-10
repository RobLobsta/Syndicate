/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a bot is allowed to know about the world at one instant
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.3, #D11-S5.2).
 *
 * <p>This type is the enforcement point for AC-D11-2: {@code BotDecisionSystem} reads this and
 * nothing else, so a bot cannot see through a wall or react before its {@code reactionDelayS} has
 * elapsed. Widening it is how bot difficulty accidentally stops meaning anything, so a new field
 * here is a design decision, not a convenience.
 *
 * <p>Refreshed in place at {@code sensorUpdateHz} rather than reallocated: one snapshot per bot per
 * refresh would be steady-state garbage (D04-S5.6). The {@link PerceivedTarget} instances are pooled
 * for the same reason — {@link #beginTargets()} rewinds the list rather than clearing it, and
 * {@link #addTarget()} hands back the next slot to overwrite.
 */
public final class SensorSnapshot {

    /** The tick this view describes — always in the past by the bot's reaction delay. */
    public long capturedTick;

    /** The bot's own vehicle position. Exact: a bot knows where it is. */
    public final Vector3 selfPosition = new Vector3();

    /** The bot's own vehicle velocity. Exact. */
    public final Vector3 selfVelocity = new Vector3();

    /** The bot's own aggregate health fraction, {@code [0,1]}. */
    public float selfIntegrity = 1f;

    /** Hit points from the short-range obstacle ray fan, in fan order. */
    public final List<Vector3> nearbyObstacles = new ArrayList<>();

    /** Projectiles within {@code PROJECTILE_NOTICE_M}, in ascending entity order. */
    public final List<Vector3> incomingProjectiles = new ArrayList<>();

    private final List<PerceivedTarget> targetPool = new ArrayList<>();

    private int targetCount;

    /** Targets that passed the range and line-of-sight tests, in ascending entity order (G3). */
    public List<PerceivedTarget> targets() {
        return targetPool.subList(0, targetCount);
    }

    /** How many targets are believed in. */
    public int targetCount() {
        return targetCount;
    }

    /** The belief about one entity, or null when it is not in this snapshot. */
    public PerceivedTarget targetById(int entity) {
        for (int i = 0; i < targetCount; i++) {
            PerceivedTarget target = targetPool.get(i);
            if (target.entity == entity) {
                return target;
            }
        }
        return null;
    }

    /** Starts a fresh set of beliefs, keeping the pooled instances. */
    public void beginTargets() {
        targetCount = 0;
    }

    /** The next pooled target slot, already reset. */
    public PerceivedTarget addTarget() {
        while (targetPool.size() <= targetCount) {
            targetPool.add(new PerceivedTarget());
        }
        PerceivedTarget target = targetPool.get(targetCount++);
        target.reset();
        return target;
    }

    /**
     * Clears the snapshot for reuse. The lists keep their backing arrays — that is the point of
     * clearing rather than replacing them.
     */
    public void reset() {
        capturedTick = 0L;
        targetCount = 0;
        selfPosition.set(0f, 0f, 0f);
        selfVelocity.set(0f, 0f, 0f);
        selfIntegrity = 1f;
        nearbyObstacles.clear();
        incomingProjectiles.clear();
    }
}
