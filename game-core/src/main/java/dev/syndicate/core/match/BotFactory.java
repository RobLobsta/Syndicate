/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.match;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.BotControllerComponent;
import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.util.StreamId;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.GameMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates bot <em>players</em> and keeps their vehicles under AI control
 * (docs/11_ai_bots_and_match_simulation.md#D11-S5.6).
 *
 * <p><b>A bot is a player, not a vehicle.</b> D11-S5.6's pseudocode creates a player entity, gives
 * it a team and a scoreboard row, and only then spawns something for it to drive. That order is what
 * makes a bot respawn work at all, and it is why {@code BotControllerComponent} cannot be attached
 * here: the vehicle does not exist yet. Slot 5 creates it next tick, and
 * {@link #attachControllers(World)} — called by {@code MatchFlowSystem} every tick — puts the
 * controller on whatever vehicle a bot player currently owns. That reconciliation costs one scan of
 * the vehicle family and handles the first spawn and every respawn with the same three lines.
 *
 * <p>D11-R12: bots draw from the same assembly catalogue players do, so a bot can never field
 * equipment a human cannot.
 */
public final class BotFactory {

    private static final Logger LOG = LoggerFactory.getLogger(BotFactory.class);

    private BotFactory() {
        throw new AssertionError("no instances");
    }

    /**
     * Adds {@code count} bot players to the match.
     *
     * <p>No vehicle is spawned here. The caller — {@code MatchFlowSystem} in {@code LOBBY} — queues
     * the spawns, because it is the thing that owns the spawn queue and the arena's spawn points.
     *
     * @param nextPlayerId the id to give the first bot; ids ascend from there
     * @return the created player entities, in creation order
     */
    public static List<Integer> fill(
            World world, AssetIndex assets, int count, BotDifficulty difficulty, int nextPlayerId) {

        List<Integer> created = new ArrayList<>();
        if (count <= 0) {
            return created;
        }
        List<AssetId> catalogue = assemblyCatalogue(assets);
        if (catalogue.isEmpty()) {
            LOG.error("no assemblies are loaded; {} bots cannot be given vehicles (D11-R12)", count);
            return created;
        }
        GameMode mode = MatchFacts.mode(world);
        for (int i = 0; i < count; i++) {
            Entity player = world.createEntity();
            int playerEntity = player.id();

            PlayerIdentityComponent identity = new PlayerIdentityComponent();
            identity.playerId = nextPlayerId + i;
            identity.displayName = botName(identity.playerId);
            identity.isBot = true;
            identity.botDifficulty = difficulty;
            identity.joinTick = world.currentTick();
            identity.selectedAssemblyId = chooseAssembly(world, catalogue);
            world.addComponent(playerEntity, identity);

            TeamComponent team = new TeamComponent();
            team.teamId = assignTeam(mode, i);
            world.addComponent(playerEntity, team);

            world.addComponent(playerEntity, new ScoreComponent());
            world.addComponent(playerEntity, new ControlledVehicleComponent());
            created.add(playerEntity);
        }
        LOG.info("filled {} bot players at difficulty {}", created.size(), difficulty);
        return created;
    }

    /**
     * Gives every bot-owned vehicle a {@link BotControllerComponent}, and takes it off vehicles that
     * are no longer bot-owned.
     *
     * <p>Idempotent, so calling it every tick is the design rather than a cost: a bot that respawns
     * gets a fresh controller with a clean sensor snapshot, and one whose seat was taken by a human
     * (D11-R11) stops being driven by the AI in the same tick.
     */
    public static void attachControllers(World world) {
        Family vehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class, OwnerComponent.class));
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < vehicles.size(); i++) {
            int vehicle = entityIds[i];
            OwnerComponent owner = world.getComponent(vehicle, OwnerComponent.class);
            PlayerIdentityComponent identity = owner == null || !world.isAlive(owner.ownerEntity)
                    ? null
                    : world.getComponent(owner.ownerEntity, PlayerIdentityComponent.class);
            boolean shouldBeAi = identity != null && identity.isBot;
            boolean isAi = world.hasComponent(vehicle, BotControllerComponent.class);

            if (shouldBeAi && !isAi) {
                BotControllerComponent controller = new BotControllerComponent();
                controller.difficulty = identity.botDifficulty == null ? BotDifficulty.NORMAL : identity.botDifficulty;
                world.addComponent(vehicle, controller);
            } else if (!shouldBeAi && isAi) {
                world.removeComponent(vehicle, BotControllerComponent.class);
            }
        }
    }

    /**
     * Which team a bot joins.
     *
     * <p>Alternating by index rather than by counting the teams already present: the whole set is
     * created in one call, so alternating is exactly balanced and does not depend on iteration order
     * over a half-built lobby (G3).
     */
    private static int assignTeam(GameMode mode, int index) {
        return switch (mode) {
            case TEAM_DEATHMATCH, PAYLOAD -> index % 2;
            default -> TeamComponent.FREE_FOR_ALL;
        };
    }

    /**
     * Every loaded assembly id, sorted.
     *
     * <p>Sorted rather than in map order because the choice below indexes into it with a seeded
     * draw, and a seed that picked a different vehicle depending on hash order would break G4 in the
     * least visible way possible — a replay that diverges only when the map is rebuilt.
     */
    private static List<AssetId> assemblyCatalogue(AssetIndex assets) {
        List<AssetId> ids = new ArrayList<>(assets.assemblyIds());
        ids.sort(Comparator.comparing(AssetId::value));
        return ids;
    }

    /**
     * Picks a vehicle for one bot.
     *
     * <p>D11-S5.6 calls this {@code selectAssemblyForDifficulty}, and it deliberately does not vary
     * with difficulty: D11-R6 forbids a difficulty level granting any advantage that is not a
     * perception or execution parameter, and "the hard bots get the better car" is exactly that.
     */
    private static AssetId chooseAssembly(World world, List<AssetId> catalogue) {
        return catalogue.get(world.random().stream(StreamId.BOT_DECISION).nextInt(catalogue.size()));
    }

    /** A stable display name. Bots are numbered from 1 because "Bot 0" reads as a bug. */
    private static String botName(int playerId) {
        return "Bot " + (playerId + 1);
    }
}
