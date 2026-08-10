/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ai;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.ClosestRayResultCallback;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.util.Pcg32;
import dev.syndicate.core.util.StreamId;
import dev.syndicate.core.vehicle.VehicleIntegrity;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;

/**
 * Builds the delayed, error-injected view a bot decides from
 * (docs/11_ai_bots_and_match_simulation.md#D11-S4.3, D11-R5, D11-R6).
 *
 * <p><b>This class is the whole of AC-D11-2.</b> Every read of live world state a bot's behaviour
 * depends on happens here, and the result goes into a {@link SensorSnapshot} the decision loop then
 * treats as the only truth. If the tree could reach past it, no difficulty parameter would mean
 * anything: reaction delay, aim error and line of sight are all imposed at this boundary and nowhere
 * else.
 *
 * <p><b>The delay is applied to the perceived position, not by replaying history.</b> D11-S4.3 reads
 * the lag-compensation history buffer of D10-S5.7, and there is no such buffer yet — networking is
 * unwritten. Rather than perceive the present and call it delayed, a target's position is rewound
 * along its own velocity by the reaction delay, which is the same first-order reconstruction the
 * history buffer would give for a vehicle that is not turning hard, and is wrong in the same
 * direction (behind the truth) when it is. When D10-S5.7 lands, this is the one method that changes.
 */
public final class BotSensors {

    /**
     * How far a bot can perceive, in metres.
     *
     * <p>D11-S4.3 reads {@code bot.stats.sensorRangeM} from D05-S4.5's stat table, and that table has
     * no such stat — no part authors one and {@code VehicleStatsComponent} has no field for it. A
     * constant rather than an invented stat: perception range is a property of the AI, not of the
     * car, and making it content would let an assembly buy sight, which is the kind of advantage
     * D11-R6 exists to forbid.
     */
    public static final float SENSOR_RANGE_M = 150.0f;

    /** How far a projectile has to be to be noticed at all (D11-S4.3 {@code PROJECTILE_NOTICE_M}). */
    public static final float PROJECTILE_NOTICE_M = 60.0f;

    /** Rays in the forward obstacle fan (D11-S5.3). */
    public static final int OBSTACLE_FAN_RAYS = 7;

    /** Total spread of that fan, in degrees. */
    public static final float OBSTACLE_FAN_SPREAD_DEG = 100.0f;

    /** Shortest fan the bot will cast, so a stationary bot still sees the wall it is against. */
    public static final float OBSTACLE_FAN_MIN_LENGTH_M = 6.0f;

    /**
     * Position error in metres per metre of range, at one radian of aim error.
     *
     * <p>Scaling the sigma with distance is what makes a distant target genuinely harder rather than
     * uniformly offset: at 100 m an {@code EASY} bot's belief is several metres out, at 10 m it is
     * centimetres, which is how a person's estimate behaves.
     */
    public static final float POSITION_ERROR_PER_RADIAN_PER_METRE = 1.0f;

    /**
     * How strongly the aim-error random walk is pulled back toward zero each refresh.
     *
     * <p>Without it the walk is free and drifts away unboundedly, which D11-E18 forbids. With it the
     * walk is a bounded Ornstein-Uhlenbeck process whose stationary spread is the difficulty's
     * {@code aimErrorRad}; the hard clamp below is a second belt on the same trousers.
     */
    public static final float AIM_ERROR_REVERSION = 0.15f;

    /** The walk is clamped to this many standard deviations (D11-E18). */
    public static final float AIM_ERROR_CLAMP_SIGMA = 3.0f;

    /** The sensor origin's height above the vehicle's origin, in metres. Roughly a driver's eyeline. */
    public static final float SENSOR_ORIGIN_HEIGHT_M = 1.0f;

    private final PhysicsWorld physics;

    private final Vector3 from = new Vector3();
    private final Vector3 to = new Vector3();
    private final Vector3 selfPos = new Vector3();
    private final Vector3 otherPos = new Vector3();
    private final Vector3 otherVel = new Vector3();
    private final Vector3 error = new Vector3();
    private final Vector3 forward = new Vector3();
    private final Vector3 fanDirection = new Vector3();
    private final Vector3 hitPoint = new Vector3();

    public BotSensors(PhysicsWorld physics) {
        this.physics = physics;
    }

    /**
     * Refreshes a bot's snapshot if this tick is a refresh tick.
     *
     * <p>The stagger is deliberate: bots refresh on {@code tick % period == entityIndex % period}
     * rather than all on the same tick. Twelve bots all rebuilding perception on multiples of four
     * would put twelve times the ray-cast cost on one tick in fifteen, which is a frame spike that
     * shows up as stutter rather than as load.
     *
     * @return true when the snapshot was rebuilt this tick
     */
    public boolean refresh(
            World world,
            int vehicleEntity,
            BotControllerComponent bot,
            BotDifficultyParams params,
            Family vehicles,
            long tick) {

        int period = params.sensorPeriodTicks();
        if (Math.floorMod(tick - EntityId.index(vehicleEntity), period) != 0) {
            return false;
        }
        SensorSnapshot snapshot = bot.perceivedWorld;
        snapshot.capturedTick = Math.max(0L, tick - params.reactionDelayTicks());

        TransformComponent selfTransform = world.getComponent(vehicleEntity, TransformComponent.class);
        VelocityComponent selfVelocity = world.getComponent(vehicleEntity, VelocityComponent.class);
        if (selfTransform == null) {
            snapshot.beginTargets();
            snapshot.nearbyObstacles.clear();
            return true;
        }
        selfPos.set(selfTransform.position);
        snapshot.selfPosition.set(selfPos);
        snapshot.selfVelocity.set(selfVelocity == null ? Vector3.Zero : selfVelocity.linear);
        snapshot.selfIntegrity = VehicleIntegrity.fraction(world, vehicleEntity);

        Pcg32 rng = world.random().stream(StreamId.BOT_DECISION);
        advanceAimError(bot, params, rng);
        bot.memory.forgetStale(tick);
        perceiveVehicles(world, vehicleEntity, bot, params, vehicles, rng, tick);
        castObstacleFan(world, vehicleEntity, selfTransform, snapshot, params);
        return true;
    }

    // ---- Targets ---------------------------------------------------------------------

    /**
     * Fills the snapshot's target list from what is in range and in sight.
     *
     * <p>Iteration is over the vehicle family, which is ascending entity id, so two peers replaying
     * this tick build the same list in the same order (G3) — and the seeded error draws therefore
     * land on the same targets.
     */
    private void perceiveVehicles(
            World world,
            int selfVehicle,
            BotControllerComponent bot,
            BotDifficultyParams params,
            Family vehicles,
            Pcg32 rng,
            long tick) {

        SensorSnapshot snapshot = bot.perceivedWorld;
        snapshot.beginTargets();
        float delayS = params.reactionDelayS();
        int[] entityIds = vehicles.snapshot();
        int count = vehicles.size();

        for (int i = 0; i < count; i++) {
            int other = entityIds[i];
            if (other == selfVehicle || !world.isAlive(other)) {
                continue;
            }
            TransformComponent transform = world.getComponent(other, TransformComponent.class);
            if (transform == null) {
                continue;
            }
            otherPos.set(transform.position);
            float distance = otherPos.dst(selfPos);
            if (distance > SENSOR_RANGE_M) {
                // Out of range is not remembered either: D11-R6 makes range a hard perception cut,
                // so a target that drove away stops being tracked rather than becoming a ghost.
                continue;
            }
            VelocityComponent velocity = world.getComponent(other, VelocityComponent.class);
            otherVel.set(velocity == null ? Vector3.Zero : velocity.linear);
            float integrity = VehicleIntegrity.fraction(world, other);
            int teamId = teamOf(world, other);

            if (hasLineOfSight(selfPos, otherPos)) {
                bot.memory.remember(other, otherPos, otherVel, integrity, teamId, tick);
                PerceivedTarget target = snapshot.addTarget();
                target.entity = other;
                // Rewound by the reaction delay, then offset by the perception error. Both are
                // degradations of the truth and both scale with difficulty; see the class note.
                target.position.set(otherPos).mulAdd(otherVel, -delayS).add(positionError(params, distance, rng));
                target.velocity.set(otherVel);
                target.integrity = integrity;
                target.teamId = teamId;
                target.hasLineOfSight = true;
                target.lastSeenTick = tick;
                continue;
            }
            BotMemory.Trace trace = bot.memory.recall(other, tick);
            if (trace == null) {
                continue;
            }
            // Dead reckoning from the last sighting (D11-R6). No fresh error is added: the belief is
            // already as stale as it is going to get, and jittering it again would make a bot hunting
            // a remembered target wander rather than commit.
            float elapsedS = (tick - trace.lastSeenTick) * SimulationConstants.TICK_DT;
            PerceivedTarget target = snapshot.addTarget();
            target.entity = other;
            target.position.set(trace.position).mulAdd(trace.velocity, elapsedS);
            target.velocity.set(trace.velocity);
            target.integrity = trace.integrity;
            target.teamId = trace.teamId;
            target.hasLineOfSight = false;
            target.lastSeenTick = trace.lastSeenTick;
        }
    }

    /**
     * One ray from the bot's sensor origin to the target's, on the {@code SENSOR_RAY} mask.
     *
     * <p>The query group is {@link PhysicsWorld#BULLET_DEFAULT_FILTER}, not {@code SENSOR_RAY.bit()}.
     * Bullet's filter test is bidirectional, and no vehicle's D06-S4.4 mask contains the sensor bit —
     * a ray issued on that group would pass through every car in the arena and report every target
     * as visible. This is DISC-011's trap in a second place, and DEV-012's default-filter bit is what
     * makes the query work at all.
     */
    private boolean hasLineOfSight(Vector3 origin, Vector3 target) {
        if (physics == null) {
            // No physics world: a pure-logic test. Everything in range is visible, which keeps the
            // decision loop testable without Bullet.
            return true;
        }
        from.set(origin).add(0f, SENSOR_ORIGIN_HEIGHT_M, 0f);
        to.set(target).add(0f, SENSOR_ORIGIN_HEIGHT_M, 0f);
        ClosestRayResultCallback callback = new ClosestRayResultCallback(from, to);
        try {
            callback.setCollisionFilterGroup(PhysicsWorld.BULLET_DEFAULT_FILTER);
            callback.setCollisionFilterMask(CollisionLayer.SENSOR_RAY.mask());
            physics.dynamicsWorld().rayTest(from, to, callback);
            if (!callback.hasHit()) {
                return true;
            }
            // The first thing the ray meets is either the target itself or something between. The
            // hit fraction is the cheap test: anything stopping the ray short of the far end is
            // cover, and the target's own hull stops it just before the end.
            return callback.getClosestHitFraction() >= LINE_OF_SIGHT_CLEAR_FRACTION;
        } finally {
            callback.dispose();
        }
    }

    /**
     * How far along the ray a hit must be for the target to count as visible.
     *
     * <p>A target's own hull stops the ray a metre or two short of its centre, which at close range
     * is a large fraction of the segment. 0.9 is chosen so a ray that reaches the target's shell
     * counts, and one stopped by a wall in the last tenth does not — the case that costs a bot a
     * shot it should not have had is far rarer than the case that lets it shoot through a wall.
     */
    private static final float LINE_OF_SIGHT_CLEAR_FRACTION = 0.9f;

    /** A seeded Gaussian offset, scaled by difficulty and by range. */
    private Vector3 positionError(BotDifficultyParams params, float distanceM, Pcg32 rng) {
        float sigma = params.aimErrorRad() * distanceM * POSITION_ERROR_PER_RADIAN_PER_METRE;
        return error.set(rng.nextGaussian() * sigma, rng.nextGaussian() * sigma * 0.25f, rng.nextGaussian() * sigma);
    }

    // ---- Aim error walk (D11-E18) -----------------------------------------------------

    /**
     * Advances the bounded random walk that offsets a bot's aim.
     *
     * <p>Mean-reverting and hard-clamped, so it wanders within a few times the difficulty's sigma and
     * never escapes. Advanced once per sensor refresh rather than per tick, which is what makes a
     * {@code BRUTAL} bot's aim steadier than an {@code EASY} bot's twice over: smaller sigma, and a
     * walk that takes smaller steps because it steps less often relative to how fast it aims.
     */
    private void advanceAimError(BotControllerComponent bot, BotDifficultyParams params, Pcg32 rng) {
        float sigma = params.aimErrorRad();
        float limit = sigma * AIM_ERROR_CLAMP_SIGMA;
        Vector3 offset = bot.aimErrorOffset;
        offset.scl(1f - AIM_ERROR_REVERSION);
        offset.add(
                rng.nextGaussian() * sigma * AIM_ERROR_REVERSION, 0f, rng.nextGaussian() * sigma * AIM_ERROR_REVERSION);
        offset.x = clamp(offset.x, -limit, limit);
        offset.y = clamp(offset.y, -limit, limit);
        offset.z = clamp(offset.z, -limit, limit);
    }

    // ---- Obstacles -------------------------------------------------------------------

    /**
     * Casts the forward ray fan the steering solver avoids with.
     *
     * <p>Its length is the bot's own speed times its avoidance lookahead, so a fast bot looks further
     * ahead than a slow one and the horizon is expressed in time rather than distance — which is what
     * makes the same parameter work for a vehicle doing 5 m/s and one doing 80.
     */
    private void castObstacleFan(
            World world,
            int vehicleEntity,
            TransformComponent transform,
            SensorSnapshot snapshot,
            BotDifficultyParams params) {

        snapshot.nearbyObstacles.clear();
        if (physics == null) {
            return;
        }
        float speed = snapshot.selfVelocity.len();
        float length = Math.max(OBSTACLE_FAN_MIN_LENGTH_M, speed * params.avoidanceLookaheadS());
        forward.set(0f, 0f, 1f).mul(transform.rotation).nor();
        float halfSpread = OBSTACLE_FAN_SPREAD_DEG * 0.5f;
        float step = OBSTACLE_FAN_RAYS > 1 ? OBSTACLE_FAN_SPREAD_DEG / (OBSTACLE_FAN_RAYS - 1) : 0f;

        from.set(transform.position).add(0f, SENSOR_ORIGIN_HEIGHT_M, 0f);
        for (int i = 0; i < OBSTACLE_FAN_RAYS; i++) {
            fanDirection.set(forward).rotate(Vector3.Y, -halfSpread + step * i);
            to.set(from).mulAdd(fanDirection, length);
            ClosestRayResultCallback callback = new ClosestRayResultCallback(from, to);
            try {
                callback.setCollisionFilterGroup(PhysicsWorld.BULLET_DEFAULT_FILTER);
                callback.setCollisionFilterMask(CollisionLayer.SENSOR_RAY.mask());
                physics.dynamicsWorld().rayTest(from, to, callback);
                if (!callback.hasHit()) {
                    continue;
                }
                callback.getHitPointWorld(hitPoint);
                if (entityAt(world, vehicleEntity, hitPoint)) {
                    continue;
                }
                snapshot.nearbyObstacles.add(new Vector3(hitPoint));
            } finally {
                callback.dispose();
            }
        }
    }

    /** True when the hit is inside the bot's own footprint, which is its own hull, not an obstacle. */
    private boolean entityAt(World world, int selfVehicle, Vector3 point) {
        TransformComponent transform = world.getComponent(selfVehicle, TransformComponent.class);
        return transform != null && transform.position.dst2(point) < 4f;
    }

    private static int teamOf(World world, int vehicleEntity) {
        TeamComponent team = world.getComponent(vehicleEntity, TeamComponent.class);
        return team == null ? TeamComponent.FREE_FOR_ALL : team.teamId;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /** The family a bot perceives through: every vehicle with a place in the world. */
    public static dev.syndicate.core.ecs.ComponentQuery.Builder perceivableVehicles() {
        return dev.syndicate.core.ecs.ComponentQuery.all(VehicleChassisComponent.class, TransformComponent.class);
    }
}
