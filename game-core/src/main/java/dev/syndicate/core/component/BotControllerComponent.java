/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ai.BtState;
import dev.syndicate.core.ai.SensorSnapshot;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.BotDifficulty;

/**
 * An AI driver (docs/04_entity_component_model.md#D04-S4.3.4,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.3).
 *
 * <p>{@link #perceivedWorld} is the bot's <em>only</em> window onto the match. AC-D11-2 forbids
 * {@code BotDecisionSystem} from reading world state directly, and the reason is that difficulty
 * would otherwise mean nothing: every parameter in D11-S4.2 works by degrading perception, so a bot
 * that could see the truth would play identically at every setting.
 *
 * <p>The snapshot is reused in place rather than replaced each refresh, which is why it is final —
 * a bot's perception is per-bot state, and reallocating it every {@code sensorUpdateHz} for every
 * bot is exactly the steady-state garbage D04-S5.6 rules out.
 */
public final class BotControllerComponent implements Component {

    /** Which perception and execution parameters apply (D11-S4.2). */
    public BotDifficulty difficulty = BotDifficulty.NORMAL;

    /** The behaviour-tree node currently executing. */
    public BtState behaviorTreeState = BtState.IDLE;

    /** The entity this bot is engaging, or {@link EntityId#NULL}. */
    public int targetEntity = EntityId.NULL;

    /** Seconds by which this bot's view of the world lags reality. */
    public float reactionDelayS;

    /** The delayed, error-injected view the bot decides from. */
    public final SensorSnapshot perceivedWorld = new SensorSnapshot();

    @Override
    public void reset() {
        difficulty = BotDifficulty.NORMAL;
        behaviorTreeState = BtState.IDLE;
        targetEntity = EntityId.NULL;
        reactionDelayS = 0f;
        perceivedWorld.reset();
    }
}
