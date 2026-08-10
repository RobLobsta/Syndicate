/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import dev.syndicate.core.component.DamageLedgerComponent;
import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.RandomSourceComponent;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.MatchPhase;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.config.LaunchConfig;
import java.util.Objects;

/**
 * Builds a world and its match singleton (docs/04_entity_component_model.md#D04-S5.4).
 *
 * <p>Step 6 of the startup sequence (D03-S5.1). It is deliberately small: {@link World} knows
 * nothing about physics, assets or the match, so those are constructor dependencies of the systems
 * that need them (DEC-012) rather than fields this factory attaches. What is left is the one thing
 * a world cannot be built without — the {@code MATCH} entity at the reserved index 1 (D04-R5),
 * which every match rule and the seeded RNG census hang off.
 *
 * <p>D04-S5.4 ends by calling {@code ArenaFactory.load}. That call is made by whoever owns the
 * physics world — {@code ServerRuntime} at startup, a test in its fixture — because loading an arena
 * needs a {@code PhysicsWorld} and a {@code ShapeCache}, and this factory deliberately depends on
 * neither.
 */
public final class WorldFactory {

    private WorldFactory() {
        throw new AssertionError("no instances");
    }

    /**
     * Creates a world configured for a launch.
     *
     * <p>The authority flag decides the world's entity index range, so an authority-allocated id and
     * a client-local one can never collide (D04-R24) — which is why it comes from the resolved mode
     * rather than from a parameter a caller could get wrong.
     */
    public static World create(LaunchConfig config) {
        Objects.requireNonNull(config, "config");
        World world = new World(config.matchSeed(), config.isAuthority());
        createMatchEntity(world, config);
        return world;
    }

    /**
     * The singleton {@code MATCH} entity at the reserved index 1 (D04-R4, D04-R5).
     *
     * @return its entity id
     */
    public static int createMatchEntity(World world, LaunchConfig config) {
        Entity match = world.createEntityWithReservedIndex(EntityId.MATCH);
        int matchEntity = match.id();

        MatchStateComponent state = new MatchStateComponent();
        state.phase = MatchPhase.LOBBY;
        world.addComponent(matchEntity, state);

        MatchClockComponent clock = new MatchClockComponent();
        // Seconds are what a server operator configures; ticks are what the simulation counts. The
        // conversion happens once, here, so no system has to know the tick rate to read a time limit
        // (G2 keeps TICK_RATE_HZ global, so the conversion is unambiguous).
        clock.timeLimitTicks = Math.max(0, config.timeLimitSeconds()) * SimulationConstants.TICK_RATE_HZ;
        world.addComponent(matchEntity, clock);

        MatchRulesComponent rules = new MatchRulesComponent();
        rules.mode = config.gameMode();
        rules.arenaId = config.arenaId();
        rules.botCount = config.botCount();
        rules.botDifficulty = config.botDifficulty();
        rules.autoStart = config.autoStart();
        // D01-R21/R22 need somewhere for a free-for-all to say "no friendly fire" without a rule
        // per mode: a team mode has teams to protect, and a free-for-all has nobody who is not a
        // valid target.
        rules.friendlyFire = config.gameMode() != GameMode.TEAM_DEATHMATCH && config.gameMode() != GameMode.PAYLOAD;
        world.addComponent(matchEntity, rules);

        RandomSourceComponent random = new RandomSourceComponent();
        random.matchSeed = config.matchSeed();
        world.addComponent(matchEntity, random);

        // The damage ledger outlives every vehicle it records damage against — which is the point of
        // it, because the moment it is read is the moment a victim dies (D01-S5.4). The match entity
        // is the only thing in the world guaranteed to still be here then.
        world.addComponent(matchEntity, new DamageLedgerComponent());

        return matchEntity;
    }
}
