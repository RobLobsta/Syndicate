/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.ScoreComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the person at the keyboard into the match (docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>The counterpart of {@code BotFactory}, and deliberately the same shape: a {@code PLAYER}
 * archetype (D04-S4.2) with {@code isBot} false. Everything downstream — the lobby's readiness
 * check, the spawn queue, scoring, the scoreboard — reads the same components for a human as for a
 * bot, which is what makes a listen server and the offline simulator the same code path (G17).
 *
 * <p>It lives in {@code game-client} rather than beside {@code BotFactory} because joining is the
 * one thing only a client does. When a transport exists, a remote peer's join will arrive at the
 * authority as a message and want a core-side factory; this creates the local player directly,
 * because in {@code SINGLE_PLAYER} the client <em>is</em> the authority (D03-R9) and there is no
 * message to send.
 */
public final class LocalPlayerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(LocalPlayerFactory.class);

    /** The name a local player is given until a profile or a menu offers a better one. */
    public static final String DEFAULT_NAME = "Player";

    private LocalPlayerFactory() {
        throw new AssertionError("no instances");
    }

    /**
     * Creates the local player entity and returns it.
     *
     * <p>Player id 0, and joined before any bot: {@code MatchFlowSystem} fills the lobby to
     * {@code botCount} with ids from there, and D11-R11 removes bots oldest-first — so the human,
     * being oldest, is never the one removed to make room.
     *
     * @return the player entity, or {@link dev.syndicate.core.ecs.EntityId#NULL} when no assembly is
     *     loaded for them to drive
     */
    public static int join(World world, AssetIndex assets, GameMode mode, String displayName) {
        return join(world, assets, mode, displayName, null);
    }

    /**
     * Creates the local player driving a chosen vehicle.
     *
     * @param selectedAssembly what the player picked in the garage, or null to take the first in
     *     the catalogue. An id that is not loaded also falls back rather than refusing to join —
     *     content going missing under a saved choice should cost the player a different car, not
     *     the match (G18).
     */
    public static int join(
            World world, AssetIndex assets, GameMode mode, String displayName, AssetId selectedAssembly) {
        AssetId assembly = resolveAssembly(assets, selectedAssembly);
        if (assembly == null) {
            LOG.error("no assemblies are loaded; the local player has nothing to drive (D11-R12)");
            return dev.syndicate.core.ecs.EntityId.NULL;
        }

        Entity entity = world.createEntity();
        int playerEntity = entity.id();

        PlayerIdentityComponent identity = new PlayerIdentityComponent();
        identity.playerId = 0;
        identity.displayName = displayName == null || displayName.isBlank() ? DEFAULT_NAME : displayName;
        identity.isBot = false;
        identity.joinTick = world.currentTick();
        identity.selectedAssemblyId = assembly;
        world.addComponent(playerEntity, identity);

        TeamComponent team = new TeamComponent();
        // Team 0 in a team mode and free-for-all otherwise, matching how BotFactory assigns.
        team.teamId = mode == GameMode.TEAM_DEATHMATCH || mode == GameMode.PAYLOAD ? 0 : TeamComponent.FREE_FOR_ALL;
        world.addComponent(playerEntity, team);

        world.addComponent(playerEntity, new ScoreComponent());
        world.addComponent(playerEntity, new ControlledVehicleComponent());

        LOG.info("local player {} joined as {} driving {}", identity.playerId, identity.displayName, assembly.value());
        return playerEntity;
    }

    /**
     * The assembly the local player drives.
     *
     * <p>The garage's choice when there is one. Without it, the lowest id in the catalogue —
     * lowest rather than first-encountered so a launch that skips the menu (`--auto-start`, and
     * every capture in CI) picks the same car every run on every machine (G3), and a handling
     * change can be judged against the previous session's.
     */
    private static AssetId resolveAssembly(AssetIndex assets, AssetId selectedAssembly) {
        if (selectedAssembly != null && assets.assembly(selectedAssembly) != null) {
            return selectedAssembly;
        }
        if (selectedAssembly != null) {
            LOG.warn("assembly {} is not loaded; falling back to the catalogue's first", selectedAssembly.value());
        }
        return assets.assemblyIds().stream()
                .min(java.util.Comparator.comparing(AssetId::value))
                .orElse(null);
    }

    /** True when a human is already in the lobby, so a reconnect does not create a second one. */
    public static boolean hasHuman(World world) {
        Family players = world.family(ComponentQuery.all(PlayerIdentityComponent.class));
        int[] entityIds = players.snapshot();
        for (int i = 0; i < players.size(); i++) {
            PlayerIdentityComponent identity = world.getComponent(entityIds[i], PlayerIdentityComponent.class);
            if (identity != null && !identity.isBot) {
                return true;
            }
        }
        return false;
    }
}
