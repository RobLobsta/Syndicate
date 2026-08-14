/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.utils.Disposable;

/**
 * One thing the window can be showing (docs/03_runtime_modes.md#D03-S5.1 step 9).
 *
 * <p>A screen is asked to advance itself by real frame time and to say what should be showing
 * next. It never switches screens itself: {@link GameShell} owns the transition, so there is one
 * place where a screen is disposed and its successor constructed, and no screen holds a reference
 * to another.
 *
 * <p>Only {@link MatchScreen} runs simulation. The menu screens have no world, no physics and no
 * tick — which is why entering the game is where {@code ClientRuntime} is built and leaving it is
 * where that runtime is closed, rather than both happening once at process start.
 */
public interface Screen extends Disposable {

    /**
     * Advances and draws one frame.
     *
     * @param frameDeltaSeconds real elapsed time since the last frame — never simulation time; a
     *     screen that steps the world converts it through {@code ClientLoop} (D03-R10, G2)
     */
    void render(float frameDeltaSeconds);

    /** What should be showing after this frame. Returning its own id means "stay". */
    ScreenId next();

    /** Propagates a window resize. */
    default void resize(int width, int height) {}

    @Override
    default void dispose() {}
}
