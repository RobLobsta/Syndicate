/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import dev.syndicate.model.SimulationConstants;

/**
 * One difficulty level's perception and execution parameters
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.2).
 *
 * <p>Every field here degrades what a bot <em>knows</em> or how well it <em>executes</em>. D11-R6
 * forbids anything else: no field may raise damage, health, speed, or armour, because a difficulty
 * setting that did would stop being a difficulty setting and become a handicap the player cannot
 * see. That rule is checkable by reading this record, which is why the record exists rather than
 * thirteen fields spread across the decision loop.
 *
 * @param reactionDelayS how far in the past the bot's view of the world is
 * @param sensorUpdateHz how often that view refreshes
 * @param aimErrorRad standard deviation of the persistent aim offset
 * @param aimSettleRateRadPerS how fast aim converges on the point it is trying to reach
 * @param leadPredictionQuality fraction of the correct target lead that is applied
 * @param throttleAggression fraction of available throttle used
 * @param avoidanceLookaheadS obstacle-avoidance horizon
 * @param targetSwitchCooldownS minimum time before re-targeting
 * @param usesPartTargeting whether it aims at parts rather than at the centre of mass
 * @param retreatHealthFraction integrity below which it breaks contact
 * @param firingDisciplineRange multiplier on effective range before it will fire
 * @param usesCover whether a retreat paths toward cover
 * @param focusFireCoordination whether it prefers a target a teammate is engaging
 */
public record BotDifficultyParams(
        float reactionDelayS,
        int sensorUpdateHz,
        float aimErrorRad,
        float aimSettleRateRadPerS,
        float leadPredictionQuality,
        float throttleAggression,
        float avoidanceLookaheadS,
        float targetSwitchCooldownS,
        boolean usesPartTargeting,
        float retreatHealthFraction,
        float firingDisciplineRange,
        boolean usesCover,
        boolean focusFireCoordination) {

    /** {@link #reactionDelayS} in ticks, which is the unit the sensor snapshot is stamped in. */
    public int reactionDelayTicks() {
        return Math.round(reactionDelayS * SimulationConstants.TICK_RATE_HZ);
    }

    /**
     * How many ticks pass between sensor refreshes.
     *
     * <p>At least one: a {@code sensorUpdateHz} above the tick rate would otherwise round to zero
     * and make {@code tick % period} a division by zero rather than "every tick".
     */
    public int sensorPeriodTicks() {
        return Math.max(1, SimulationConstants.TICK_RATE_HZ / Math.max(1, sensorUpdateHz));
    }

    /** {@link #targetSwitchCooldownS} in ticks. */
    public int targetSwitchCooldownTicks() {
        return Math.round(targetSwitchCooldownS * SimulationConstants.TICK_RATE_HZ);
    }
}
