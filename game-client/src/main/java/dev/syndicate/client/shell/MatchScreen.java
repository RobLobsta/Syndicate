/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import dev.syndicate.client.ClientLoop;
import dev.syndicate.client.ClientRuntime;
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

    private ScreenId next = ScreenId.MATCH;
    private boolean escapeWasDown = true;

    /**
     * @param exitTo where leaving the match goes. {@link ScreenId#MAIN_MENU} when a menu launched
     *     it; {@link ScreenId#QUIT} when the launch skipped the menu, because backing out to a
     *     screen the player never saw would be a surprise.
     */
    public MatchScreen(ClientRuntime runtime, ScreenId exitTo) {
        this.runtime = runtime;
        this.exitTo = exitTo;
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

        loop.advance(runtime.world(), frameDeltaSeconds);
    }

    @Override
    public ScreenId next() {
        return next;
    }

    @Override
    public void resize(int width, int height) {
        runtime.render().resize(width, height);
    }

    @Override
    public void dispose() {
        runtime.close();
    }
}
