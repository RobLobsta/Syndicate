/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ai.BehaviourTree;
import dev.syndicate.core.ai.BotDifficultyParams;
import dev.syndicate.core.ai.BotDifficultyTable;
import dev.syndicate.core.ai.BotSensors;
import dev.syndicate.core.ai.PerceivedTarget;
import dev.syndicate.core.ai.SensorSnapshot;
import dev.syndicate.core.ai.SteeringSolver;
import dev.syndicate.core.ai.TargetSelection;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.WeaponBlock;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.match.MatchFacts;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.core.vehicle.StatBlock;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 3: bots decide, and write the same component a human's client writes
 * (docs/04_entity_component_model.md#D04-S4.4, docs/11_ai_bots_and_match_simulation.md#D11-S5.3).
 *
 * <p>Authority only. The loop is D11-S5.3's six steps in order — perceive, decide, navigate, steer,
 * aim, fire — and its <b>only</b> output is {@link PlayerInputComponent} (D11-R2, AC-D11-1). Every
 * system after this one reads intent and cannot tell what produced it, which is what makes G17 hold
 * for AI: a bot is not a special case of a driver, it is a driver.
 *
 * <p><b>Navigation is direct steering, not a navmesh path.</b> D11-S5.4 generates a navmesh offline
 * into {@code arenas/&lt;id&gt;/navmesh.bin} and A*s over it; no arena ships one and the generator is
 * unwritten. D11-E4 specifies exactly this case — "bots fall back to direct steering with obstacle
 * avoidance and log at ERROR once" — so that is what happens, once per arena rather than once per
 * bot per tick. The arena is a convex box with no interior geometry, so a path and a straight line
 * currently coincide; the moment cover ships, the fallback will visibly fail and the log will say
 * why.
 *
 * <p><b>Cost.</b> The expensive part is perception, and it is refreshed at {@code sensorUpdateHz}
 * with a per-bot phase offset rather than every tick for every bot (see {@code BotSensors.refresh}),
 * which is what keeps AC-D11-16's 0.8 ms budget for eleven bots reachable.
 */
public final class BotDecisionSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(BotDecisionSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 3;

    /** How close to its aim solution a bot must be before it will pull the trigger. */
    public static final float AIM_TOLERANCE_RAD = 0.08f;

    private final AssetIndex assets;
    private final BotDifficultyTable difficulties;
    private final BotSensors sensors;

    private final TargetSelection targeting = new TargetSelection();
    private final BehaviourTree tree = new BehaviourTree();
    private final SteeringSolver steering = new SteeringSolver();

    private final Vector3 forward = new Vector3();
    private final Vector3 aimPoint = new Vector3();
    private final Vector3 lead = new Vector3();
    private final Vector3 toAim = new Vector3();

    /** How many bots are engaging each entity, for the focus-fire term. Rebuilt each tick. */
    private final Map<Integer, Integer> engagementCensus = new TreeMap<>();

    private Family bots;
    private Family vehicles;

    private boolean navmeshWarningLogged;

    public BotDecisionSystem(AssetIndex assets, BotDifficultyTable difficulties, PhysicsWorld physics) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.difficulties = Objects.requireNonNull(difficulties, "difficulties");
        this.sensors = new BotSensors(physics);
    }

    @Override
    public Phase phase() {
        return Phase.INPUT;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        bots = world.family(ComponentQuery.all(
                BotControllerComponent.class,
                PlayerInputComponent.class,
                VehicleChassisComponent.class,
                TransformComponent.class));
        vehicles = world.family(BotSensors.perceivableVehicles());
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        if (bots.isEmpty()) {
            return;
        }
        warnAboutMissingNavmeshOnce(world);
        buildEngagementCensus(world);
        boolean friendlyFire = friendlyFire(world);
        ArenaDef arena = arena(world);

        int[] entityIds = bots.snapshot();
        int count = bots.size();
        for (int i = 0; i < count; i++) {
            int vehicleEntity = entityIds[i];
            try {
                decide(world, vehicleEntity, arena, friendlyFire, dtSeconds, tick);
            } catch (RuntimeException e) {
                // D11-E16: one bad bot must not abort the match. Its tree is reset to the root and
                // the tick continues; the entity, the tick and the node are in the log.
                BotControllerComponent bot = world.getComponent(vehicleEntity, BotControllerComponent.class);
                LOG.error(
                        "bot {} threw in node {} at tick {}; resetting its tree",
                        EntityId.toString(vehicleEntity),
                        bot == null ? "(unknown)" : bot.behaviorTreeState,
                        tick,
                        e);
                if (bot != null) {
                    bot.behaviorTreeState = dev.syndicate.core.ai.BtState.IDLE;
                    bot.targetEntity = EntityId.NULL;
                    bot.unstickTicksRemaining = 0;
                }
            }
        }
    }

    // ---- One bot's tick ---------------------------------------------------------------

    private void decide(
            World world, int vehicleEntity, ArenaDef arena, boolean friendlyFire, float dtSeconds, long tick) {

        BotControllerComponent bot = world.getComponent(vehicleEntity, BotControllerComponent.class);
        PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
        TransformComponent transform = world.getComponent(vehicleEntity, TransformComponent.class);
        if (bot == null || input == null || transform == null) {
            return;
        }
        BotDifficultyParams params = difficulties.get(bot.difficulty);
        bot.reactionDelayS = params.reactionDelayS();

        // ---- 1. PERCEIVE --------------------------------------------------------------
        sensors.refresh(world, vehicleEntity, bot, params, vehicles, tick);
        SensorSnapshot snapshot = bot.perceivedWorld;
        forward.set(0f, 0f, 1f).mul(transform.rotation).nor();

        // ---- 2. DECIDE ----------------------------------------------------------------
        int teamId = teamOf(world, vehicleEntity);
        int lastAttacker = lastAttackerOf(world, vehicleEntity);
        int chosen = targeting.select(
                bot,
                params,
                snapshot,
                forward,
                teamId,
                friendlyFire,
                lastAttacker,
                entity -> engagementCensus.getOrDefault(entity, 0),
                tick);
        boolean drivable = hasDrivableWheels(world, vehicleEntity);
        Vector3 patrol = patrolPoint(arena, bot);
        bot.behaviorTreeState =
                tree.tick(bot, params, snapshot, forward, chosen, drivable, patrol, targeting, aimPoint);
        bot.targetEntity = bot.blackboard.target;

        // ---- 3/4. NAVIGATE and STEER ---------------------------------------------------
        VehicleStatsComponent stats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        VelocityComponent velocity = world.getComponent(vehicleEntity, VelocityComponent.class);
        float speed = velocity == null ? 0f : velocity.linear.len();
        SteeringSolver.Controls controls = SteeringSolver.Controls.IDLE;
        if (bot.blackboard.hasDestination && drivable) {
            controls = steering.solve(
                    transform.position,
                    forward,
                    bot.blackboard.destination,
                    snapshot,
                    stats == null ? 0.5f : stats.maxSteerRad,
                    speed,
                    stats == null ? 0f : stats.maxSpeedMps);
        }
        float throttle = controls.throttle() * params.throttleAggression() * bot.blackboard.aggression;
        // An unstick manoeuvre reverses; the tree put the destination behind the bot and the solver
        // turned that into "brake, then turn". Reversing is what actually gets it out.
        if (bot.behaviorTreeState == dev.syndicate.core.ai.BtState.UNSTICK) {
            throttle = -Math.abs(params.throttleAggression());
        }
        updateStuckCounter(bot, speed, throttle);

        // ---- 5. AIM --------------------------------------------------------------------
        if (bot.blackboard.target != EntityId.NULL) {
            aimAt(world, vehicleEntity, bot, params, snapshot, transform.position, dtSeconds);
        }

        // ---- 6. FIRE -------------------------------------------------------------------
        int fireMask = bot.blackboard.wantsToFire
                ? fireMask(world, vehicleEntity, bot, params, snapshot, transform.position)
                : 0;

        // ---- 7. EMIT -------------------------------------------------------------------
        input.throttle = clamp(throttle, -1f, 1f);
        input.steer = clamp(controls.steer(), -1f, 1f);
        input.brake = clamp(controls.brake(), 0f, 1f);
        input.aimYawRad = bot.aimYawRad;
        input.aimPitchRad = bot.aimPitchRad;
        input.fireMask = fireMask;
        input.commandTick = tick;
    }

    // ---- Aim ---------------------------------------------------------------------------

    /**
     * Converges the bot's aim on its target, with lead and with its persistent error.
     *
     * <p>Lead is scaled by {@code leadPredictionQuality}, so an {@code EASY} bot applies less than a
     * third of the correct lead and consistently shoots behind a crossing target — which is what a
     * poor player does, rather than what a good player with a handicap does.
     */
    private void aimAt(
            World world,
            int vehicleEntity,
            BotControllerComponent bot,
            BotDifficultyParams params,
            SensorSnapshot snapshot,
            Vector3 origin,
            float dtSeconds) {

        PerceivedTarget target = snapshot.targetById(bot.blackboard.target);
        if (target == null) {
            return;
        }
        aimPoint.set(bot.blackboard.aimPoint);
        float projectileSpeed = fastestProjectileSpeed(world, vehicleEntity);
        if (projectileSpeed > 0f) {
            float timeOfFlight = aimPoint.dst(origin) / projectileSpeed;
            lead.set(target.velocity).scl(timeOfFlight * params.leadPredictionQuality());
            aimPoint.add(lead);
        }
        aimPoint.add(bot.aimErrorOffset);

        toAim.set(aimPoint).sub(origin);
        float horizontal = (float) Math.sqrt(toAim.x * toAim.x + toAim.z * toAim.z);
        float desiredYaw = (float) Math.atan2(toAim.x, toAim.z);
        float desiredPitch = (float) Math.atan2(toAim.y, Math.max(0.001f, horizontal));
        float step = params.aimSettleRateRadPerS() * dtSeconds;
        bot.aimYawRad = moveTowardAngle(bot.aimYawRad, desiredYaw, step);
        bot.aimPitchRad = moveToward(bot.aimPitchRad, desiredPitch, step);
    }

    // ---- Fire --------------------------------------------------------------------------

    /**
     * Which weapon groups fire this tick (D11-S5.3 step 6, D11-S5.5).
     *
     * <p>Every gate here is one the weapon system would apply anyway — cooldown, ammunition, heat —
     * and checking them first is not duplication but discipline: a bot that spammed the fire bit
     * every tick would look identical to one that fired at the right moment, because slot 8 would
     * drop the extra requests, and there would be no way to tell a well-timed bot from a jammed one.
     *
     * <p>D11-R10 is what makes this safe: the same components the server owns hold the cooldowns, so
     * a bot cannot fire faster than a human with the same vehicle.
     */
    private int fireMask(
            World world,
            int vehicleEntity,
            BotControllerComponent bot,
            BotDifficultyParams params,
            SensorSnapshot snapshot,
            Vector3 origin) {

        PerceivedTarget target = snapshot.targetById(bot.blackboard.target);
        if (target == null || !target.hasLineOfSight) {
            return 0;
        }
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (graph == null) {
            return 0;
        }
        toAim.set(bot.blackboard.aimPoint).sub(origin);
        float distance = toAim.len();
        forward.set(0f, 0f, 1f);
        // The aim direction the bot has actually converged to, not the one it wants.
        float aimedYaw = bot.aimYawRad;
        float desiredYaw = (float) Math.atan2(toAim.x, toAim.z);
        float aimErrorRad = Math.abs(normaliseAngle(desiredYaw - aimedYaw));

        int mask = 0;
        for (SlotNode node : graph.nodes) {
            WeaponControllerComponent weapon = weaponOf(world, node.childEntity);
            if (weapon == null || weapon.cooldownRemainingS > 0f || weapon.ammoRemaining == 0 || weapon.heat > 0.9f) {
                continue;
            }
            if (!isAlive(world, node.childEntity)) {
                continue;
            }
            float range = effectiveRangeOf(world, node.childEntity);
            if (distance > range * params.firingDisciplineRange()) {
                continue;
            }
            if (aimErrorRad > AIM_TOLERANCE_RAD) {
                continue;
            }
            mask |= 1 << weapon.groupIndex;
        }
        return mask;
    }

    private float effectiveRangeOf(World world, int partEntity) {
        var partRef = world.getComponent(partEntity, dev.syndicate.core.component.PartRefComponent.class);
        if (partRef == null || partRef.partTypeId == null) {
            return BotSensors.SENSOR_RANGE_M;
        }
        var type = assets.partType(partRef.partTypeId);
        if (type == null || type.weapon() == null) {
            return BotSensors.SENSOR_RANGE_M;
        }
        return type.weapon().effectiveRangeM();
    }

    private float fastestProjectileSpeed(World world, int vehicleEntity) {
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (graph == null) {
            return 0f;
        }
        float fastest = 0f;
        for (SlotNode node : graph.nodes) {
            var partRef = world.getComponent(node.childEntity, dev.syndicate.core.component.PartRefComponent.class);
            if (partRef == null || partRef.partTypeId == null) {
                continue;
            }
            var type = assets.partType(partRef.partTypeId);
            if (type == null || type.weapon() == null) {
                continue;
            }
            fastest = Math.max(
                    fastest,
                    type.stats()
                            .resolve(StatBlock.Stat.PROJECTILE_SPEED_MPS, WeaponBlock.DEFAULT_PROJECTILE_SPEED_MPS));
        }
        return fastest;
    }

    // ---- Context -----------------------------------------------------------------------

    /**
     * Counts how many bots are engaging each entity, for the focus-fire term of D11-S5.2.
     *
     * <p>Built from last tick's targets, because this tick's are being decided as it is read. One
     * tick of lag on "is a teammate shooting this" is invisible and the alternative — two passes over
     * every bot — is not.
     */
    private void buildEngagementCensus(World world) {
        engagementCensus.clear();
        int[] entityIds = bots.snapshot();
        for (int i = 0; i < bots.size(); i++) {
            BotControllerComponent bot = world.getComponent(entityIds[i], BotControllerComponent.class);
            if (bot != null && bot.targetEntity != EntityId.NULL) {
                engagementCensus.merge(bot.targetEntity, 1, Integer::sum);
            }
        }
    }

    /**
     * The next arena point of interest for this bot.
     *
     * <p>Spawn points are the only points of interest an arena declares (D08-S4.7), and they are
     * spread across it by construction — so patrolling between them is patrolling the arena. The bot
     * advances its index when it arrives, which is what makes a patrol a circuit rather than a bot
     * parked on one point.
     */
    private Vector3 patrolPoint(ArenaDef arena, BotControllerComponent bot) {
        if (arena == null || arena.spawnPoints().isEmpty()) {
            return null;
        }
        int index = Math.floorMod(bot.patrolIndex, arena.spawnPoints().size());
        Vector3 point = arena.spawnPoints().get(index).position();
        if (point.dst2(bot.perceivedWorld.selfPosition) < PATROL_ARRIVE_M * PATROL_ARRIVE_M) {
            bot.patrolIndex = index + 1;
        }
        return point;
    }

    /** How close a bot gets to a patrol point before moving on to the next. */
    public static final float PATROL_ARRIVE_M = 15.0f;

    private ArenaDef arena(World world) {
        MatchRulesComponent rules = MatchFacts.rules(world);
        return rules == null || rules.arenaId == null ? null : assets.arena(rules.arenaId);
    }

    private void warnAboutMissingNavmeshOnce(World world) {
        if (navmeshWarningLogged) {
            return;
        }
        navmeshWarningLogged = true;
        LOG.error("no navmesh is generated for any arena (D11-S5.4); bots are steering directly "
                + "with obstacle avoidance, as D11-E4 specifies");
    }

    private static boolean friendlyFire(World world) {
        MatchRulesComponent rules = MatchFacts.rules(world);
        return rules == null || rules.friendlyFire;
    }

    private static int teamOf(World world, int vehicleEntity) {
        TeamComponent team = world.getComponent(vehicleEntity, TeamComponent.class);
        return team == null ? TeamComponent.FREE_FOR_ALL : team.teamId;
    }

    /** The vehicle whose damage most recently reached this bot's chassis, or {@code NULL}. */
    private static int lastAttackerOf(World world, int vehicleEntity) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null || chassis.chassisPartEntity == EntityId.NULL) {
            return EntityId.NULL;
        }
        HealthComponent health = world.getComponent(chassis.chassisPartEntity, HealthComponent.class);
        return health == null ? EntityId.NULL : health.lastAttacker;
    }

    /**
     * Whether the bot can still drive (D11-E2).
     *
     * <p>A vehicle with no live driven wheel is immobile, and every driving behaviour has to know
     * that or the unstick manoeuvre becomes a permanent loop of reversing into nothing.
     */
    private static boolean hasDrivableWheels(World world, int vehicleEntity) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null) {
            return false;
        }
        for (int i = 0; i < chassis.wheelCount; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            WheelControllerComponent wheel = wheelEntity == EntityId.NULL
                    ? null
                    : world.getComponent(wheelEntity, WheelControllerComponent.class);
            if (wheel != null && wheel.isDriven && isAlive(world, wheelEntity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAlive(World world, int partEntity) {
        if (partEntity == EntityId.NULL || !world.isAlive(partEntity)) {
            return false;
        }
        HealthComponent health = world.getComponent(partEntity, HealthComponent.class);
        return health == null || health.currentHp > 0f;
    }

    private static WeaponControllerComponent weaponOf(World world, int partEntity) {
        return partEntity == EntityId.NULL ? null : world.getComponent(partEntity, WeaponControllerComponent.class);
    }

    private static void updateStuckCounter(BotControllerComponent bot, float speed, float throttle) {
        if (Math.abs(throttle) > BehaviourTree.STUCK_THROTTLE && speed < BehaviourTree.STUCK_SPEED_MPS) {
            bot.stuckTicks++;
        } else {
            bot.stuckTicks = 0;
        }
    }

    // ---- Small maths -------------------------------------------------------------------

    private static float moveToward(float current, float target, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.signum(delta) * maxStep;
    }

    /** Like {@link #moveToward} but takes the short way round the circle. */
    private static float moveTowardAngle(float current, float target, float maxStep) {
        float delta = normaliseAngle(target - current);
        if (Math.abs(delta) <= maxStep) {
            return normaliseAngle(target);
        }
        return normaliseAngle(current + Math.signum(delta) * maxStep);
    }

    private static float normaliseAngle(float radians) {
        float twoPi = (float) (Math.PI * 2.0);
        float wrapped = (float) Math.IEEEremainder(radians, twoPi);
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
