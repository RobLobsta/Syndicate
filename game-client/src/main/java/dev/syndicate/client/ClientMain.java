/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import dev.syndicate.client.shell.ScreenId;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.ConfigException;
import dev.syndicate.model.config.LaunchConfig;
import dev.syndicate.model.config.LaunchConfigResolver;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Steps 1 and 2 of the startup sequence happen here, because they are the ones that can still
 * fail with a diagnosis and an exit code. Everything from step 3 needs a GL context and lives in
 * {@link SyndicateApplicationListener}.
 *
 * <p>Three arguments beyond {@link LaunchConfig}'s. {@code --capture FILE} and {@code
 * --capture-frame N} run the real client for N frames, write a PNG and exit — that is how a machine
 * with no display (CI, and this project's own sandbox under {@code xvfb-run}) verifies that what was
 * built actually draws. {@code --start-screen ID} opens on a named screen, which is what lets a
 * capture photograph the garage without a human navigating to it.
 */
public final class ClientMain {

    private static final Logger LOG = LoggerFactory.getLogger(ClientMain.class);

    /** Frames to run before a capture when {@code --capture-frame} is not given. */
    public static final int DEFAULT_CAPTURE_FRAME = 120;

    private ClientMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.exit(run(args).code());
    }

    /** Runs the client and returns the exit code, so tests can assert it without forking. */
    public static ExitCode run(String[] args) {
        List<String> remaining = new ArrayList<>();
        Path capturePath = null;
        int captureFrame = DEFAULT_CAPTURE_FRAME;
        ScreenId startScreen = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--capture" -> capturePath = Path.of(args[++i]);
                case "--capture-frame" -> captureFrame = Math.max(1, Integer.parseInt(args[++i]));
                case "--start-screen" -> startScreen = ScreenId.parse(args[++i]);
                default -> remaining.add(args[i]);
            }
        }

        LaunchConfig config;
        try {
            config = new LaunchConfigResolver(false, System.getenv()).resolve(remaining.toArray(new String[0]));
            config.validateCombination();
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

        // `--auto-start` means "I have already made the choices a menu would ask for" — it is the
        // flag D03-S4.2 gives for a lobby that does not wait, and skipping the menu is the same
        // statement one screen earlier. Every headless capture and every CI run takes this path.
        ScreenId resolvedStart =
                startScreen != null ? startScreen : config.autoStart() ? ScreenId.MATCH : ScreenId.MAIN_MENU;
        LOG.info("opening on {}", resolvedStart);

        SyndicateApplicationListener listener =
                new SyndicateApplicationListener(config, capturePath, captureFrame, resolvedStart);
        try {
            new Lwjgl3Application(listener, lwjgl3Config(config));
        } catch (RuntimeException e) {
            LOG.error("the application could not be created", e);
            return ExitCode.MODE_UNAVAILABLE;
        }
        return listener.exitCode();
    }

    /** The window, from the display half of D03-S4.2's configuration. */
    private static Lwjgl3ApplicationConfiguration lwjgl3Config(LaunchConfig config) {
        Lwjgl3ApplicationConfiguration application = new Lwjgl3ApplicationConfiguration();
        application.setTitle("Syndicate");
        if (config.fullscreen()) {
            application.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
        } else {
            application.setWindowedMode(config.windowWidth(), config.windowHeight());
        }
        application.useVsync(config.vsync());
        // Zero means "as fast as the machine will go", which is what a maxFps of 0 asks for. The
        // fixed-timestep loop makes an uncapped frame rate harmless to the simulation (D03-R10).
        application.setForegroundFPS(config.maxFps());
        // 24-bit depth: the arena is hundreds of metres across and the cars are centimetres thick,
        // and 16 bits z-fights across that range badly enough to be visible on a bonnet.
        application.setBackBufferConfig(8, 8, 8, 8, 24, 0, 0);
        return application;
    }
}
