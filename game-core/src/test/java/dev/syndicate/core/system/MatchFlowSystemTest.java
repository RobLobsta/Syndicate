/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.component.ComponentCatalogue;
import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.MatchPhase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 4 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.7).
 *
 * <p>No physics: the state machine's job is phases, gates and spawn <em>requests</em>, and slot 5 is
 * what turns a request into a vehicle. Driving the machine without a Bullet world is exactly the
 * separation that makes T-D11-14's phase-by-phase assertions cheap.
 */
@Tag("unit")
class MatchFlowSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_test_01");
    private static final AssetId ARENA = AssetId.of("arena_test_01");

    private World world;
    private MatchFlowSystem system;
    private SpawnQueue spawnQueue;
    private MatchStateComponent state;
    private MatchRulesComponent rules;
    private MatchClockComponent clock;

    @BeforeEach
    void setUp() {
        world = new World(4242L, true);
        ComponentCatalogue.registerAll(world.componentTypes());

        Entity match = world.createEntityWithReservedIndex(EntityId.MATCH);
        state = new MatchStateComponent();
        rules = new MatchRulesComponent();
        clock = new MatchClockComponent();
        rules.mode = GameMode.DEATHMATCH;
        rules.arenaId = ARENA;
        rules.warmupTicks = 10;
        rules.endingTicks = 5;
        rules.resultsTicks = 5;
        rules.autoStart = true;
        rules.botCount = 2;
        world.addComponent(match.id(), state);
        world.addComponent(match.id(), rules);
        world.addComponent(match.id(), clock);

        spawnQueue = new SpawnQueue();
        system = new MatchFlowSystem(assets(), spawnQueue);
        world.registerSystems(List.of(system));
    }

    private static InMemoryAssetIndex assets() {
        InMemoryAssetIndex index = new InMemoryAssetIndex();
        index.put(new AssemblyDef(ASSEMBLY, "medium", AssetId.of("chassis_test_01"), List.of(), null));
        index.put(new ArenaDef(
                ARENA,
                "Test Arena",
                new Vector3(-100f, -10f, -100f),
                new Vector3(100f, 50f, 100f),
                -20f,
                0f,
                List.of(
                        new ArenaDef.SpawnPoint("sp_1", -1, new Vector3(-40f, 1f, 0f), 90f, 8f),
                        new ArenaDef.SpawnPoint("sp_2", -1, new Vector3(40f, 1f, 0f), 270f, 8f),
                        new ArenaDef.SpawnPoint("sp_3", -1, new Vector3(0f, 1f, 40f), 180f, 8f)),
                Set.of(),
                null,
                null));
        return index;
    }

    private void tick(long from, long count) {
        for (long t = from; t < from + count; t++) {
            world.tick(t);
        }
    }

    @Test
    void system_occupiesSlot4OfPreSim() {
        assertThat(system.order()).isEqualTo(MatchFlowSystem.ORDER).isEqualTo(4);
        assertThat(system.phase()).isEqualTo(Phase.PRE_SIM);
    }

    /** T-D11-14: the phase sequence, at the tick counts the rules declare. */
    @Test
    void phases_runInOrderAtTheDeclaredTickCounts() {
        world.tick(0);
        assertThat(state.phase).isEqualTo(MatchPhase.COUNTDOWN);

        tick(1, rules.warmupTicks - 1);
        assertThat(state.phase).as("still counting down").isEqualTo(MatchPhase.COUNTDOWN);

        world.tick(rules.warmupTicks);
        assertThat(state.phase).isEqualTo(MatchPhase.ACTIVE);
    }

    /** LOBBY fills the lobby with bots and queues one spawn each (D11-S5.6). */
    @Test
    void lobby_createsBotsAndQueuesTheirSpawns() {
        world.tick(0);

        Family players = world.family(ComponentQuery.all(PlayerIdentityComponent.class));
        assertThat(players.size()).isEqualTo(rules.botCount);
        assertThat(spawnQueue.size()).isEqualTo(rules.botCount);
    }

    /** Two spawns queued on the same tick take different points, not the same one twice. */
    @Test
    void lobby_spreadsSpawnsAcrossPoints() {
        world.tick(0);

        List<dev.syndicate.core.vehicle.SpawnRequest> requests = spawnQueue.drain();
        Vector3 first = new Vector3();
        Vector3 second = new Vector3();
        requests.get(0).spawnTransform().getTranslation(first);
        requests.get(1).spawnTransform().getTranslation(second);

        assertThat(first.dst(second)).isGreaterThanOrEqualTo(ArenaDef.MIN_SPAWN_SEPARATION_M);
    }

    /** D01-R21/R22: nothing is driven and nothing is damageable during COUNTDOWN. */
    @Test
    void countdown_closesBothGatesAndErasesIntent() {
        int driver = driverWithFullThrottle();

        world.tick(0);

        assertThat(state.phase).isEqualTo(MatchPhase.COUNTDOWN);
        assertThat(state.inputEnabled).isFalse();
        assertThat(state.damageEnabled).isFalse();
        PlayerInputComponent input = world.getComponent(driver, PlayerInputComponent.class);
        assertThat(input.throttle).isZero();
        assertThat(input.fireMask).isZero();
    }

    /** ACTIVE opens both gates and stops erasing intent. */
    @Test
    void active_opensBothGates() {
        int driver = driverWithFullThrottle();
        tick(0, rules.warmupTicks + 1);
        world.getComponent(driver, PlayerInputComponent.class).throttle = 1f;

        world.tick(rules.warmupTicks + 2);

        assertThat(state.phase).isEqualTo(MatchPhase.ACTIVE);
        assertThat(state.inputEnabled).isTrue();
        assertThat(state.damageEnabled).isTrue();
        assertThat(world.getComponent(driver, PlayerInputComponent.class).throttle)
                .isEqualTo(1f);
    }

    /** D11-E11: the clock counts ACTIVE ticks, so a long lobby cannot consume the time limit. */
    @Test
    void clock_onlyAdvancesDuringActive() {
        rules.autoStart = false;
        tick(0, 50);
        assertThat(clock.tick).isZero();

        rules.autoStart = true;
        tick(50, rules.warmupTicks + 10);
        assertThat(clock.tick).isPositive().isLessThanOrEqualTo(10);
    }

    /** D01-R23: ENDING keeps damage running so the last wreck plays out, but ignores input. */
    @Test
    void ending_ignoresInputAndKeepsDamageOn() {
        clock.timeLimitTicks = 1;
        tick(0, rules.warmupTicks + 3);

        assertThat(state.phase).isEqualTo(MatchPhase.ENDING);
        assertThat(state.inputEnabled).isFalse();
        assertThat(state.damageEnabled).isTrue();
        assertThat(state.outcome).isNotEqualTo(MatchOutcome.UNDECIDED);
    }

    /**
     * T-D11-14: the full circuit, LOBBY through to LOBBY.
     *
     * <p>Asserted on the emitted transitions rather than on the phase afterwards, because with
     * {@code autoStart} on the machine leaves the lobby again on the very tick it re-enters it —
     * which is the correct behaviour for a server that is meant to keep playing, and would make an
     * assertion on the final phase read {@code COUNTDOWN} and look like a bug.
     */
    @Test
    void phases_completeTheCircuitBackToLobby() {
        List<MatchPhase> transitions = new java.util.ArrayList<>();
        world.events()
                .subscribe(dev.syndicate.core.match.MatchPhaseChangedEvent.class, event -> transitions.add(event.to()));
        clock.timeLimitTicks = 1;

        tick(0, rules.warmupTicks + rules.endingTicks + rules.resultsTicks + 8);

        assertThat(transitions).containsSubsequence(MatchPhase.COUNTDOWN, MatchPhase.ENDING, MatchPhase.LOBBY);
        assertThat(state.suddenDeath).isFalse();
    }

    /** A world with no match singleton is a fixture, and slot 4 leaves it entirely alone. */
    @Test
    void noMatchSingleton_doesNothing() {
        World bare = new World(1L, true);
        ComponentCatalogue.registerAll(bare.componentTypes());
        MatchFlowSystem bareSystem = new MatchFlowSystem(assets(), new SpawnQueue());
        bare.registerSystems(List.of(bareSystem));

        Entity driver = bare.createEntity();
        PlayerInputComponent input = new PlayerInputComponent();
        input.throttle = 1f;
        bare.addComponent(driver.id(), input);

        bare.tick(0);

        assertThat(input.throttle).as("a fixture's input is not gated").isEqualTo(1f);
    }

    private int driverWithFullThrottle() {
        Entity entity = world.createEntity();
        PlayerIdentityComponent identity = new PlayerIdentityComponent();
        identity.playerId = 100;
        identity.displayName = "Human";
        identity.selectedAssemblyId = ASSEMBLY;
        world.addComponent(entity.id(), identity);
        world.addComponent(entity.id(), new ControlledVehicleComponent());
        world.addComponent(entity.id(), new TeamComponent());
        world.addComponent(entity.id(), new ScoreComponent());
        PlayerInputComponent input = new PlayerInputComponent();
        input.throttle = 1f;
        input.fireMask = 0b11;
        world.addComponent(entity.id(), input);
        return entity.id();
    }
}
