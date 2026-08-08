/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.ConfigException;
import dev.syndicate.model.config.LaunchConfig;
import dev.syndicate.model.config.LaunchConfigResolver;
import java.awt.GraphicsEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the game client (docs/03_runtime_modes.md#D03-S5.1).
 *
 * <p>The client hosts three of the four runtime modes — {@code LOCAL_CLIENT}, {@code SINGLE_PLAYER},
 * and {@code HOSTED_MULTIPLAYER} — from one binary and one code path (D03-R3). Single-player is a
 * listen server with zero remote peers over a loopback transport, which is what guarantees that
 * playing alone exercises the same replication logic multiplayer depends on (D02-R19).
 *
 * <p>Implements steps 1 and 2 of the startup sequence, including the display check that produces
 * {@link ExitCode#MODE_UNAVAILABLE}. Rendering, the world, and the client loop arrive with the
 * systems they drive.
 */
public final class ClientMain {

    private static final Logger LOG = LoggerFactory.getLogger(ClientMain.class);

    private ClientMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.exit(run(args).code());
    }

    /** Runs the bootstrap and returns the exit code, so tests can assert it without forking. */
    public static ExitCode run(String[] args) {
        LaunchConfig config;
        try {
            config = new LaunchConfigResolver(false, System.getenv()).resolve(args);
        } catch (ConfigException e) {
            LOG.error("{}", e.getMessage());
            return e.exitCode();
        }

        LOG.info("effective configuration:");
        config.describeEffectiveValues().forEach(entry -> LOG.info("  {}", entry));

        // D03-E1: a rendering mode with no display exits with guidance rather than a stack trace
        // from deep inside GLFW, which is what a user would otherwise see on a headless host.
        if (config.mode().requiresDisplay() && GraphicsEnvironment.isHeadless()) {
            LOG.error("no display available; use --headless or --mode DEDICATED_SERVER");
            return ExitCode.MODE_UNAVAILABLE;
        }

        LOG.error("client bootstrap beyond configuration is not implemented yet "
                + "(docs/03_runtime_modes.md#D03-S5.1 steps 3-9); see .agent-memory/progress/");
        return ExitCode.INTERNAL_ERROR;
    }
}
