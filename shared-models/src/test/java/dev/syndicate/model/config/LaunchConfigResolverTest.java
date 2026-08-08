/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.RuntimeMode;
import dev.syndicate.model.SimulationConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Launch configuration precedence and validation (docs/03_runtime_modes.md#D03-S4.2, #D03-S5.2). */
@Tag("unit")
class LaunchConfigResolverTest {

    private static final Map<String, String> NO_ENV = Map.of();

    @Test
    void defaults_areTheBlueprintDefaults() {
        LaunchConfig config = client().resolve(new String[0]);

        assertThat(config.mode()).isEqualTo(RuntimeMode.SINGLE_PLAYER);
        assertThat(config.serverPort()).isEqualTo(LaunchConfig.DEFAULT_PORT);
        assertThat(config.maxPlayers()).isEqualTo(LaunchConfig.DEFAULT_MAX_PLAYERS);
        assertThat(config.gameMode()).isEqualTo(GameMode.DEATHMATCH);
        assertThat(config.botDifficulty()).isEqualTo(BotDifficulty.NORMAL);
        assertThat(config.arenaId().value()).isEqualTo(LaunchConfig.DEFAULT_ARENA);
        assertThat(config.snapshotRateHz()).isEqualTo(SimulationConstants.SNAPSHOT_RATE_HZ);
    }

    @Test
    void singlePlayer_getsSevenBotsByDefault() {
        // D03-S4.2: bots default to 0, except in SINGLE_PLAYER where an empty arena is not a game.
        assertThat(client().resolve(new String[0]).botCount()).isEqualTo(LaunchConfig.DEFAULT_SINGLE_PLAYER_BOTS);
    }

    @Test
    void serverExecutable_derivesDedicatedServerAndItsDefaults() {
        // D03-S5.2: the executable identity selects the mode when no --mode is given.
        LaunchConfig config = server().resolve(new String[0]);

        assertThat(config.mode()).isEqualTo(RuntimeMode.DEDICATED_SERVER);
        assertThat(config.headless()).isTrue();
        assertThat(config.autoStart()).isTrue();
        assertThat(config.adminConsole()).isTrue();
        assertThat(config.botCount()).isZero();
    }

    @Test
    void connectFlag_impliesLocalClient() {
        LaunchConfig config = client().resolve(new String[] {"--connect", "example.test"});

        assertThat(config.mode()).isEqualTo(RuntimeMode.LOCAL_CLIENT);
        assertThat(config.serverHost()).isEqualTo("example.test");
    }

    @Test
    void cliBeatsEnvironmentBeatsFileBeatsDefault(@TempDir Path dir) throws IOException {
        // T-D03-7 / AC-D03-5 (docs/03_runtime_modes.md#D03-S8)
        Path file = dir.resolve("syndicate.conf");
        Files.writeString(file, "# comment\nport=1111\nmax-players=4\n");
        Map<String, String> env = Map.of("SYNDICATE_PORT", "2222");

        LaunchConfig config = new LaunchConfigResolver(true, env)
                .resolve(new String[] {"--config", file.toString(), "--port", "3333"});

        assertThat(config.serverPort()).isEqualTo(3333);
        assertThat(config.sourceOf("serverPort")).isEqualTo(ConfigSource.CLI);
        // A value set only in the file keeps the file as its source.
        assertThat(config.maxPlayers()).isEqualTo(4);
        assertThat(config.sourceOf("maxPlayers")).isEqualTo(ConfigSource.CONFIG_FILE);
        // A value nobody set reports DEFAULT.
        assertThat(config.sourceOf("gameMode")).isEqualTo(ConfigSource.DEFAULT);
    }

    @Test
    void environmentBeatsFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("syndicate.conf");
        Files.writeString(file, "port=1111\n");

        LaunchConfig config = new LaunchConfigResolver(true, Map.of("SYNDICATE_PORT", "2222"))
                .resolve(new String[] {"--config", file.toString()});

        assertThat(config.serverPort()).isEqualTo(2222);
        assertThat(config.sourceOf("serverPort")).isEqualTo(ConfigSource.ENVIRONMENT);
    }

    @Test
    void unknownFlag_isFatalWithAsuggestion() {
        // T-D03-6 / D03-R6: a silently ignored typo is how a server runs misconfigured for a week.
        assertThatThrownBy(() -> client().resolve(new String[] {"--nonsense"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("--nonsense")
                .extracting(e -> ((ConfigException) e).exitCode())
                .isEqualTo(ExitCode.USAGE);
    }

    @Test
    void nearMissFlag_namesTheIntendedOne() {
        assertThatThrownBy(() -> client().resolve(new String[] {"--bot", "4"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("did you mean '--bots'");
    }

    @Test
    void unknownConfigKey_isFatal(@TempDir Path dir) throws IOException {
        // D03-R7: same rationale as an unknown flag.
        Path file = dir.resolve("syndicate.conf");
        Files.writeString(file, "portt=1111\n");

        assertThatThrownBy(() -> server().resolve(new String[] {"--config", file.toString()}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unknown config key 'portt'");
    }

    @Test
    void unreadableConfigFile_isFatal(@TempDir Path dir) {
        // D03-E16: a silently ignored config file is worse than a failure.
        assertThatThrownBy(() -> server().resolve(new String[] {
                    "--config", dir.resolve("absent.conf").toString()
                }))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("not readable");
    }

    @Test
    void connectWithBots_isFatal() {
        // T-D03-4 / D03-E4: bots belong to the authority.
        assertThatThrownBy(() -> client().resolve(new String[] {"--connect", "host.test", "--bots", "4"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("bots belong to the authority");
    }

    @Test
    void headlessWithFullscreen_isFatal() {
        assertThatThrownBy(() ->
                        client().resolve(new String[] {"--mode", "DEDICATED_SERVER", "--headless", "--fullscreen"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("contradictory display options");
    }

    @Test
    void dedicatedServerThatRenders_isFatal() {
        assertThatThrownBy(() -> client().resolve(new String[] {"--mode", "DEDICATED_SERVER", "--headless=false"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("cannot render");
    }

    @Test
    void repeatedFlag_takesTheLast() {
        // D03-E18: standard CLI semantics.
        assertThat(client().resolve(new String[] {"--port", "1", "--port", "2"}).serverPort())
                .isEqualTo(2);
    }

    @Test
    void badValue_isFatalNamingTheField() {
        assertThatThrownBy(() -> client().resolve(new String[] {"--port", "eleven"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("'port' expects an integer");
    }

    @Test
    void badEnumValue_listsTheLegalValues() {
        assertThatThrownBy(() -> client().resolve(new String[] {"--game-mode", "BATTLE_ROYALE"}))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("DEATHMATCH");
    }

    @Test
    void tickRate_isNotConfigurable() {
        // G2 / D00-E5: a mode may change the snapshot rate, never the tick rate.
        assertThat(client().resolve(new String[0]).tickRateHz()).isEqualTo(SimulationConstants.TICK_RATE_HZ);
        assertThatThrownBy(() -> client().resolve(new String[] {"--tick-rate", "30"}))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void seed_isRecordedSoAbugIsReproducible() {
        assertThat(client().resolve(new String[] {"--seed", "1337"}).matchSeed())
                .isEqualTo(1337L);
    }

    @Test
    void effectiveValues_reportEverySourceForTheStartupLog() {
        LaunchConfig config = client().resolve(new String[] {"--seed", "1337"});

        assertThat(config.describeEffectiveValues())
                .anyMatch(line -> line.startsWith("matchSeed = 1337 [CLI]"))
                .anyMatch(line -> line.contains("tickRateHz = 60 [FIXED: G2]"));
    }

    private static LaunchConfigResolver client() {
        return new LaunchConfigResolver(false, NO_ENV);
    }

    private static LaunchConfigResolver server() {
        return new LaunchConfigResolver(true, NO_ENV);
    }
}
