/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.core.component.MatchClockComponent;
import dev.syndicate.core.component.MatchRulesComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.RandomSourceComponent;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.MatchPhase;
import dev.syndicate.model.RuntimeMode;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.config.LaunchConfig;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** World construction (docs/04_entity_component_model.md#D04-S5.4). */
@Tag("unit")
class WorldFactoryTest {

    private static final long MATCH_SEED = 987_654_321L;

    /** D04-R5: the match singleton lives at the reserved index 1, and nothing else does. */
    @Test
    void theMatchEntityIsTheSingletonAtIndexOne() {
        World world = WorldFactory.create(config(RuntimeMode.DEDICATED_SERVER, 300));

        assertThat(world.isAlive(EntityId.MATCH)).isTrue();
        assertThat(EntityId.index(EntityId.MATCH)).isEqualTo(1);
        assertThat(world.entityCount()).isEqualTo(1);
        assertThat(world.getComponent(EntityId.MATCH, MatchStateComponent.class).phase)
                .isEqualTo(MatchPhase.LOBBY);
    }

    /** The time limit is configured in seconds and counted in ticks; the conversion happens once. */
    @Test
    void theTimeLimitIsConvertedToTicks() {
        World world = WorldFactory.create(config(RuntimeMode.DEDICATED_SERVER, 300));

        MatchClockComponent clock = world.getComponent(EntityId.MATCH, MatchClockComponent.class);
        assertThat(clock.timeLimitTicks).isEqualTo(300 * SimulationConstants.TICK_RATE_HZ);
        assertThat(clock.tick).isZero();
    }

    /** A negative or absent limit means no limit, not a negative tick budget. */
    @Test
    void aNonPositiveTimeLimitMeansNoLimit() {
        World world = WorldFactory.create(config(RuntimeMode.DEDICATED_SERVER, 0));

        assertThat(world.getComponent(EntityId.MATCH, MatchClockComponent.class).timeLimitTicks)
                .isZero();
    }

    /** G4: the match seed reaches the world's RNG and the component that reports it. */
    @Test
    void theMatchSeedIsCarriedIntoTheWorldAndTheComponent() {
        World world = WorldFactory.create(config(RuntimeMode.DEDICATED_SERVER, 300));

        assertThat(world.getComponent(EntityId.MATCH, RandomSourceComponent.class).matchSeed)
                .isEqualTo(MATCH_SEED);
        assertThat(world.random()).isNotNull();
        assertThat(world.getComponent(EntityId.MATCH, MatchRulesComponent.class).mode)
                .isEqualTo(GameMode.DEATHMATCH);
    }

    /** D04-R24: the authority flag decides the entity index range, so ids cannot collide. */
    @Test
    void authorityAndClientWorldsAllocateFromDifferentRanges() {
        World authority = WorldFactory.create(config(RuntimeMode.DEDICATED_SERVER, 60));
        World client = WorldFactory.create(config(RuntimeMode.LOCAL_CLIENT, 60));

        assertThat(authority.isAuthority()).isTrue();
        assertThat(client.isAuthority()).isFalse();
        assertThat(EntityId.index(authority.createEntity().id()))
                .isBetween(EntityId.AUTHORITY_INDEX_MIN, EntityId.AUTHORITY_INDEX_MAX);
        assertThat(EntityId.index(client.createEntity().id()))
                .isBetween(EntityId.CLIENT_LOCAL_INDEX_MIN, EntityId.CLIENT_LOCAL_INDEX_MAX);
    }

    private static LaunchConfig config(RuntimeMode mode, int timeLimitSeconds) {
        return new LaunchConfig(
                mode,
                mode.isHeadless(),
                mode == RuntimeMode.LOCAL_CLIENT ? "127.0.0.1" : null,
                LaunchConfig.DEFAULT_PORT,
                LaunchConfig.DEFAULT_MAX_PLAYERS,
                GameMode.DEATHMATCH,
                AssetId.of(LaunchConfig.DEFAULT_ARENA),
                0,
                BotDifficulty.NORMAL,
                MATCH_SEED,
                Path.of("assets"),
                false,
                SimulationConstants.SNAPSHOT_RATE_HZ,
                "INFO",
                null,
                true,
                0,
                1280,
                720,
                false,
                null,
                false,
                false,
                true,
                timeLimitSeconds,
                false,
                Map.of());
    }
}
