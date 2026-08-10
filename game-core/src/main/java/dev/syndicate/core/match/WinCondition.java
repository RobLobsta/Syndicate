/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.VehicleIntegrity;
import dev.syndicate.model.DamageState;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * D01-S5.5's {@code evaluateWinCondition}, as an operation over components
 * (docs/01_product_game_design.md#D01-S5.5).
 *
 * <p>Separate from {@code MatchFlowSystem} for the reason {@code DamageApplication} is separate from
 * {@code DamageSystem} (DEC-038): the state machine is a schedule slot with one job — move between
 * phases — and the rules for <em>when</em> a match is over are per-mode content logic that wants to
 * be readable and testable on its own, without a world that ticks.
 *
 * <p><b>Every tie-break is explicit.</b> D01-S5.5 says "uniqueMax" and "most intact"; the failure
 * mode a vehicular combat game actually hits is two players on identical scores at the time limit,
 * and a rule that resolved that by iteration order would decide the match differently on two peers
 * (G3). So: leaders are gathered, a single leader wins, and several leaders is a tie — never
 * "whichever came first".
 */
public final class WinCondition {

    private WinCondition() {
        throw new AssertionError("no instances");
    }

    /**
     * Evaluates the configured mode's win condition for this tick.
     *
     * @param world the match world; must carry the match singleton
     * @param players every player entity, in ascending {@code playerId} (G3)
     * @return what the match should do, never null
     */
    public static WinConditionResult evaluate(World world, List<Integer> players) {
        MatchRulesComponent rules = world.getComponent(EntityId.MATCH, MatchRulesComponent.class);
        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        if (rules == null || clock == null || state == null) {
            return WinConditionResult.CONTINUE;
        }
        return switch (rules.mode) {
            case DEATHMATCH -> deathmatch(world, players, rules, clock, state);
            case TEAM_DEATHMATCH, PAYLOAD -> teamDeathmatch(world, players, rules, clock, state);
            case LAST_MACHINE -> lastMachine(world, players, clock);
                // D01-S5.5: TIME_TRIAL and TEST_RANGE return NEVER. They end when an operator ends
                // them, which is why a sandbox mode is the one thing that cannot time out.
            case TIME_TRIAL, TEST_RANGE -> WinConditionResult.CONTINUE;
        };
    }

    // ---- DEATHMATCH ------------------------------------------------------------------

    private static WinConditionResult deathmatch(
            World world,
            List<Integer> players,
            MatchRulesComponent rules,
            MatchClockComponent clock,
            MatchStateComponent state) {

        List<Integer> leaders = leadersByKills(world, players);
        if (leaders.size() == 1 && rules.scoreLimit > 0) {
            ScoreComponent score = world.getComponent(leaders.get(0), ScoreComponent.class);
            if (score != null && score.kills >= rules.scoreLimit) {
                return WinConditionResult.playerWin(leaders.get(0));
            }
        }
        if (!timeIsUp(clock)) {
            return WinConditionResult.CONTINUE;
        }
        if (leaders.size() == 1) {
            return WinConditionResult.playerWin(leaders.get(0));
        }
        return tieAtTheLimit(rules, state);
    }

    /**
     * Every player tied for the most kills.
     *
     * <p>A zero-kill match returns every player, which is right: nobody led, so the limit is a draw
     * or a sudden death rather than a win for the lowest entity id.
     */
    private static List<Integer> leadersByKills(World world, List<Integer> players) {
        List<Integer> leaders = new ArrayList<>();
        int best = Integer.MIN_VALUE;
        for (int player : players) {
            ScoreComponent score = world.getComponent(player, ScoreComponent.class);
            int kills = score == null ? 0 : score.kills;
            if (kills > best) {
                best = kills;
                leaders.clear();
                leaders.add(player);
            } else if (kills == best) {
                leaders.add(player);
            }
        }
        return leaders;
    }

    // ---- TEAM_DEATHMATCH and PAYLOAD -------------------------------------------------

    /**
     * Team scores, then the same limit rules.
     *
     * <p>{@code PAYLOAD} shares this path deliberately. D01-S5.5 decides it on the payload reaching
     * its goal, and no payload entity exists; falling through to team score means a payload match
     * still <em>ends</em>, which E11's "a match must always terminate" needs, rather than running to
     * the safety cap of D11-S5.8.
     */
    private static WinConditionResult teamDeathmatch(
            World world,
            List<Integer> players,
            MatchRulesComponent rules,
            MatchClockComponent clock,
            MatchStateComponent state) {

        // Sorted so the leader scan visits teams in the same order on every peer (G3).
        SortedMap<Integer, Integer> killsByTeam = new TreeMap<>();
        for (int player : players) {
            TeamComponent team = world.getComponent(player, TeamComponent.class);
            ScoreComponent score = world.getComponent(player, ScoreComponent.class);
            if (team == null || team.teamId == TeamComponent.FREE_FOR_ALL) {
                continue;
            }
            killsByTeam.merge(team.teamId, score == null ? 0 : score.kills, Integer::sum);
        }
        if (killsByTeam.isEmpty()) {
            return timeIsUp(clock) ? tieAtTheLimit(rules, state) : WinConditionResult.CONTINUE;
        }

        List<Integer> leaders = new ArrayList<>();
        int best = Integer.MIN_VALUE;
        for (var entry : killsByTeam.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                leaders.clear();
                leaders.add(entry.getKey());
            } else if (entry.getValue() == best) {
                leaders.add(entry.getKey());
            }
        }
        if (rules.scoreLimit > 0 && best >= rules.scoreLimit && leaders.size() == 1) {
            return WinConditionResult.teamWin(leaders.get(0));
        }
        if (!timeIsUp(clock)) {
            return WinConditionResult.CONTINUE;
        }
        return leaders.size() == 1 ? WinConditionResult.teamWin(leaders.get(0)) : tieAtTheLimit(rules, state);
    }

    // ---- LAST_MACHINE ----------------------------------------------------------------

    /**
     * The last player with a living vehicle wins.
     *
     * <p>"Living" is a chassis that is not {@code DESTROYED} <em>and</em> an entity that is still
     * alive: a wrecked vehicle is queued for destruction in the same tick it dies (D07-S5.7), so
     * asking only about the damage state would count a vehicle that no longer exists.
     */
    private static WinConditionResult lastMachine(World world, List<Integer> players, MatchClockComponent clock) {
        List<Integer> survivors = new ArrayList<>();
        for (int player : players) {
            if (hasLivingVehicle(world, player)) {
                survivors.add(player);
            }
        }
        if (survivors.size() == 1) {
            return WinConditionResult.playerWin(survivors.get(0));
        }
        if (survivors.isEmpty()) {
            // Everyone died in the same tick — a mutual ram, or the kill plane taking the last two.
            return WinConditionResult.DRAW;
        }
        if (!timeIsUp(clock)) {
            return WinConditionResult.CONTINUE;
        }
        // D01-S5.5: at the limit the most intact vehicle wins, ties broken on damage dealt.
        int best = EntityId.NULL;
        float bestHp = Float.NEGATIVE_INFINITY;
        float bestDealt = Float.NEGATIVE_INFINITY;
        boolean tied = false;
        for (int player : survivors) {
            float hp = remainingHp(world, player);
            ScoreComponent score = world.getComponent(player, ScoreComponent.class);
            float dealt = score == null ? 0f : score.damageDealt;
            if (hp > bestHp || (hp == bestHp && dealt > bestDealt)) {
                best = player;
                bestHp = hp;
                bestDealt = dealt;
                tied = false;
            } else if (hp == bestHp && dealt == bestDealt) {
                tied = true;
            }
        }
        return tied ? WinConditionResult.DRAW : WinConditionResult.playerWin(best);
    }

    private static boolean hasLivingVehicle(World world, int playerEntity) {
        ControlledVehicleComponent controlled = world.getComponent(playerEntity, ControlledVehicleComponent.class);
        if (controlled == null || controlled.vehicleEntity == EntityId.NULL) {
            return false;
        }
        if (!world.isAlive(controlled.vehicleEntity)) {
            return false;
        }
        VehicleChassisComponent chassis = world.getComponent(controlled.vehicleEntity, VehicleChassisComponent.class);
        if (chassis == null || chassis.chassisPartEntity == EntityId.NULL) {
            return false;
        }
        DamageStateComponent damage = world.getComponent(chassis.chassisPartEntity, DamageStateComponent.class);
        return damage == null || damage.state != DamageState.DESTROYED;
    }

    /** The sum of a player's vehicle's live part hit points; 0 when they have no vehicle. */
    private static float remainingHp(World world, int playerEntity) {
        ControlledVehicleComponent controlled = world.getComponent(playerEntity, ControlledVehicleComponent.class);
        return controlled == null ? 0f : VehicleIntegrity.remainingHp(world, controlled.vehicleEntity);
    }

    // ---- Shared -----------------------------------------------------------------------

    private static boolean timeIsUp(MatchClockComponent clock) {
        return clock.timeLimitTicks > 0 && clock.tick >= clock.timeLimitTicks;
    }

    /**
     * What a tie at the time limit means.
     *
     * <p>Sudden death is available exactly once (D01-E2). {@link MatchStateComponent#suddenDeath}
     * is already set when the extension is running, so a second tie at the extended limit falls
     * through to a draw rather than extending again.
     */
    private static WinConditionResult tieAtTheLimit(MatchRulesComponent rules, MatchStateComponent state) {
        if (rules.suddenDeathTicks > 0 && !state.suddenDeath) {
            return WinConditionResult.SUDDEN_DEATH;
        }
        return WinConditionResult.DRAW;
    }
}
