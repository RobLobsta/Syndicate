/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.match.BotFactory;
import dev.syndicate.core.match.MatchPhaseChangedEvent;
import dev.syndicate.core.match.SpawnPointSelector;
import dev.syndicate.core.match.WinCondition;
import dev.syndicate.core.match.WinConditionResult;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.MatchPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 4: the match state machine
 * (docs/04_entity_component_model.md#D04-S4.4, docs/11_ai_bots_and_match_simulation.md#D11-S5.7,
 * docs/01_product_game_design.md#D01-S5.6).
 *
 * <p>Authority only (G15). A client is told which phase it is in; it never decides.
 *
 * <p>This is the system that makes the simulation a <em>match</em> rather than a sandbox. Before it
 * existed the world contained everything a fight is made of and nothing that started one.
 *
 * <p><b>Input gating is done by erasing intent, not by asking six systems to check a flag.</b>
 * D01-R21 freezes vehicles during {@code COUNTDOWN} and D01-R23 freezes them again during
 * {@code ENDING}. This slot runs at 4 — after {@code InputReceive} (2) and {@code BotDecision} (3)
 * have written their intent, and before {@code VehicleControl} (7) and {@code Weapon} (8) read it —
 * so zeroing every {@code PlayerInputComponent} here <em>is</em> "input is ignored", with no second
 * copy of the rule to keep in step. Damage cannot be handled the same way, because it originates at
 * slots 11 and 12, seven slots later; that gate is a flag {@code DamageSystem} reads.
 *
 * <p><b>The clock advances here, once.</b> {@code MatchClockComponent.tick} counts ticks
 * <em>in {@code ACTIVE}</em>, not ticks since the process started, so a long lobby cannot consume a
 * match's time limit (D11-E11).
 */
public final class MatchFlowSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(MatchFlowSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 4;

    /**
     * {@code LOBBY_MAX_WAIT} — thirty seconds before a lobby with at least one participant starts
     * regardless (D11-S5.7).
     */
    public static final int LOBBY_MAX_WAIT_TICKS = 1800;

    /** How long a queued spawn is left alone before it is retried. One second. */
    public static final int SPAWN_RETRY_TICKS = 60;

    private final AssetIndex assets;
    private final SpawnQueue spawnQueue;
    private final SpawnPointSelector spawnPoints = new SpawnPointSelector();
    private final Matrix4 spawnTransform = new Matrix4();

    private Family players;
    private Family drivers;

    /** Reused across ticks: the sorted player list is rebuilt every tick and never escapes. */
    private final List<Integer> playerScratch = new ArrayList<>();

    public MatchFlowSystem(AssetIndex assets, SpawnQueue spawnQueue) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.spawnQueue = Objects.requireNonNull(spawnQueue, "spawnQueue");
    }

    @Override
    public Phase phase() {
        return Phase.PRE_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        players = world.family(ComponentQuery.all(PlayerIdentityComponent.class, ControlledVehicleComponent.class));
        drivers = world.family(ComponentQuery.all(PlayerInputComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        MatchRulesComponent rules = world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        if (state == null || rules == null || clock == null) {
            // No match singleton: this world is a fixture, not a match. Doing nothing is what leaves
            // every physics and damage test running exactly as it did before this system existed.
            return;
        }

        // A bot's vehicle may have been created by slot 5 on the previous tick, or replaced by a
        // respawn; either way its controller is reconciled before slot 3 reads it next tick.
        BotFactory.attachControllers(world);
        collectPlayers(world);

        switch (state.phase) {
            case LOBBY -> lobby(world, state, rules, tick);
            case COUNTDOWN -> countdown(state, rules, tick);
            case ACTIVE -> active(world, state, rules, clock, tick);
            case ENDING -> ending(state, rules, tick);
            case RESULTS -> results(world, state, rules, tick);
        }
        applyGates(world, state);
    }

    // ---- LOBBY -----------------------------------------------------------------------

    /**
     * Waits for a reason to start, then fills the lobby with bots and puts everyone on the grid.
     *
     * <p>Three reasons, all from D11-S5.7: the launch asked for an automatic start, every human
     * present is ready, or a human has been waiting {@link #LOBBY_MAX_WAIT_TICKS}. A lobby with
     * neither humans nor {@code autoStart} waits forever, which is correct for a dedicated server
     * nobody has joined and is why the offline simulator sets {@code autoStart} rather than relying
     * on a timeout.
     */
    private void lobby(World world, MatchStateComponent state, MatchRulesComponent rules, long tick) {
        long waited = tick - state.phaseEnteredTick;
        boolean humansWaiting = countHumans(world) >= 1;
        if (!rules.autoStart && !(humansWaiting && waited > LOBBY_MAX_WAIT_TICKS)) {
            return;
        }

        int nextPlayerId = playerScratch.size();
        int wanted = Math.max(0, rules.botCount);
        BotFactory.fill(world, assets, wanted, rules.botDifficulty, nextPlayerId);
        collectPlayers(world);
        spawnEveryone(world, rules, tick);
        transitionTo(world, state, MatchPhase.COUNTDOWN, tick);
    }

    /** Queues a spawn for every player who has no vehicle. Slot 5 creates them later this tick. */
    private void spawnEveryone(World world, MatchRulesComponent rules, long tick) {
        for (int player : playerScratch) {
            requestSpawn(world, rules, player, tick);
        }
    }

    // ---- COUNTDOWN -------------------------------------------------------------------

    /**
     * Vehicles exist and are simulated; nothing is driven and nothing takes damage (D01-R21/R22).
     *
     * <p>The phase is not decoration. A vehicle spawns with its wheels at their suspension rest
     * length and falls the last few centimetres onto them; starting a match during that settle would
     * hand whoever spawned on the lowest ground a measurable head start.
     */
    private void countdown(MatchStateComponent state, MatchRulesComponent rules, long tick) {
        if (tick - state.phaseEnteredTick >= Math.max(0, rules.warmupTicks)) {
            transitionToInternal(state, MatchPhase.ACTIVE, tick);
        }
    }

    // ---- ACTIVE ----------------------------------------------------------------------

    private void active(
            World world, MatchStateComponent state, MatchRulesComponent rules, MatchClockComponent clock, long tick) {

        clock.tick++;
        handleRespawns(world, rules, tick);

        WinConditionResult result = WinCondition.evaluate(world, playerScratch);
        switch (result.kind()) {
            case CONTINUE -> {
                // Nothing to do; the common case, and the one that must allocate nothing.
            }
            case ENTER_SUDDEN_DEATH -> enterSuddenDeath(state, rules, clock, tick);
            case WIN -> {
                state.outcome =
                        result.winnerPlayerEntity() != EntityId.NULL ? MatchOutcome.PLAYER_WIN : MatchOutcome.TEAM_WIN;
                state.winnerPlayerEntity = result.winnerPlayerEntity();
                state.winnerTeamId = result.winnerTeamId();
                transitionTo(world, state, MatchPhase.ENDING, tick);
            }
            case DRAW -> {
                state.outcome = MatchOutcome.DRAW;
                transitionTo(world, state, MatchPhase.ENDING, tick);
            }
        }
    }

    /**
     * Extends the clock once, and stops the respawns (D01-E2, D11-E13).
     *
     * <p>{@code respawnDelayTicks} is not set to infinity as D11-S5.7 writes it — {@code noRespawn}
     * says the same thing without a sentinel that arithmetic elsewhere could overflow.
     */
    private void enterSuddenDeath(
            MatchStateComponent state, MatchRulesComponent rules, MatchClockComponent clock, long tick) {
        state.suddenDeath = true;
        rules.noRespawn = true;
        clock.timeLimitTicks += rules.suddenDeathTicks;
        LOG.info("sudden death at tick {}: limit extended to {} ticks", tick, clock.timeLimitTicks);
    }

    /**
     * Notices deaths and puts players back when their delay has run (D11-S5.7 {@code handleRespawns}).
     *
     * <p>A player's vehicle is not removed by anything that knows about players: {@code DetachSystem}
     * wrecks it and slot 27 destroys it. So the death is <em>detected</em> here, by the entity no
     * longer being alive, and the tick it is detected on becomes the death tick. That is at most one
     * tick later than the destruction itself, which is below the resolution of any respawn delay a
     * human would configure.
     */
    private void handleRespawns(World world, MatchRulesComponent rules, long tick) {
        for (int player : playerScratch) {
            ControlledVehicleComponent controlled = world.getComponent(player, ControlledVehicleComponent.class);
            if (controlled == null) {
                continue;
            }
            if (controlled.vehicleEntity != EntityId.NULL && !world.isAlive(controlled.vehicleEntity)) {
                controlled.vehicleEntity = EntityId.NULL;
                controlled.deathTick = tick;
            }
            if (controlled.vehicleEntity != EntityId.NULL || rules.noRespawn) {
                continue;
            }
            if (isSpawnPending(controlled, tick)) {
                continue;
            }
            boolean neverDied = controlled.deathTick == ControlledVehicleComponent.NEVER_DIED;
            if (neverDied || tick - controlled.deathTick >= rules.respawnDelayTicks) {
                requestSpawn(world, rules, player, tick);
            }
        }
    }

    // ---- ENDING and RESULTS ----------------------------------------------------------

    /** Input is ignored; physics and destruction keep running so the last wreck plays out (D01-R23). */
    private void ending(MatchStateComponent state, MatchRulesComponent rules, long tick) {
        if (tick - state.phaseEnteredTick >= Math.max(0, rules.endingTicks)) {
            transitionToInternal(state, MatchPhase.RESULTS, tick);
        }
    }

    /**
     * The scoreboard, then back to the lobby.
     *
     * <p>D11-S5.7 calls {@code resetWorld} here. This implementation does not: the offline simulator
     * (D11-S5.8) stops <em>at</em> {@code RESULTS} and reads the scoreboard, and a dedicated server
     * that wiped the world at this point would delete the numbers its own match report is made of.
     * What the return to {@code LOBBY} does do is clear the decision, so the next match starts
     * undecided.
     */
    private void results(World world, MatchStateComponent state, MatchRulesComponent rules, long tick) {
        if (tick - state.phaseEnteredTick < Math.max(0, rules.resultsTicks)) {
            return;
        }
        state.outcome = MatchOutcome.UNDECIDED;
        state.winnerPlayerEntity = EntityId.NULL;
        state.winnerTeamId = TeamComponent.FREE_FOR_ALL;
        state.suddenDeath = false;
        transitionTo(world, state, MatchPhase.LOBBY, tick);
    }

    // ---- Phase mechanics -------------------------------------------------------------

    private void transitionTo(World world, MatchStateComponent state, MatchPhase phase, long tick) {
        MatchPhase from = state.phase;
        transitionToInternal(state, phase, tick);
        world.events().emit(new MatchPhaseChangedEvent(from, phase, tick));
    }

    private void transitionToInternal(MatchStateComponent state, MatchPhase phase, long tick) {
        LOG.info("match phase {} -> {} at tick {}", state.phase, phase, tick);
        state.phase = phase;
        state.phaseEnteredTick = tick;
    }

    /**
     * Applies the phase's input and damage gates (D01-R21, D01-R22, D01-R23).
     *
     * <p>Zeroing rather than flagging, for input: see the class note. The zero is written every tick
     * the gate is closed, not once on entry, because slot 3 writes fresh intent every tick and would
     * otherwise overwrite a one-shot clear.
     */
    private void applyGates(World world, MatchStateComponent state) {
        state.inputEnabled = state.phase == MatchPhase.ACTIVE;
        state.damageEnabled = state.phase == MatchPhase.ACTIVE || state.phase == MatchPhase.ENDING;
        if (state.inputEnabled) {
            return;
        }
        int[] entityIds = drivers.snapshot();
        for (int i = 0; i < drivers.size(); i++) {
            PlayerInputComponent input = world.getComponent(entityIds[i], PlayerInputComponent.class);
            if (input != null) {
                input.throttle = 0f;
                input.steer = 0f;
                input.brake = 0f;
                input.fireMask = 0;
            }
        }
    }

    // ---- Players ---------------------------------------------------------------------

    /**
     * Rebuilds the player list in ascending {@code playerId}.
     *
     * <p>Family order is ascending entity id, and player ids are handed out in creation order, so the
     * two orders agree — but only until an entity index is recycled. Sorting on the id the rules
     * actually name costs a sort of a list of at most a dozen and removes that dependency (G3).
     */
    private void collectPlayers(World world) {
        playerScratch.clear();
        int[] entityIds = players.snapshot();
        for (int i = 0; i < players.size(); i++) {
            playerScratch.add(entityIds[i]);
        }
        playerScratch.sort((a, b) -> {
            PlayerIdentityComponent left = world.getComponent(a, PlayerIdentityComponent.class);
            PlayerIdentityComponent right = world.getComponent(b, PlayerIdentityComponent.class);
            int leftId = left == null ? Integer.MAX_VALUE : left.playerId;
            int rightId = right == null ? Integer.MAX_VALUE : right.playerId;
            return leftId != rightId ? Integer.compare(leftId, rightId) : Integer.compare(a, b);
        });
    }

    private int countHumans(World world) {
        int humans = 0;
        for (int player : playerScratch) {
            PlayerIdentityComponent identity = world.getComponent(player, PlayerIdentityComponent.class);
            if (identity != null && !identity.isBot) {
                humans++;
            }
        }
        return humans;
    }

    /** Queues one player's vehicle at a spawn point chosen for their team. */
    private void requestSpawn(World world, MatchRulesComponent rules, int playerEntity, long tick) {
        PlayerIdentityComponent identity = world.getComponent(playerEntity, PlayerIdentityComponent.class);
        if (identity == null || identity.selectedAssemblyId == null) {
            return;
        }
        TeamComponent team = world.getComponent(playerEntity, TeamComponent.class);
        int teamId = team == null ? TeamComponent.FREE_FOR_ALL : team.teamId;

        ArenaDef arena = rules.arenaId == null ? null : assets.arena(rules.arenaId);
        Matrix4 where = spawnPoints.choose(world, arena, teamId, spawnTransform);
        if (where == null) {
            LOG.error(
                    "arena {} declares no spawn point for team {}; player {} cannot enter the match",
                    rules.arenaId == null ? "(none)" : rules.arenaId.value(),
                    teamId,
                    identity.displayName);
            return;
        }
        spawnQueue.request(identity.selectedAssemblyId, where, playerEntity, teamId);
        ControlledVehicleComponent controlled = world.getComponent(playerEntity, ControlledVehicleComponent.class);
        if (controlled != null) {
            controlled.spawnRequestedTick = tick;
        }
    }

    /**
     * Whether a spawn queued for this player is still outstanding.
     *
     * <p>Slot 5 drains the queue one slot after this one, so in the normal case the vehicle exists
     * before the next tick reads this. The window matters only when the spawn was <em>refused</em>:
     * {@link #SPAWN_RETRY_TICKS} then decides how often the error is retried and logged, rather than
     * once per tick for the rest of the match.
     */
    private static boolean isSpawnPending(ControlledVehicleComponent controlled, long tick) {
        return controlled.spawnRequestedTick != ControlledVehicleComponent.NEVER_DIED
                && tick - controlled.spawnRequestedTick < SPAWN_RETRY_TICKS;
    }
}
