/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.ecs.EntityId;

/**
 * Which target a bot engages, and where on it to shoot
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.2).
 *
 * <p>Two rules carry the behaviour, and both are about <em>commitment</em> rather than about picking
 * the best target. A bot that re-evaluated freely every tick would swap targets whenever two scores
 * crossed, which reads as indecision and means it never finishes anything; the cooldown is what makes
 * it look like it decided. And the score's distance term is deliberately soft — a bot that always
 * shot the nearest enemy would be trivially baited by anyone willing to drive at it.
 */
public final class TargetSelection {

    /** Weight on closeness: a target at the sensor edge scores nothing, one at zero range scores this. */
    public static final float WEIGHT_PROXIMITY = 100f;

    /** Weight on how wounded a target is — finish what is already hurt. */
    public static final float WEIGHT_WOUNDED = 80f;

    /** Bonus for a target that is visible right now rather than remembered. */
    public static final float WEIGHT_VISIBLE = 40f;

    /** Bonus for whoever hit us last. */
    public static final float WEIGHT_RETALIATION = 30f;

    /** Bonus for a target a teammate is already engaging, when the difficulty coordinates. */
    public static final float WEIGHT_FOCUS_FIRE = 25f;

    /** Penalty for a target behind the bow, which a car cannot bring guns to bear on quickly. */
    public static final float PENALTY_ASTERN = 50f;

    /** Beyond this angle off the bow a target counts as astern. */
    public static final float ASTERN_ANGLE_DEG = 120f;

    private final Vector3 toTarget = new Vector3();
    private final Vector3 forward = new Vector3();

    /**
     * Chooses a target, honouring the difficulty's re-target cooldown.
     *
     * @param lastAttacker the player or vehicle that most recently damaged this bot, or
     *     {@link EntityId#NULL}
     * @param teammatesTargeting how many allies are engaging a given entity; may be null when the
     *     difficulty does not coordinate
     * @return the chosen entity, or {@link EntityId#NULL} when nothing is worth engaging
     */
    public int select(
            BotControllerComponent bot,
            BotDifficultyParams params,
            SensorSnapshot snapshot,
            Vector3 botForward,
            int botTeam,
            boolean friendlyFire,
            int lastAttacker,
            java.util.function.IntUnaryOperator teammatesTargeting,
            long tick) {

        if (bot.targetEntity != EntityId.NULL
                && tick - bot.lastTargetSwitchTick < params.targetSwitchCooldownTicks()
                && snapshot.targetById(bot.targetEntity) != null) {
            return bot.targetEntity;
        }

        forward.set(botForward);
        int best = EntityId.NULL;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (PerceivedTarget target : snapshot.targets()) {
            if (!friendlyFire && isAlly(target.teamId, botTeam)) {
                continue;
            }
            float score = scoreOf(target, params, snapshot, botTeam, lastAttacker, teammatesTargeting);
            target.threatScore = score;
            // Strictly greater, and the list is in ascending entity order, so an exact tie resolves
            // to the lowest entity id on every peer rather than to whichever was visited last (G3).
            if (score > bestScore) {
                bestScore = score;
                best = target.entity;
            }
        }
        if (best != bot.targetEntity) {
            bot.lastTargetSwitchTick = tick;
        }
        return best;
    }

    private float scoreOf(
            PerceivedTarget target,
            BotDifficultyParams params,
            SensorSnapshot snapshot,
            int botTeam,
            int lastAttacker,
            java.util.function.IntUnaryOperator teammatesTargeting) {

        toTarget.set(target.position).sub(snapshot.selfPosition);
        float distance = toTarget.len();
        float score = WEIGHT_PROXIMITY * (1f - clamp01(distance / BotSensors.SENSOR_RANGE_M));
        score += WEIGHT_WOUNDED * (1f - clamp01(target.integrity));
        if (target.hasLineOfSight) {
            score += WEIGHT_VISIBLE;
        }
        if (lastAttacker != EntityId.NULL && target.entity == lastAttacker) {
            score += WEIGHT_RETALIATION;
        }
        if (params.focusFireCoordination()
                && teammatesTargeting != null
                && teammatesTargeting.applyAsInt(target.entity) > 0) {
            score += WEIGHT_FOCUS_FIRE;
        }
        if (distance > 0.001f && angleOffBowDeg(toTarget, distance) > ASTERN_ANGLE_DEG) {
            score -= PENALTY_ASTERN;
        }
        return score;
    }

    private float angleOffBowDeg(Vector3 toTargetVector, float distance) {
        float cos = toTargetVector.dot(forward) / distance;
        return (float) Math.toDegrees(Math.acos(clamp(cos, -1f, 1f)));
    }

    /**
     * A target's aim point (D11-S5.2 {@code selectAimPoint}).
     *
     * <p>Part-level targeting is a {@code HARD}/{@code BRUTAL} privilege (D11-R3) and is expressed
     * here as an aim <em>bias</em> rather than as a lookup of a specific part's world transform.
     * Biasing low aims at wheels and suspension, which is what "target the parts whose loss most
     * degrades the vehicle" means on a car and costs nothing to compute; walking the slot chain of
     * every candidate target every tick would be the most expensive thing in the decision loop and
     * would buy accuracy the aim error immediately throws away.
     */
    public Vector3 aimPointFor(PerceivedTarget target, BotDifficultyParams params, Vector3 out) {
        out.set(target.position);
        if (params.usesPartTargeting()) {
            out.y -= PART_TARGETING_AIM_DROP_M;
        }
        return out;
    }

    /** How far below a target's origin a part-targeting bot aims — roughly axle height. */
    public static final float PART_TARGETING_AIM_DROP_M = 0.35f;

    private static boolean isAlly(int targetTeam, int botTeam) {
        return botTeam != TeamComponent.FREE_FOR_ALL && targetTeam == botTeam;
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
