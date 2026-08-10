/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.component.DamageLedgerComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.damage.DamageLedger;
import dev.syndicate.core.damage.PartDestroyedEvent;
import dev.syndicate.core.damage.VehicleDestroyedEvent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.MatchPhase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 17: awards points for what the tick destroyed
 * (docs/04_entity_component_model.md#D04-S4.4, docs/01_product_game_design.md#D01-S5.4).
 *
 * <p>Authority only (G15): a client renders the scoreboard it is told about and never evaluates one.
 *
 * <p><b>It reads events, not health.</b> {@code PartDestroyedEvent} carries the player whose damage
 * crossed zero, captured at the instant it did; going back to {@code HealthComponent.lastAttacker}
 * here would be too late twice over, because the same tick's propagation can overwrite the attacker
 * and a part that fractured no longer exists by slot 17.
 *
 * <p><b>Assists are the reason the damage ledger exists.</b> A player who took a vehicle to a
 * quarter of its hit points and then disengaged gets credit when someone else finishes it within
 * {@link DamageLedger#ASSIST_WINDOW_TICKS}; the same player gets nothing for damage they did three
 * minutes earlier. The threshold is a fraction of the victim's <em>total effective</em> hit points
 * — the sum over the parts it actually had, not a nominal maximum — so stripping a heavily armoured
 * vehicle counts for as much as it cost.
 *
 * <p>Damage after the win condition fires is ignored for scoring and still simulated (D01-E7), which
 * is why the phase is checked here rather than by refusing to apply the damage in slot 12: the
 * wreckage of the last few seconds still happens, it just does not count.
 */
public final class ScoreSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 17;

    /** Points for the player whose damage crossed a part's zero (D01-S5.4). */
    public static final int SCORE_PART_DESTROYED = 10;

    /** Points for the killer of a vehicle (D01-S5.4). */
    public static final int SCORE_VEHICLE_DESTROYED = 100;

    /** Points for a contributor who met the assist threshold (D01-S5.4). */
    public static final int SCORE_ASSIST = 40;

    /** Points deducted for driving off the map or otherwise killing yourself (D01-S5.4, D01-E3). */
    public static final int SCORE_SELF_DESTRUCT = -50;

    /** Points deducted for killing a teammate (D01-S5.4). */
    public static final int SCORE_TEAM_KILL = -100;

    @Override
    public Phase phase() {
        return Phase.POST_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        // No families: this system reads events, and the entities they name are usually already
        // queued for destruction by the time it runs (D07-S5.7).
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        // Copied because drainSameTick hands back an immutable view and these are about to be sorted.
        List<PartDestroyedEvent> partsDestroyed =
                new ArrayList<>(world.events().drainSameTick(PartDestroyedEvent.class));
        List<VehicleDestroyedEvent> vehiclesDestroyed =
                new ArrayList<>(world.events().drainSameTick(VehicleDestroyedEvent.class));
        if (partsDestroyed.isEmpty() && vehiclesDestroyed.isEmpty()) {
            return;
        }
        if (!scoringIsOpen(world)) {
            // D01-E7: the destruction still happened; it simply earns nothing.
            return;
        }
        DamageLedger ledger = ledger(world);

        // Sorted so two peers replaying the same tick award the same points in the same order (G3).
        partsDestroyed.sort(Comparator.comparingInt(PartDestroyedEvent::partEntity));
        for (PartDestroyedEvent event : partsDestroyed) {
            award(world, event.killerPlayerEntity(), SCORE_PART_DESTROYED);
        }

        vehiclesDestroyed.sort(Comparator.comparingInt(VehicleDestroyedEvent::vehicleEntity));
        for (VehicleDestroyedEvent event : vehiclesDestroyed) {
            awardKill(world, event, ledger, tick);
        }
    }

    // ---- A vehicle died (D01-S5.4 onPartDestroyed, chassis branch) --------------------

    /**
     * Credits a kill, a death, and every assist that earned one.
     *
     * <p>A kill with no attributed killer is a self-destruct: driving off the map, or being crushed
     * by the arena. D01-E3 makes that {@link #SCORE_SELF_DESTRUCT} against the victim and no credit
     * to anybody, rather than a free point for whoever happened to shoot the vehicle last.
     */
    private void awardKill(World world, VehicleDestroyedEvent event, DamageLedger ledger, long tick) {
        int victimVehicle = event.vehicleEntity();
        int victimPlayer = ownerOf(world, victimVehicle);
        int killerPlayer = event.killerEntity();

        recordDeath(world, victimPlayer);

        if (killerPlayer == EntityId.NULL || killerPlayer == victimPlayer) {
            award(world, victimPlayer, SCORE_SELF_DESTRUCT);
            LOG.debug("vehicle {} died unattributed at tick {}", EntityId.toString(victimVehicle), tick);
            return;
        }
        if (sameTeam(world, killerPlayer, victimPlayer)) {
            award(world, killerPlayer, SCORE_TEAM_KILL);
        } else {
            award(world, killerPlayer, SCORE_VEHICLE_DESTROYED);
            recordKill(world, killerPlayer);
        }
        if (ledger == null) {
            return;
        }
        float assistThreshold = DamageLedger.ASSIST_DAMAGE_FRACTION * totalEffectiveHp(world, victimVehicle);
        if (assistThreshold <= 0f) {
            return;
        }
        for (int contributor : ledger.contributorsAgainst(victimVehicle)) {
            if (contributor == killerPlayer || contributor == victimPlayer || contributor == EntityId.NULL) {
                continue;
            }
            float recent = ledger.within(victimVehicle, contributor, tick, DamageLedger.ASSIST_WINDOW_TICKS);
            if (recent >= assistThreshold) {
                award(world, contributor, SCORE_ASSIST);
                recordAssist(world, contributor);
            }
        }
    }

    /**
     * The sum of the victim's parts' maximum hit points.
     *
     * <p>Read at the moment of death, from the vehicle that died, so a vehicle already stripped to
     * its chassis has a smaller pool and a smaller assist threshold — which is right: the 20% is a
     * share of the work the kill actually took.
     */
    private static float totalEffectiveHp(World world, int vehicleEntity) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        float total = 0f;
        if (chassis != null) {
            total += maxHpOf(world, chassis.chassisPartEntity);
        }
        if (graph != null) {
            for (SlotNode node : graph.nodes) {
                total += maxHpOf(world, node.childEntity);
            }
        }
        return total;
    }

    private static float maxHpOf(World world, int partEntity) {
        HealthComponent health =
                partEntity == EntityId.NULL ? null : world.getComponent(partEntity, HealthComponent.class);
        return health == null ? 0f : health.maxHp;
    }

    // ---- Scoreboard writes -----------------------------------------------------------

    private static void award(World world, int playerEntity, int points) {
        ScoreComponent score = scoreOf(world, playerEntity);
        if (score != null) {
            score.objectiveScore += points;
        }
    }

    private static void recordKill(World world, int playerEntity) {
        ScoreComponent score = scoreOf(world, playerEntity);
        if (score != null) {
            score.kills++;
        }
    }

    private static void recordAssist(World world, int playerEntity) {
        ScoreComponent score = scoreOf(world, playerEntity);
        if (score != null) {
            score.assists++;
        }
    }

    private static void recordDeath(World world, int playerEntity) {
        ScoreComponent score = scoreOf(world, playerEntity);
        if (score != null) {
            score.deaths++;
        }
    }

    /**
     * A player's scoreboard row, or null when that player entity has none.
     *
     * <p>Null rather than created on demand: a score row belongs to a player entity that something
     * else owns the lifetime of, and inventing one here would give points to an id that has already
     * left the match (D01-E8).
     */
    private static ScoreComponent scoreOf(World world, int playerEntity) {
        if (playerEntity == EntityId.NULL || !world.isAlive(playerEntity)) {
            return null;
        }
        return world.getComponent(playerEntity, ScoreComponent.class);
    }

    // ---- Match context ---------------------------------------------------------------

    /** Whether the match is in a phase where points are awarded (D01-E7, AC-D01-12). */
    private static boolean scoringIsOpen(World world) {
        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        return state == null || state.phase == MatchPhase.ACTIVE;
    }

    private static DamageLedger ledger(World world) {
        DamageLedgerComponent component = world.getComponent(EntityId.MATCH, DamageLedgerComponent.class);
        return component == null ? null : component.ledger;
    }

    private static int ownerOf(World world, int vehicleEntity) {
        OwnerComponent owner =
                vehicleEntity == EntityId.NULL ? null : world.getComponent(vehicleEntity, OwnerComponent.class);
        return owner == null ? EntityId.NULL : owner.ownerEntity;
    }

    /** Whether two players are on the same side, for the team-kill penalty. */
    private static boolean sameTeam(World world, int playerA, int playerB) {
        TeamComponent teamA = playerA == EntityId.NULL ? null : world.getComponent(playerA, TeamComponent.class);
        TeamComponent teamB = playerB == EntityId.NULL ? null : world.getComponent(playerB, TeamComponent.class);
        if (teamA == null || teamB == null) {
            return false;
        }
        return teamA.teamId != TeamComponent.FREE_FOR_ALL && teamA.teamId == teamB.teamId;
    }
}
