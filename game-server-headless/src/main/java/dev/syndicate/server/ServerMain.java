/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.ConfigException;
import dev.syndicate.model.config.LaunchConfig;
import dev.syndicate.model.config.LaunchConfigResolver;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the dedicated authoritative server (docs/03_runtime_modes.md#D03-S5.1).
 *
 * <p>The startup sequence runs as far as the implemented subsystems reach: configuration and mode
 * validation (steps 1-2), Bullet initialisation (3), assets (5), world and schedule (6), and the
 * tick loop (9), followed by the teardown of D03-S5.6. Two steps are skipped, loudly rather than
 * silently: there is no transport to bind (7) and no {@code MatchFactory} or {@code BotFactory} to
 * bootstrap a match with (8). A server therefore starts, ticks a world at 60 Hz, and shuts down
 * cleanly — which is the difference between a simulation that runs in a test and one that runs as a
 * process.
 *
 * <p><b>No libGDX Application is created</b> (DEV-011). D03-S5.1 step 4 wraps the server in a
 * {@code HeadlessApplication} for {@code Gdx.files} and the app lifecycle. {@code Gdx.files} is set
 * up directly instead, and the lifecycle is {@link HeadlessLoop}, which D03-S5.4 specifies in more
 * detail than the backend's own loop could honour — two loops in one process would each think they
 * owned the tick rate.
 *
 * <p>Exit codes come from {@link ExitCode} and are never invented at a call site: D03-S4.4 assigns
 * one code per cause, and AC-D03-7 requires a test per code.
 */
public final class ServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.exit(run(args).code());
    }

    /** Runs the server and returns the exit code, so tests can assert it without forking. */
    public static ExitCode run(String[] args) {
        return run(args, 0L);
    }

    /**
     * Runs the server, stopping after {@code tickLimit} ticks.
     *
     * @param tickLimit {@code 0} to run until the process is asked to stop. A bounded run is what
     *     lets a test drive the whole startup sequence — natives, world, schedule, loop, teardown —
     *     and still terminate.
     */
    public static ExitCode run(String[] args, long tickLimit) {
        return run(args, tickLimit, System.getenv());
    }

    /**
     * Runs the server against an explicit environment.
     *
     * <p>D03-R5 puts the environment between the config file and the CLI flags in the precedence
     * chain, so a caller that does not choose its environment does not fully choose its
     * configuration. The process passes {@link System#getenv()}; a test passes what it means to test,
     * which is the difference between a test that asserts the non-strict asset path and a test that
     * asserts it <em>unless the machine happens to export {@code SYNDICATE_STRICT_ASSETS}</em>
     * (DISC-013).
     *
     * @param environment the environment variables to resolve from, never null
     */
    public static ExitCode run(String[] args, long tickLimit, Map<String, String> environment) {
        LaunchConfig config;
        try {
            config = new LaunchConfigResolver(true, environment).resolve(args);
        } catch (ConfigException e) {
            LOG.error("{}", e.getMessage());
            return e.exitCode();
        }

        // D03-R5: every effective value is logged with its source, so a misconfiguration is always
        // diagnosable from the log alone rather than by re-deriving the precedence by hand.
        LOG.info("effective configuration:");
        config.describeEffectiveValues().forEach(entry -> LOG.info("  {}", entry));

        try (ServerRuntime runtime = ServerRuntime.start(config)) {
            List<EntitySystem> systems = runtime.systems();
            if (systems.isEmpty()) {
                LOG.error("no systems in the {} schedule; refusing to run an empty loop", config.mode());
                return ExitCode.INTERNAL_ERROR;
            }
            HeadlessLoop loop = new HeadlessLoop(runtime.world(), () -> {}, tickLimit);
            if (tickLimit <= 0L) {
                // Only for an unbounded run. A bounded one ends on its own, and registering a hook
                // per call would accumulate them in a process that runs the server more than once.
                installShutdownHook(loop);
            }
            loop.start();
            return ExitCode.OK;
        } catch (ServerRuntime.StartupException e) {
            LOG.error("{}", e.getMessage(), e.getCause());
            return e.exitCode();
        } catch (RuntimeException e) {
            // D03-S4.4: an unhandled exception anywhere in startup or shutdown is 70, so a crash
            // never leaves an operator reading a stack trace to guess whether to retry.
            LOG.error("unhandled exception during startup or shutdown", e);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    /**
     * Stops the loop on SIGINT/SIGTERM so the teardown of D03-S5.6 runs.
     *
     * <p>Without it, {@code Ctrl-C} kills the JVM mid-tick and every Bullet native is freed by
     * process exit rather than in the constraints → bodies → shapes order D02-S5.7 requires — which
     * is harmless on the way out and hides exactly the leak {@code NativeResourceTracker} exists to
     * catch.
     */
    private static void installShutdownHook(HeadlessLoop loop) {
        Runtime.getRuntime().addShutdownHook(new Thread(loop::requestStop, "syndicate-shutdown"));
    }
}
