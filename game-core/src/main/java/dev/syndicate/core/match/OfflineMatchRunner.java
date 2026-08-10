/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.ecs.WorldFactory;
import dev.syndicate.core.physics.ArenaFactory;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.system.CoreSystemProvider;
import dev.syndicate.core.system.SystemSetFactory;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.MatchPhase;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.config.LaunchConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a complete match with no client, no rendering and no human, as fast as the machine allows
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.8).
 *
 * <p>This is the thing that makes G17 checkable rather than aspirational. Every gameplay system must
 * run headless; the way to know they do is to run all of them, for a whole match, in a process with
 * no window — and get a report out that says who won and how the simulation behaved while deciding.
 *
 * <p><b>It reuses the real schedule.</b> The world, the systems, the arena and the physics world are
 * built exactly as {@code ServerRuntime} builds them, from the same {@link SystemSetFactory} and the
 * same {@link CoreSystemProvider}. A simulator with its own reduced system set would smoke-test a
 * simulation nobody ships.
 *
 * <p><b>A hang is a failure, not a timeout.</b> D11-E15: the run stops at
 * {@link #SAFETY_TICKS} past the time limit and the report says so, so a match that cannot terminate
 * comes back as a red test rather than as a report with a plausible-looking truncated duration.
 *
 * <p>Bullet's natives must already be initialised; this class does not call {@code Bullet.init}
 * because a process may run many matches and D02-R3 allows exactly one initialisation.
 */
public final class OfflineMatchRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineMatchRunner.class);

    /** Ticks past the configured time limit before the run is abandoned (D11-S5.8). */
    public static final int SAFETY_TICKS = 3600;

    /** The time limit assumed when a configuration declares none, so an unbounded run still ends. */
    public static final int DEFAULT_TIME_LIMIT_TICKS = 180 * SimulationConstants.TICK_RATE_HZ;

    private final LaunchConfig config;
    private final AssetIndex assets;
    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;

    private final Map<Integer, Telemetry> telemetry = new TreeMap<>();
    private final Vector3 previousPosition = new Vector3();

    private Family players;

    private double maxTickMs;
    private double totalTickMs;
    private long tickedCount;
    private int maxEntities;
    private int maxBodies;

    /**
     * Builds a match world from a launch configuration.
     *
     * <p>{@code autoStart} is forced on: an offline match has no human to be ready, and a lobby that
     * waited for one would run to the safety cap and report a failure that is really a
     * misconfiguration.
     */
    public OfflineMatchRunner(LaunchConfig config, AssetIndex assets) {
        this.config = Objects.requireNonNull(config, "config");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.world = WorldFactory.create(config);
        this.physics = PhysicsWorld.create();
        this.shapes = new ShapeCache();

        MatchRulesComponent rules = world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
        rules.autoStart = true;
        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        if (clock.timeLimitTicks <= 0) {
            clock.timeLimitTicks = DEFAULT_TIME_LIMIT_TICKS;
        }

        SpawnQueue spawnQueue = new SpawnQueue();
        DebrisFactory debris = new DebrisFactory(physics);
        List<EntitySystem> systems = SystemSetFactory.forMode(
                config.mode(), new CoreSystemProvider(physics, shapes, assets, spawnQueue, debris));
        world.registerSystems(systems);

        ArenaDef arena = assets.arena(config.arenaId());
        if (arena == null) {
            LOG.error(
                    "arena {} is not loaded; the match has no ground",
                    config.arenaId().value());
        } else {
            ArenaFactory.load(world, physics, shapes, arena);
        }
    }

    /**
     * Ticks until the match reaches {@code RESULTS} or the safety cap trips.
     *
     * <p>No sleeping, and no wall-clock read inside the simulation (G5): the timing below measures
     * the host, and nothing the simulation computes depends on it.
     */
    public MatchReport run() {
        players = world.family(ComponentQuery.all(PlayerIdentityComponent.class, ControlledVehicleComponent.class));
        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        long maxTicks = (long) clock.timeLimitTicks + SAFETY_TICKS;

        long tick = 0;
        while (state.phase != MatchPhase.RESULTS && tick < maxTicks) {
            long startNanos = System.nanoTime();
            world.tick(tick);
            recordTick(System.nanoTime() - startNanos);
            sampleTelemetry(tick);
            tick++;
        }
        boolean cappedOut = state.phase != MatchPhase.RESULTS;
        if (cappedOut) {
            LOG.error(
                    "match failed to terminate: {} ticks elapsed and the phase is still {} (D11-E15)",
                    tick,
                    state.phase);
        }
        return report(state, tick, cappedOut);
    }

    // ---- Telemetry --------------------------------------------------------------------

    /** Per-player counters the world does not keep, accumulated as the match runs. */
    private static final class Telemetry {
        final Vector3 lastPosition = new Vector3();
        boolean hasLastPosition;
        float distanceM;
        long ticksAlive;
    }

    private void recordTick(long durationNanos) {
        double ms = durationNanos / 1_000_000.0;
        maxTickMs = Math.max(maxTickMs, ms);
        totalTickMs += ms;
        tickedCount++;
        maxEntities = Math.max(maxEntities, world.entityCount());
        maxBodies = Math.max(maxBodies, physics.bodyCount());
    }

    /**
     * Accumulates distance travelled and time alive.
     *
     * <p>Integrated per tick rather than measured start-to-end, because a vehicle that is destroyed
     * and respawns elsewhere would otherwise be credited with the teleport. A respawn breaks the
     * chain by clearing {@code hasLastPosition}.
     */
    private void sampleTelemetry(long tick) {
        int[] entityIds = players.snapshot();
        for (int i = 0; i < players.size(); i++) {
            int playerEntity = entityIds[i];
            PlayerIdentityComponent identity = world.getComponent(playerEntity, PlayerIdentityComponent.class);
            ControlledVehicleComponent controlled = world.getComponent(playerEntity, ControlledVehicleComponent.class);
            if (identity == null || controlled == null) {
                continue;
            }
            Telemetry entry = telemetry.computeIfAbsent(identity.playerId, ignored -> new Telemetry());
            TransformComponent transform =
                    controlled.vehicleEntity == EntityId.NULL || !world.isAlive(controlled.vehicleEntity)
                            ? null
                            : world.getComponent(controlled.vehicleEntity, TransformComponent.class);
            if (transform == null) {
                entry.hasLastPosition = false;
                continue;
            }
            entry.ticksAlive++;
            previousPosition.set(transform.position);
            if (entry.hasLastPosition) {
                entry.distanceM += entry.lastPosition.dst(previousPosition);
            }
            entry.lastPosition.set(previousPosition);
            entry.hasLastPosition = true;
        }
    }

    // ---- Report -----------------------------------------------------------------------

    private MatchReport report(MatchStateComponent state, long durationTicks, boolean cappedOut) {
        MatchRulesComponent rules = world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
        List<MatchReport.PlayerRow> rows = new ArrayList<>();
        int[] entityIds = players.snapshot();
        for (int i = 0; i < players.size(); i++) {
            int playerEntity = entityIds[i];
            PlayerIdentityComponent identity = world.getComponent(playerEntity, PlayerIdentityComponent.class);
            ScoreComponent score = world.getComponent(playerEntity, ScoreComponent.class);
            if (identity == null) {
                continue;
            }
            Telemetry entry = telemetry.getOrDefault(identity.playerId, new Telemetry());
            rows.add(new MatchReport.PlayerRow(
                    identity.playerId,
                    identity.displayName,
                    identity.isBot,
                    identity.selectedAssemblyId == null ? "" : identity.selectedAssemblyId.value(),
                    score == null ? 0 : score.kills,
                    score == null ? 0 : score.deaths,
                    score == null ? 0 : score.assists,
                    score == null ? 0 : score.objectiveScore,
                    score == null ? 0f : score.damageDealt,
                    entry.distanceM,
                    entry.ticksAlive));
        }
        rows.sort(Comparator.comparingInt(MatchReport.PlayerRow::playerId));

        return new MatchReport(
                config.matchSeed(),
                rules.mode,
                rules.botDifficulty,
                rules.botCount,
                durationTicks,
                cappedOut,
                state.outcome,
                playerIdOf(state.winnerPlayerEntity),
                state.winnerTeamId,
                rows,
                new MatchReport.PhysicsSummary(maxEntities, maxBodies, physics.nanRemovalCount()),
                new MatchReport.TimingSummary(maxTickMs, tickedCount == 0 ? 0.0 : totalTickMs / tickedCount));
    }

    /** The winner's stable player id, which is what a report can be compared on. */
    private int playerIdOf(int playerEntity) {
        if (playerEntity == EntityId.NULL || !world.isAlive(playerEntity)) {
            return -1;
        }
        PlayerIdentityComponent identity = world.getComponent(playerEntity, PlayerIdentityComponent.class);
        return identity == null ? -1 : identity.playerId;
    }

    /** The world, for a test that wants to inspect it after the run. */
    public World world() {
        return world;
    }

    /** True when the match ended in a decision rather than by running out of clock and cap. */
    public boolean isDecided() {
        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        return state != null && state.outcome != MatchOutcome.UNDECIDED;
    }

    /** The D03-S5.6 teardown order: entities, then the world, then the shapes they referenced. */
    @Override
    public void close() {
        world.dispose();
        physics.close();
        shapes.close();
    }
}
