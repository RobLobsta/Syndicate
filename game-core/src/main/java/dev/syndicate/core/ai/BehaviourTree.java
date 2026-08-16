/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.core.ecs.EntityId;

/**
 * The priority selector of D11-S5.1, evaluated once per decision tick
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.1).
 *
 * <p>The root is a priority selector: the first branch whose precondition passes runs, and the
 * declaration order below <em>is</em> the tactical priority. That order is fixed (G3), so two peers
 * replaying a tick take the same branch.
 *
 * <p><b>Nothing here writes controls.</b> Every branch writes a destination, a target and a fire
 * intent onto the {@link BotBlackboard}, and the steering and aim solvers convert those into an
 * {@code InputCommand} (D11-R7). A branch that reached for the throttle would be a second driving
 * model to keep in step with the first.
 *
 * <p>D11-S5.1's {@code objective} branch is absent: it applies to {@code PAYLOAD} only, and no
 * payload entity exists to escort. A payload match runs the {@code engage} branch, which is the
 * behaviour a bot with no objective should have.
 */
public final class BehaviourTree {

    /** Ticks below walking pace, while asking for throttle, before the bot decides it is stuck. */
    public static final int STUCK_TICKS = 90;

    /** Speed below which a bot asking for throttle counts as not moving, in m/s. */
    public static final float STUCK_SPEED_MPS = 0.5f;

    /** Throttle above which "not moving" is the vehicle's problem rather than the bot's intent. */
    public static final float STUCK_THROTTLE = 0.5f;

    /** How long an unstick manoeuvre runs: 1.5 s at the tick rate. */
    public static final int UNSTICK_TICKS = 90;

    /** How far behind itself a stuck bot aims while reversing out. */
    public static final float UNSTICK_REVERSE_M = 10.0f;

    /** How far past a target a bot drives when closing, so it does not stop short and idle. */
    public static final float ENGAGE_STANDOFF_M = 15.0f;

    /**
     * How far either side of {@link #ENGAGE_STANDOFF_M} counts as "already at range".
     *
     * <p>Inside this band the bot orbits rather than closing or backing off; see
     * {@link #maintainEngagementRange}.
     */
    public static final float ENGAGE_BAND_M = 8.0f;

    /**
     * How far around the standoff circle an orbiting bot aims, in radians.
     *
     * <p>60° puts the destination a chord of {@code 2 · 15 · sin 30° = 15 m} away — outside
     * {@code ARRIVE_RADIUS_M}, so the bot does not slow for it, and far outside any turning circle
     * it has.
     */
    public static final float ENGAGE_ORBIT_STEP_RAD = (float) (Math.PI / 3.0);

    private final Vector3 scratch = new Vector3();
    private final Vector3 tangent = new Vector3();

    /**
     * Runs the tree and leaves its decision on the blackboard.
     *
     * @param aimPoint receives where to shoot, when the chosen branch wants to shoot
     * @param patrolPoint the next arena point of interest, or null when the arena declares none
     * @return the branch that ran, for {@code behaviorTreeState}
     */
    public BtState tick(
            BotControllerComponent bot,
            BotDifficultyParams params,
            SensorSnapshot snapshot,
            Vector3 forward,
            int chosenTarget,
            boolean hasDrivableWheels,
            Vector3 patrolPoint,
            TargetSelection targeting,
            Vector3 aimPoint) {

        BotBlackboard board = bot.blackboard;
        board.clear();

        // ---- survive -----------------------------------------------------------------
        if (snapshot.selfIntegrity < params.retreatHealthFraction()) {
            board.aggression = 1f;
            board.driveTo(retreatDestination(snapshot, patrolPoint));
            // Opportunistic fire: a retreating bot still shoots whatever is in front of it, which is
            // what stops a retreat looking like a bot that gave up.
            engageOpportunistically(board, snapshot, chosenTarget, params, targeting, aimPoint);
            return BtState.RETREAT;
        }

        // ---- unstick -----------------------------------------------------------------
        if (bot.unstickTicksRemaining > 0) {
            bot.unstickTicksRemaining--;
            board.driveTo(scratch.set(snapshot.selfPosition).mulAdd(forward, -UNSTICK_REVERSE_M));
            return BtState.UNSTICK;
        }
        // D11-E2: a bot with no drivable wheels is immobile, and must not loop the unstick behaviour
        // forever trying to drive out of it. It becomes a stationary turret instead.
        if (hasDrivableWheels && bot.stuckTicks >= STUCK_TICKS) {
            bot.stuckTicks = 0;
            bot.unstickTicksRemaining = UNSTICK_TICKS;
            board.driveTo(scratch.set(snapshot.selfPosition).mulAdd(forward, -UNSTICK_REVERSE_M));
            return BtState.UNSTICK;
        }

        // ---- engage ------------------------------------------------------------------
        PerceivedTarget target = chosenTarget == EntityId.NULL ? null : snapshot.targetById(chosenTarget);
        if (target != null && target.hasLineOfSight) {
            board.target = target.entity;
            targeting.aimPointFor(target, params, aimPoint);
            board.aimPoint.set(aimPoint);
            board.wantsToFire = true;
            if (hasDrivableWheels) {
                board.driveTo(maintainEngagementRange(snapshot, target, forward));
            }
            return BtState.ENGAGE;
        }

        // ---- hunt --------------------------------------------------------------------
        if (target != null) {
            board.target = target.entity;
            targeting.aimPointFor(target, params, aimPoint);
            board.aimPoint.set(aimPoint);
            // Remembered, not seen: aim at where it should be, but do not shoot through the wall
            // it is behind (D11-R6, AC-D11-5).
            board.wantsToFire = false;
            board.driveTo(target.position);
            return BtState.PURSUE;
        }

        // ---- patrol ------------------------------------------------------------------
        if (patrolPoint != null) {
            board.driveTo(patrolPoint);
        }
        return BtState.PATROL;
    }

    /**
     * Where a retreating bot heads.
     *
     * <p>Directly away from the nearest threat, and toward the patrol point when the difficulty uses
     * cover — a spawn point is the closest thing the arena declares to a safe place. A bot that
     * retreated to a fixed corner would be found there every time.
     */
    private Vector3 retreatDestination(SensorSnapshot snapshot, Vector3 patrolPoint) {
        PerceivedTarget nearest = nearestVisible(snapshot);
        if (nearest == null) {
            return patrolPoint == null ? snapshot.selfPosition : patrolPoint;
        }
        scratch.set(snapshot.selfPosition).sub(nearest.position);
        scratch.y = 0f;
        if (scratch.len2() < 1e-4f) {
            return patrolPoint == null ? snapshot.selfPosition : patrolPoint;
        }
        return scratch.nor().scl(RETREAT_DISTANCE_M).add(snapshot.selfPosition);
    }

    /** How far a retreating bot tries to put between itself and the nearest threat. */
    public static final float RETREAT_DISTANCE_M = 60.0f;

    /**
     * Closes to, backs off to, or orbits at a standoff distance (D11-S5.1 {@code
     * MaintainEngagementRange}).
     *
     * <p>Driving <em>at</em> a target means arriving on top of it and then having no room to shoot;
     * driving to a point short of it keeps the bot at a distance where its guns bear. The standoff is
     * expressed as a point rather than as a range check so that the one steering solver handles both
     * "too far" and "too close" without a second branch.
     *
     * <p><b>The third case is why this is not two lines.</b> A bot already near standoff gets a
     * radial destination a couple of metres from where it stands — which is <em>inside its own
     * turning circle</em>, so no steering angle reaches it. The solver answers with full lock and,
     * because the bot never gets above {@code CREEP_SPEED_MPS}, the creep floor keeps feeding it
     * just enough throttle to scrub round at walking pace. The bot shuffles back and forth for the
     * rest of the match: 18 m travelled in 12 seconds while its opponents covered 118 m
     * (DISC-059). Every metre of that band is reachable only tangentially, so inside it the bot
     * orbits instead — which is also the better tactic, a stationary vehicle at 15 m being a free
     * kill.
     *
     * <p>Which way round is decided by the bot's own nose rather than by a draw: whichever tangent
     * needs the smaller turn, and the left-hand one when the two are exactly equal. That is
     * deterministic (G3) and it varies between bots without needing a random stream.
     */
    private Vector3 maintainEngagementRange(SensorSnapshot snapshot, PerceivedTarget target, Vector3 forward) {
        scratch.set(snapshot.selfPosition).sub(target.position);
        scratch.y = 0f;
        float distance = scratch.len();
        if (distance < 0.001f) {
            return target.position;
        }
        if (Math.abs(distance - ENGAGE_STANDOFF_M) > ENGAGE_BAND_M) {
            return scratch.scl(ENGAGE_STANDOFF_M / distance).add(target.position);
        }
        scratch.scl(1f / distance);
        // The radial direction rotated a quarter turn about world up.
        tangent.set(-scratch.z, 0f, scratch.x);
        float sense = forward.x * tangent.x + forward.z * tangent.z >= 0f ? 1f : -1f;
        float cos = (float) Math.cos(ENGAGE_ORBIT_STEP_RAD);
        float sin = (float) Math.sin(ENGAGE_ORBIT_STEP_RAD) * sense;
        return scratch.scl(ENGAGE_STANDOFF_M * cos)
                .mulAdd(tangent, ENGAGE_STANDOFF_M * sin)
                .add(target.position);
    }

    private void engageOpportunistically(
            BotBlackboard board,
            SensorSnapshot snapshot,
            int chosenTarget,
            BotDifficultyParams params,
            TargetSelection targeting,
            Vector3 aimPoint) {

        PerceivedTarget target = chosenTarget == EntityId.NULL ? null : snapshot.targetById(chosenTarget);
        if (target == null) {
            target = nearestVisible(snapshot);
        }
        if (target == null || !target.hasLineOfSight) {
            return;
        }
        board.target = target.entity;
        targeting.aimPointFor(target, params, aimPoint);
        board.aimPoint.set(aimPoint);
        board.wantsToFire = true;
    }

    /** The closest target currently in sight, or null. Ascending entity order breaks exact ties (G3). */
    private static PerceivedTarget nearestVisible(SensorSnapshot snapshot) {
        PerceivedTarget best = null;
        float bestDistance = Float.MAX_VALUE;
        for (PerceivedTarget target : snapshot.targets()) {
            if (!target.hasLineOfSight) {
                continue;
            }
            float distance = target.position.dst2(snapshot.selfPosition);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = target;
            }
        }
        return best;
    }
}
