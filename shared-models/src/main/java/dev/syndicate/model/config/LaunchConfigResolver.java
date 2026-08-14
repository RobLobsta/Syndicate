/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model.config;

import dev.syndicate.model.AssetId;
import dev.syndicate.model.BotDifficulty;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.GameMode;
import dev.syndicate.model.RuntimeMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a {@link LaunchConfig} from defaults, a config file, the environment, and CLI flags
 * (docs/03_runtime_modes.md#D03-S4.2, #D03-S5.1, #D03-S5.2).
 *
 * <p>Precedence is defaults &lt; file &lt; environment &lt; CLI (D03-R5). Unknown flags and unknown
 * config keys are fatal, never warnings (D03-R6, D03-R7): a typo'd flag that is silently ignored is
 * how a server ends up running with the wrong settings for a week.
 */
public final class LaunchConfigResolver {

    /** Every recognised key, in the CLI flag's name without the leading dashes (D03-S4.3). */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "mode",
            "headless",
            "connect",
            "port",
            "max-players",
            "game-mode",
            "arena",
            "bots",
            "bot-difficulty",
            "seed",
            "assets",
            "strict-assets",
            "snapshot-rate",
            "log-level",
            "log-file",
            "vsync",
            "max-fps",
            "width",
            "height",
            "fullscreen",
            "config",
            "profile",
            "deterministic",
            "auto-start",
            "time-limit",
            "console");

    /** Flags that take no value; everything else requires one. */
    private static final Set<String> FLAG_KEYS = Set.of(
            "headless", "strict-assets", "vsync", "fullscreen", "profile", "deterministic", "auto-start", "console");

    /** Environment variable for each key that has one (D03-S4.2). */
    private static final Map<String, String> ENV_KEYS = Map.of(
            "mode", "SYNDICATE_MODE",
            "headless", "SYNDICATE_HEADLESS",
            "port", "SYNDICATE_PORT",
            "seed", "SYNDICATE_SEED",
            "assets", "SYNDICATE_ASSETS",
            "strict-assets", "SYNDICATE_STRICT_ASSETS",
            "log-level", "SYNDICATE_LOG_LEVEL",
            "config", "SYNDICATE_CONFIG");

    /** Map from a key to the {@link LaunchConfig} field name it populates, for source reporting. */
    private static final Map<String, String> KEY_TO_FIELD = buildKeyToField();

    private final boolean serverExecutable;
    private final Map<String, String> environment;

    /**
     * @param serverExecutable true when running as {@code syndicate-server}, which changes the
     *     defaults for {@code headless}, {@code autoStart}, and {@code adminConsole} (D03-S4.2) and
     *     the derived mode (D03-S5.2)
     * @param environment the environment to read, injected so the resolver is testable without
     *     mutating the real one
     */
    public LaunchConfigResolver(boolean serverExecutable, Map<String, String> environment) {
        this.serverExecutable = serverExecutable;
        this.environment = Map.copyOf(environment);
    }

    /**
     * Resolves the effective configuration.
     *
     * @throws ConfigException with {@link ExitCode#USAGE} for an unknown flag, an unknown config
     *     key, a bad value, or an unreadable config file (D03-E16)
     */
    public LaunchConfig resolve(String[] argv) {
        Map<String, String> values = new TreeMap<>();
        Map<String, ConfigSource> sources = new LinkedHashMap<>();

        Map<String, String> cli = parseCli(argv);

        // The config file path itself obeys precedence before anything it contains is read.
        Path configFile = resolveConfigFilePath(cli);
        if (configFile != null) {
            for (Map.Entry<String, String> entry : readConfigFile(configFile).entrySet()) {
                values.put(entry.getKey(), entry.getValue());
                sources.put(fieldFor(entry.getKey()), ConfigSource.CONFIG_FILE);
            }
        }
        for (Map.Entry<String, String> entry : ENV_KEYS.entrySet()) {
            String value = environment.get(entry.getValue());
            if (value != null && !value.isBlank()) {
                values.put(entry.getKey(), value);
                sources.put(fieldFor(entry.getKey()), ConfigSource.ENVIRONMENT);
            }
        }
        for (Map.Entry<String, String> entry : cli.entrySet()) {
            values.put(entry.getKey(), entry.getValue());
            sources.put(fieldFor(entry.getKey()), ConfigSource.CLI);
        }

        return build(values, sources, configFile);
    }

    private LaunchConfig build(Map<String, String> values, Map<String, ConfigSource> sources, Path configFile) {
        String serverHost = values.get("connect");

        RuntimeMode mode = resolveMode(values, serverHost);
        if (!sources.containsKey("mode")) {
            sources.put("mode", ConfigSource.DEFAULT);
        }

        boolean headless = bool(values, "headless", mode.isHeadless());
        int botCount = integer(
                values, "bots", mode == RuntimeMode.SINGLE_PLAYER ? LaunchConfig.DEFAULT_SINGLE_PLAYER_BOTS : 0);

        LaunchConfig config = new LaunchConfig(
                mode,
                headless,
                serverHost,
                integer(values, "port", LaunchConfig.DEFAULT_PORT),
                integer(values, "max-players", LaunchConfig.DEFAULT_MAX_PLAYERS),
                enumValue(values, "game-mode", GameMode.class, GameMode.DEATHMATCH),
                assetId(values, "arena", LaunchConfig.DEFAULT_ARENA),
                botCount,
                enumValue(values, "bot-difficulty", BotDifficulty.class, BotDifficulty.NORMAL),
                longValue(values, "seed", new Random().nextLong()),
                resolveAssetRoot(path(values, "assets", Path.of("assets"))),
                bool(values, "strict-assets", false),
                integer(values, "snapshot-rate", dev.syndicate.model.SimulationConstants.SNAPSHOT_RATE_HZ),
                values.getOrDefault("log-level", "INFO").toUpperCase(Locale.ROOT),
                values.containsKey("log-file") ? Path.of(values.get("log-file")) : null,
                bool(values, "vsync", true),
                integer(values, "max-fps", 0),
                integer(values, "width", 1600),
                integer(values, "height", 900),
                bool(values, "fullscreen", false),
                configFile,
                bool(values, "profile", false),
                bool(values, "deterministic", false),
                bool(values, "auto-start", serverExecutable),
                integer(values, "time-limit", 0),
                bool(values, "console", serverExecutable),
                sources);

        config.validateCombination();
        return config;
    }

    /** Mode selection of D03-S5.2. An explicit {@code --mode} always wins. */
    private RuntimeMode resolveMode(Map<String, String> values, String serverHost) {
        String explicit = values.get("mode");
        if (explicit != null) {
            return enumOf(RuntimeMode.class, explicit, "mode");
        }
        if (serverHost != null) {
            return RuntimeMode.LOCAL_CLIENT;
        }
        if (serverExecutable) {
            return RuntimeMode.DEDICATED_SERVER;
        }
        return RuntimeMode.SINGLE_PLAYER;
    }

    private Path resolveConfigFilePath(Map<String, String> cli) {
        String fromCli = cli.get("config");
        if (fromCli != null) {
            return requireReadable(Path.of(fromCli));
        }
        String fromEnv = environment.get("SYNDICATE_CONFIG");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return requireReadable(Path.of(fromEnv));
        }
        Path implicit = Path.of("syndicate.conf");
        return Files.isReadable(implicit) ? implicit : null;
    }

    private static Path requireReadable(Path path) {
        // D03-E16: a silently ignored config file is worse than a failure.
        if (!Files.isReadable(path)) {
            throw new ConfigException(ExitCode.USAGE, "config file not readable: " + path.toAbsolutePath());
        }
        return path;
    }

    /**
     * Parses CLI arguments. Repeated flags follow standard CLI semantics — last wins (D03-E18) —
     * which falls out of writing into a map.
     */
    private static Map<String, String> parseCli(String[] argv) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (!arg.startsWith("--")) {
                throw new ConfigException(ExitCode.USAGE, "unexpected argument '" + arg + "'; flags start with --");
            }
            String key = arg.substring(2);
            String inlineValue = null;
            int equals = key.indexOf('=');
            if (equals >= 0) {
                inlineValue = key.substring(equals + 1);
                key = key.substring(0, equals);
            }
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigException(ExitCode.USAGE, "unknown flag '--" + key + "'" + suggestion(key));
            }
            if (FLAG_KEYS.contains(key)) {
                parsed.put(key, inlineValue != null ? inlineValue : "true");
                continue;
            }
            if (inlineValue != null) {
                parsed.put(key, inlineValue);
                continue;
            }
            if (i + 1 >= argv.length) {
                throw new ConfigException(ExitCode.USAGE, "flag '--" + key + "' requires a value");
            }
            parsed.put(key, argv[++i]);
        }
        return parsed;
    }

    /** Reads the {@code key=value} config file of D03-S4.3. An unknown key is fatal (D03-R7). */
    private static Map<String, String> readConfigFile(Path file) {
        Map<String, String> parsed = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(ExitCode.USAGE, "cannot read config file " + file.toAbsolutePath(), e);
        } catch (UncheckedIOException e) {
            throw new ConfigException(ExitCode.USAGE, "cannot read config file " + file.toAbsolutePath(), e);
        }

        for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
            String line = lines.get(lineNumber - 1).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                throw new ConfigException(
                        ExitCode.USAGE, file + ":" + lineNumber + ": expected key=value, got '" + line + "'");
            }
            String key = line.substring(0, equals).trim();
            if (!KNOWN_KEYS.contains(key)) {
                throw new ConfigException(
                        ExitCode.USAGE,
                        file + ":" + lineNumber + ": unknown config key '" + key + "'" + suggestion(key));
            }
            parsed.put(key, line.substring(equals + 1).trim());
        }
        return parsed;
    }

    /** Names the closest known key, so a typo's fix is in the error rather than in the docs. */
    private static String suggestion(String key) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : KNOWN_KEYS) {
            int distance = editDistance(key, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= 3 ? "; did you mean '--" + best + "'?" : "";
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ---- Typed accessors; every parse failure is USAGE with the offending text ----

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> throw new ConfigException(ExitCode.USAGE, "'" + key + "' expects a boolean, got '" + raw + "'");
        };
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(ExitCode.USAGE, "'" + key + "' expects an integer, got '" + raw + "'", e);
        }
    }

    private static long longValue(Map<String, String> values, String key, long fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(ExitCode.USAGE, "'" + key + "' expects a long, got '" + raw + "'", e);
        }
    }

    private static Path path(Map<String, String> values, String key, Path fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : Path.of(raw);
    }

    private static AssetId assetId(Map<String, String> values, String key, String fallback) {
        String raw = values.getOrDefault(key, fallback);
        if (!AssetId.isValid(raw)) {
            throw new ConfigException(
                    ExitCode.USAGE, "'" + key + "' expects an asset id matching " + AssetId.PATTERN.pattern());
        }
        return AssetId.of(raw);
    }

    private static <E extends Enum<E>> E enumValue(Map<String, String> values, String key, Class<E> type, E fallback) {
        String raw = values.get(key);
        return raw == null ? fallback : enumOf(type, raw, key);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, String key) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(
                    ExitCode.USAGE,
                    "'" + key + "' expects one of " + java.util.Arrays.toString(type.getEnumConstants()) + ", got '"
                            + raw + "'",
                    e);
        }
    }

    private static String fieldFor(String key) {
        return KEY_TO_FIELD.getOrDefault(key, key);
    }

    private static Map<String, String> buildKeyToField() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("connect", "serverHost");
        map.put("port", "serverPort");
        map.put("max-players", "maxPlayers");
        map.put("game-mode", "gameMode");
        map.put("arena", "arenaId");
        map.put("bots", "botCount");
        map.put("bot-difficulty", "botDifficulty");
        map.put("seed", "matchSeed");
        map.put("assets", "assetRoot");
        map.put("strict-assets", "strictAssets");
        map.put("snapshot-rate", "snapshotRateHz");
        map.put("log-level", "logLevel");
        map.put("log-file", "logFile");
        map.put("max-fps", "maxFps");
        map.put("width", "windowWidth");
        map.put("height", "windowHeight");
        map.put("config", "configFile");
        map.put("deterministic", "deterministicMode");
        map.put("auto-start", "autoStart");
        map.put("time-limit", "timeLimitSeconds");
        map.put("console", "adminConsole");
        return Map.copyOf(map);
    }

    /**
     * The content directory, found beside the executable when it is not beside the shell.
     *
     * <p>The default is the <em>relative</em> path {@code assets}, which resolves against the
     * process working directory. That is right for {@code ./gradlew run} in a clone and wrong for
     * a packaged build: double-clicking an installed game gives it whatever working directory the
     * desktop happened to hand it, and the game would start, load nothing, and present an empty
     * garage — a content bug's symptoms for a path problem's cause.
     *
     * <p>So an unresolvable <b>relative</b> root is retried beside the running code, walking out of
     * the {@code app/} or {@code lib/} directory a packager puts jars in. Resolved <b>here</b>, once,
     * rather than at the point content is read: the loader is only one of six things that open a
     * file under this root, and fixing it alone produced a build that found its vehicles and then
     * could not find their meshes, their sounds or its own typeface.
     *
     * <p>An absolute root is never second-guessed. Somebody who passed {@code --assets D:\mods}
     * meant it, and silently loading different content than they named is worse than failing.
     */
    static Path resolveAssetRoot(Path configured) {
        if (configured == null || Files.isDirectory(configured) || configured.isAbsolute()) {
            return configured;
        }
        for (Path base : installationRoots()) {
            Path candidate = base.resolve(configured);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return configured;
    }

    /** Directories a packaged build might hold its content in, nearest first. */
    private static List<Path> installationRoots() {
        try {
            Path codeLocation = Path.of(LaunchConfigResolver.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath();
            Path jarDir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (jarDir == null) {
                return List.of();
            }
            Path parent = jarDir.getParent();
            return parent == null ? List.of(jarDir) : List.of(jarDir, parent);
        } catch (RuntimeException | java.net.URISyntaxException e) {
            // A class loader that cannot say where it loaded from just means this fallback has
            // nothing to offer, and the configured path stands.
            return List.of();
        }
    }
}
