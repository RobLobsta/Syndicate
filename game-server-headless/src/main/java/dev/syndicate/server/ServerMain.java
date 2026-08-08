/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.server;

import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.ConfigException;
import dev.syndicate.model.config.LaunchConfig;
import dev.syndicate.model.config.LaunchConfigResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the dedicated authoritative server (docs/03_runtime_modes.md#D03-S5.1).
 *
 * <p>Implements steps 1 and 2 of the startup sequence: configuration resolution with the precedence
 * of D03-R5, and mode validation. Steps 3 onward — {@code Bullet.init}, asset loading, world and
 * system construction, transport binding, and the tick loop — arrive with the subsystems they
 * depend on; each is recorded as not-started in {@code .agent-memory/progress/}.
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

    /** Runs the bootstrap and returns the exit code, so tests can assert it without forking. */
    public static ExitCode run(String[] args) {
        LaunchConfig config;
        try {
            config = new LaunchConfigResolver(true, System.getenv()).resolve(args);
        } catch (ConfigException e) {
            LOG.error("{}", e.getMessage());
            return e.exitCode();
        }

        // D03-R5: every effective value is logged with its source, so a misconfiguration is always
        // diagnosable from the log alone rather than by re-deriving the precedence by hand.
        LOG.info("effective configuration:");
        config.describeEffectiveValues().forEach(entry -> LOG.info("  {}", entry));

        LOG.error("server bootstrap beyond configuration is not implemented yet "
                + "(docs/03_runtime_modes.md#D03-S5.1 steps 3-9); see .agent-memory/progress/");
        return ExitCode.INTERNAL_ERROR;
    }
}
