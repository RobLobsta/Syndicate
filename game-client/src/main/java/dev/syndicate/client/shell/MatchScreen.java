/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import dev.syndicate.client.ClientLoop;
import dev.syndicate.client.ClientRuntime;
import dev.syndicate.client.debug.DebugConsole;
import dev.syndicate.client.debug.DebugOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The match: the only screen with a world in it (docs/03_runtime_modes.md#D03-S5.3).
 *
 * <p>Owns a {@link ClientRuntime} and a {@link ClientLoop} for exactly as long as the player is in
 * a match. Building the runtime here rather than at process start is what makes leaving to the menu
 * meaningful: {@link #dispose()} runs D03-S5.6's teardown in D02-S5.7's order, so the Bullet world,
 * every body and every shape from the finished match are gone before the next one is built. A
 * client that kept one runtime for the process would leak a whole physics world per match.
 *
 * <p>The loop itself is untouched — frame time still decides only how <em>many</em> fixed steps run
 * (G2), and this class adds nothing to that path but an escape key.
 */
public final class MatchScreen implements Screen {

    private static final Logger LOG = LoggerFactory.getLogger(MatchScreen.class);

    private final ClientRuntime runtime;
    private final ClientLoop loop = new ClientLoop();
    private final ScreenId exitTo;

    /**
     * The live testing console (` or F1).
     *
     * <p>Owned by the match rather than by the shell, because everything it does needs a world: a
     * console on the menu would have nothing to pause, spawn into or shoot.
     */
    private final DebugConsole console;

    private final DebugOverlay consoleOverlay = new DebugOverlay();

    private ScreenId next = ScreenId.MATCH;
    private boolean escapeWasDown = true;
    private boolean nightWasDown;

    /**
     * How dark it is, cycled with {@code N}: noon, dusk, midnight, and back (D15-R51).
     *
     * <p>A key rather than a clock, and deliberately temporary. Time of day is properly an arena's
     * property — D16-S4 reserves a {@code sky} block for it — and until that block does anything,
     * being able to see the headlights is worth more than being unable to.
     */
    private static final float[] NIGHT_STEPS = {0f, 0.55f, 1f};

    private int nightStep;

    /**
     * @param exitTo where leaving the match goes. {@link ScreenId#MAIN_MENU} when a menu launched
     *     it; {@link ScreenId#QUIT} when the launch skipped the menu, because backing out to a
     *     screen the player never saw would be a surprise.
     */
    public MatchScreen(ClientRuntime runtime, ScreenId exitTo) {
        this.runtime = runtime;
        this.exitTo = exitTo;
        this.console = new DebugConsole(runtime, loop, runtime.localPlayer());
    }

    /** The console, so a capture can drive it without a keyboard. */
    public DebugConsole console() {
        return console;
    }

    /** The runtime, so the application listener can capture what it drew. */
    public ClientRuntime runtime() {
        return runtime;
    }

    /** The loop, for the tick count a capture reports. */
    public ClientLoop loop() {
        return loop;
    }

    @Override
    public void render(float frameDeltaSeconds) {
        // Read before the world steps, so quitting is not delayed by the frame's simulation.
        boolean escapeDown = Gdx.input.isKeyPressed(Input.Keys.ESCAPE);
        if (escapeDown && !escapeWasDown) {
            LOG.info("leaving the match at tick {}", loop.tick());
            next = exitTo;
        }
        escapeWasDown = escapeDown;

        boolean nightDown = Gdx.input.isKeyPressed(Input.Keys.N);
        if (nightDown && !nightWasDown) {
            nightStep = (nightStep + 1) % NIGHT_STEPS.length;
            runtime.render().environment().setNightFraction(NIGHT_STEPS[nightStep]);
            LOG.info("night fraction is now {}", NIGHT_STEPS[nightStep]);
        }
        nightWasDown = nightDown;

        console.handleInput(frameDeltaSeconds);
        loop.advance(runtime.world(), frameDeltaSeconds);
        // After the step, so what slot 3 decided this tick is what gets held at neutral.
        console.applyHolds(runtime.world());
        // After the loop, so the overlay draws over the frame slot 26 has just finished.
        consoleOverlay.render(console);
    }

    @Override
    public ScreenId next() {
        return next;
    }

    @Override
    public void resize(int width, int height) {
        runtime.render().resize(width, height);
        consoleOverlay.resize(width, height);
    }

    @Override
    public void dispose() {
        // The overlay owns a batch, a shape renderer and a font, and it is disposed before the
        // runtime for the same reason D02-S5.7 orders the rest of the teardown: what borrows a
        // context goes before what holds it.
        consoleOverlay.dispose();
        runtime.close();
    }
}
