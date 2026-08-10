/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.core.component.ComponentCatalogue;
import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.GameMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D01-S5.5's win conditions (docs/01_product_game_design.md#D01-S5.5). */
@Tag("unit")
class WinConditionTest {

    private World world;
    private MatchRulesComponent rules;
    private MatchClockComponent clock;
    private MatchStateComponent state;
    private final List<Integer> players = new ArrayList<>();

    @BeforeEach
    void setUp() {
        world = new World(1234L, true);
        ComponentCatalogue.registerAll(world.componentTypes());
        Entity match = world.createEntityWithReservedIndex(EntityId.MATCH);
        state = new MatchStateComponent();
        rules = new MatchRulesComponent();
        clock = new MatchClockComponent();
        world.addComponent(match.id(), state);
        world.addComponent(match.id(), rules);
        world.addComponent(match.id(), clock);
        players.clear();
    }

    private int addPlayer(int playerId, int teamId, int kills) {
        Entity entity = world.createEntity();
        PlayerIdentityComponent identity = new PlayerIdentityComponent();
        identity.playerId = playerId;
        identity.displayName = "P" + playerId;
        world.addComponent(entity.id(), identity);
        TeamComponent team = new TeamComponent();
        team.teamId = teamId;
        world.addComponent(entity.id(), team);
        ScoreComponent score = new ScoreComponent();
        score.kills = kills;
        world.addComponent(entity.id(), score);
        world.addComponent(entity.id(), new ControlledVehicleComponent());
        players.add(entity.id());
        return entity.id();
    }

    /** T-D11-14: reaching the score limit wins outright, before the clock has anything to say. */
    @Test
    void deathmatch_scoreLimitWins() {
        rules.mode = GameMode.DEATHMATCH;
        rules.scoreLimit = 3;
        int leader = addPlayer(0, TeamComponent.FREE_FOR_ALL, 3);
        addPlayer(1, TeamComponent.FREE_FOR_ALL, 1);

        WinConditionResult result = WinCondition.evaluate(world, players);

        assertThat(result.kind()).isEqualTo(WinConditionResult.Kind.WIN);
        assertThat(result.winnerPlayerEntity()).isEqualTo(leader);
    }

    /** Below the limit and inside the clock, nothing has been decided. */
    @Test
    void deathmatch_continuesWhileNobodyHasWon() {
        rules.mode = GameMode.DEATHMATCH;
        rules.scoreLimit = 5;
        clock.timeLimitTicks = 600;
        clock.tick = 100;
        addPlayer(0, TeamComponent.FREE_FOR_ALL, 2);
        addPlayer(1, TeamComponent.FREE_FOR_ALL, 1);

        assertThat(WinCondition.evaluate(world, players).kind()).isEqualTo(WinConditionResult.Kind.CONTINUE);
    }

    /** At the limit a single leader wins; the score limit never had to be reached. */
    @Test
    void deathmatch_timeLimitAwardsTheUniqueLeader() {
        rules.mode = GameMode.DEATHMATCH;
        clock.timeLimitTicks = 600;
        clock.tick = 600;
        addPlayer(0, TeamComponent.FREE_FOR_ALL, 1);
        int leader = addPlayer(1, TeamComponent.FREE_FOR_ALL, 4);

        WinConditionResult result = WinCondition.evaluate(world, players);

        assertThat(result.kind()).isEqualTo(WinConditionResult.Kind.WIN);
        assertThat(result.winnerPlayerEntity()).isEqualTo(leader);
    }

    /** T-D11-16: a tie extends once into sudden death, and a second tie is a draw (D01-E2). */
    @Test
    void deathmatch_tieExtendsOnceThenDraws() {
        rules.mode = GameMode.DEATHMATCH;
        rules.suddenDeathTicks = 300;
        clock.timeLimitTicks = 600;
        clock.tick = 600;
        addPlayer(0, TeamComponent.FREE_FOR_ALL, 2);
        addPlayer(1, TeamComponent.FREE_FOR_ALL, 2);

        assertThat(WinCondition.evaluate(world, players).kind()).isEqualTo(WinConditionResult.Kind.ENTER_SUDDEN_DEATH);

        // The flag is what stops it extending a second time — the clock alone would say the same
        // thing forever.
        state.suddenDeath = true;
        assertThat(WinCondition.evaluate(world, players).kind()).isEqualTo(WinConditionResult.Kind.DRAW);
    }

    /** With no sudden death configured, a tie at the limit is a draw immediately. */
    @Test
    void deathmatch_tieWithoutSuddenDeathIsADraw() {
        rules.mode = GameMode.DEATHMATCH;
        clock.timeLimitTicks = 600;
        clock.tick = 601;
        addPlayer(0, TeamComponent.FREE_FOR_ALL, 0);
        addPlayer(1, TeamComponent.FREE_FOR_ALL, 0);

        assertThat(WinCondition.evaluate(world, players).kind()).isEqualTo(WinConditionResult.Kind.DRAW);
    }

    /** Team scores are summed across the team, not read off its best player. */
    @Test
    void teamDeathmatch_sumsKillsAcrossTheTeam() {
        rules.mode = GameMode.TEAM_DEATHMATCH;
        rules.scoreLimit = 5;
        addPlayer(0, 0, 3);
        addPlayer(1, 0, 2);
        addPlayer(2, 1, 4);

        WinConditionResult result = WinCondition.evaluate(world, players);

        assertThat(result.kind()).isEqualTo(WinConditionResult.Kind.WIN);
        assertThat(result.winnerTeamId()).isZero();
    }

    /** A sandbox mode never ends on its own (D01-S5.5 {@code NEVER}). */
    @Test
    void testRange_neverEnds() {
        rules.mode = GameMode.TEST_RANGE;
        clock.timeLimitTicks = 10;
        clock.tick = 10_000;
        addPlayer(0, TeamComponent.FREE_FOR_ALL, 99);

        assertThat(WinCondition.evaluate(world, players).kind()).isEqualTo(WinConditionResult.Kind.CONTINUE);
    }

    /** A world with no match singleton has no rules to evaluate and must not throw. */
    @Test
    void noMatchSingleton_continues() {
        World bare = new World(1L, true);
        ComponentCatalogue.registerAll(bare.componentTypes());
        assertThat(WinCondition.evaluate(bare, List.of()).kind()).isEqualTo(WinConditionResult.Kind.CONTINUE);
    }
}
