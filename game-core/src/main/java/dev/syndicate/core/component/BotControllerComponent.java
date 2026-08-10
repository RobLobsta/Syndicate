/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ai.BotBlackboard;
import dev.syndicate.core.ai.BotMemory;
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
 * bot is exactly the steady-state garbage D04-S5.6 rules out. The same applies to
 * {@link #memory} and {@link #blackboard}.
 *
 * <p><b>The cross-tick fields are here rather than on the system.</b> D04-R3 requires systems to be
 * stateless with respect to gameplay, and every one of {@link #aimYawRad}, {@link #aimErrorOffset},
 * {@link #lastTargetSwitchTick} and {@link #stuckTicks} is state a rollback has to restore: a bot
 * whose aim error was re-drawn on a client's replay would shoot somewhere else than the authority
 * says it did.
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

    /** Targets seen recently but not currently visible (D11-R6). */
    public final BotMemory memory = new BotMemory();

    /** What the behaviour tree decided this tick, before the solvers read it (D11-R7). */
    public final BotBlackboard blackboard = new BotBlackboard();

    /** Current aim yaw in radians, converging on the solution at {@code aimSettleRate}. */
    public float aimYawRad;

    /** Current aim pitch in radians. */
    public float aimPitchRad;

    /**
     * A slowly-varying aim offset, in metres, added to the aim point.
     *
     * <p>A bounded random walk rather than per-tick jitter, exactly as D11-S5.3 requires and D11-E18
     * bounds. Jitter at 60 Hz averages out over a burst and reads as a machine that is perfectly
     * accurate and shaking; a drift reads as a person who is slightly off this second and slightly
     * off the other way next second.
     */
    public final Vector3 aimErrorOffset = new Vector3();

    /** The tick {@link #targetEntity} last changed, for the re-targeting cooldown (D11-S5.2). */
    public long lastTargetSwitchTick = Long.MIN_VALUE;

    /** Consecutive ticks spent below walking pace while asking for throttle (D11-S5.1 {@code unstick}). */
    public int stuckTicks;

    /** Ticks of the unstick manoeuvre still to run, or 0. */
    public int unstickTicksRemaining;

    /** Which of the arena's patrol destinations this bot is heading for. */
    public int patrolIndex;

    @Override
    public void reset() {
        difficulty = BotDifficulty.NORMAL;
        behaviorTreeState = BtState.IDLE;
        targetEntity = EntityId.NULL;
        reactionDelayS = 0f;
        perceivedWorld.reset();
        memory.clear();
        blackboard.clear();
        aimYawRad = 0f;
        aimPitchRad = 0f;
        aimErrorOffset.set(0f, 0f, 0f);
        lastTargetSwitchTick = Long.MIN_VALUE;
        stuckTicks = 0;
        unstickTicksRemaining = 0;
        patrolIndex = 0;
    }
}
