/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import dev.syndicate.client.shell.GameShell;
import dev.syndicate.client.shell.MatchScreen;
import dev.syndicate.client.shell.ScreenId;
import dev.syndicate.model.ExitCode;
import dev.syndicate.model.config.LaunchConfig;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The libGDX application the client runs inside (docs/03_runtime_modes.md#D03-S5.1 steps 4-9).
 *
 * <p>Everything that needs a GL context is built in {@link #create()} and nothing before it, which
 * is the ordering D03-S5.1 fixes and the reason startup is split between {@code ClientMain} — which
 * resolves configuration and can still exit cleanly with a code — and this class.
 *
 * <p>A startup failure here cannot return an exit code through the libGDX lifecycle, so it is
 * recorded on {@link #exitCode()} and the application is asked to close. {@code ClientMain} reads it
 * afterwards. That indirection exists because the alternative — throwing out of {@code create} —
 * produces a GLFW stack trace instead of D03-S4.4's diagnosis.
 *
 * <p><b>Capture mode.</b> Given a capture path and a frame number, the listener writes a PNG and
 * exits. It is not a debug convenience: this project's sandbox has no display, and every visual
 * claim it has made since Phase 1 has been backed by a capture from the real thing running rather
 * than by an assertion about code that draws.
 */
public final class SyndicateApplicationListener implements ApplicationListener {

    private static final Logger LOG = LoggerFactory.getLogger(SyndicateApplicationListener.class);

    private final LaunchConfig config;
    private final Path capturePath;
    private final int captureFrame;
    private final ScreenId startScreen;

    private GameShell shell;
    private ExitCode exitCode = ExitCode.OK;
    private int frame;

    public SyndicateApplicationListener(LaunchConfig config) {
        this(config, null, -1, ScreenId.MAIN_MENU);
    }

    /**
     * @param capturePath where to write a PNG, or null to run interactively
     * @param captureFrame which frame to capture on; frames before it are simulated and drawn
     * @param startScreen which screen the window opens on
     */
    public SyndicateApplicationListener(LaunchConfig config, Path capturePath, int captureFrame, ScreenId startScreen) {
        this.config = config;
        this.capturePath = capturePath;
        this.captureFrame = captureFrame;
        this.startScreen = startScreen;
    }

    /** The code the process should exit with once the application has closed. */
    public ExitCode exitCode() {
        return exitCode;
    }

    @Override
    public void create() {
        try {
            shell = new GameShell(config, startScreen);
        } catch (ClientRuntime.StartupException e) {
            LOG.error("{}", e.getMessage(), e.getCause());
            exitCode = e.exitCode();
            Gdx.app.exit();
        } catch (RuntimeException e) {
            LOG.error("client startup failed", e);
            exitCode = ExitCode.INTERNAL_ERROR;
            Gdx.app.exit();
        }
    }

    @Override
    public void render() {
        if (shell == null) {
            return;
        }
        try {
            shell.render(Gdx.graphics.getDeltaTime());
        } catch (RuntimeException e) {
            // D03-S5.6: an unhandled exception in a system logs the screen and attempts a clean
            // shutdown rather than leaving Bullet's natives to a JVM abort.
            LOG.error("unhandled exception on screen {}", shell.currentScreenId(), e);
            exitCode = ExitCode.INTERNAL_ERROR;
            Gdx.app.exit();
            return;
        }
        if (shell.isQuitRequested()) {
            Gdx.app.exit();
            return;
        }
        frame++;
        if (capturePath != null && frame >= captureFrame) {
            capture();
            Gdx.app.exit();
        }
    }

    private void capture() {
        Pixmap pixmap = Pixmap.createFromFrameBuffer(
                0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Pixmap upright = flipVertically(pixmap);
        pixmap.dispose();
        PixmapIO.writePNG(Gdx.files.absolute(capturePath.toAbsolutePath().toString()), upright);
        upright.dispose();

        // A menu capture has no world to report on, so the scene detail is only added when there
        // is a match behind it. Reporting zeroes for a screen that has no ticks would read as a
        // simulation that stopped rather than one that was never started.
        if (shell.current() instanceof MatchScreen match) {
            LOG.info(
                    "captured frame {} to {} on {} at tick {}: {} models drawn, {} particle quads this "
                            + "frame (peak {} over the run), {} dropped ticks",
                    frame,
                    capturePath,
                    shell.currentScreenId(),
                    match.loop().tick(),
                    match.runtime().provider().renderSystem().drawnThisFrame(),
                    match.runtime().render().particles().quadCount(),
                    match.runtime().render().particles().peakQuadCount(),
                    match.loop().droppedTicks());
        } else {
            LOG.info("captured frame {} to {} on {}", frame, capturePath, shell.currentScreenId());
        }
    }

    /**
     * GL's framebuffer origin is bottom-left and a PNG's is top-left.
     *
     * <p>Without the flip every capture comes out mirrored, which on this scene puts the sky below
     * the floor and reads as a rendering bug rather than as a file-format one.
     */
    private static Pixmap flipVertically(Pixmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Pixmap out = new Pixmap(width, height, source.getFormat());
        out.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            out.drawPixmap(source, 0, y, 0, height - 1 - y, width, 1);
        }
        return out;
    }

    @Override
    public void resize(int width, int height) {
        if (shell != null) {
            shell.resize(width, height);
        }
    }

    @Override
    public void pause() {
        // Nothing: the simulation is not suspended by focus loss, and the accumulator's clamp
        // already handles the long frame that follows one.
    }

    @Override
    public void resume() {
        // See pause().
    }

    @Override
    public void dispose() {
        if (shell != null) {
            shell.dispose();
            shell = null;
        }
    }
}
