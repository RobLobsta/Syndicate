/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;

/**
 * Moving around a menu, from a keyboard or a pad, as discrete events.
 *
 * <p>A menu wants <em>presses</em>, not held state: the driving code reads "throttle is 0.6 right
 * now", and a list wants "the player asked for the next item, once". So every source here is
 * edge-triggered, and a held direction repeats on a timer the way every list in every game does —
 * an initial delay so a tap moves one row, then a faster rate so holding it scrolls.
 *
 * <p>Keyboard and pad are peers rather than one being a fallback, which is the same stance
 * {@code InputRouter} takes for driving (DEC-048). A player who selected their car with the stick
 * should not have to reach for the keyboard to confirm it.
 *
 * <p>Stateful, and one instance is shared by every screen through {@link MenuContext}: the repeat
 * timer has to survive a screen change, or holding <i>down</i> through a transition would fire a
 * fresh burst on the screen that just appeared.
 */
public final class MenuInput {

    /** Seconds a direction must be held before it starts repeating. */
    public static final float REPEAT_DELAY_S = 0.40f;

    /** Seconds between repeats once repeating has started. */
    public static final float REPEAT_INTERVAL_S = 0.09f;

    /** Stick deflection past which an axis counts as a direction press. */
    public static final float STICK_THRESHOLD = 0.55f;

    /** What a menu can be told to do. */
    public enum Action {
        NONE,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        CONFIRM,
        BACK
    }

    private Action held = Action.NONE;
    private float heldForSeconds;
    private boolean hasRepeated;
    private boolean confirmWasDown;
    private boolean backWasDown;

    /**
     * The action for this frame, or {@link Action#NONE}.
     *
     * <p>Call exactly once per frame per screen: it consumes edges, so a second call in the same
     * frame reports nothing.
     */
    public Action poll(float frameDeltaSeconds) {
        // Confirm and back are pure edges with no repeat. Holding <i>enter</i> down should not
        // launch a match and then immediately re-enter whatever the next screen's first item is,
        // which is exactly what a repeating confirm does on a menu that changes under it.
        boolean confirmDown = isConfirmDown();
        boolean confirmEdge = confirmDown && !confirmWasDown;
        confirmWasDown = confirmDown;

        boolean backDown = isBackDown();
        boolean backEdge = backDown && !backWasDown;
        backWasDown = backDown;

        if (confirmEdge) {
            return Action.CONFIRM;
        }
        if (backEdge) {
            return Action.BACK;
        }

        Action direction = currentDirection();
        if (direction == Action.NONE) {
            held = Action.NONE;
            heldForSeconds = 0f;
            hasRepeated = false;
            return Action.NONE;
        }
        if (direction != held) {
            held = direction;
            heldForSeconds = 0f;
            hasRepeated = false;
            return direction;
        }

        heldForSeconds += frameDeltaSeconds;
        float threshold = hasRepeated ? REPEAT_INTERVAL_S : REPEAT_DELAY_S;
        if (heldForSeconds >= threshold) {
            heldForSeconds = 0f;
            hasRepeated = true;
            return direction;
        }
        return Action.NONE;
    }

    /** Forgets every edge, so a screen that has just appeared does not inherit a stale press. */
    public void reset() {
        held = Action.NONE;
        heldForSeconds = 0f;
        hasRepeated = false;
        // Latched as *down* rather than up: whatever button caused the transition is probably still
        // physically held, and treating it as up would read a fresh edge on the next frame.
        confirmWasDown = true;
        backWasDown = true;
    }

    private Action currentDirection() {
        if (Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W)) {
            return Action.UP;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN) || Gdx.input.isKeyPressed(Input.Keys.S)) {
            return Action.DOWN;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)) {
            return Action.LEFT;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)) {
            return Action.RIGHT;
        }
        Controller pad = firstPad();
        if (pad != null) {
            float x = pad.getAxis(pad.getMapping().axisLeftX);
            float y = pad.getAxis(pad.getMapping().axisLeftY);
            if (pad.getButton(pad.getMapping().buttonDpadUp) || y < -STICK_THRESHOLD) {
                return Action.UP;
            }
            if (pad.getButton(pad.getMapping().buttonDpadDown) || y > STICK_THRESHOLD) {
                return Action.DOWN;
            }
            if (pad.getButton(pad.getMapping().buttonDpadLeft) || x < -STICK_THRESHOLD) {
                return Action.LEFT;
            }
            if (pad.getButton(pad.getMapping().buttonDpadRight) || x > STICK_THRESHOLD) {
                return Action.RIGHT;
            }
        }
        return Action.NONE;
    }

    private boolean isConfirmDown() {
        if (Gdx.input.isKeyPressed(Input.Keys.ENTER)
                || Gdx.input.isKeyPressed(Input.Keys.NUMPAD_ENTER)
                || Gdx.input.isKeyPressed(Input.Keys.SPACE)) {
            return true;
        }
        Controller pad = firstPad();
        return pad != null && pad.getButton(pad.getMapping().buttonA);
    }

    private boolean isBackDown() {
        if (Gdx.input.isKeyPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyPressed(Input.Keys.BACKSPACE)) {
            return true;
        }
        Controller pad = firstPad();
        return pad != null && pad.getButton(pad.getMapping().buttonB);
    }

    /**
     * The first connected pad, or null.
     *
     * <p>Wrapped because {@code Controllers.getCurrent()} throws when the controller manager has
     * not been created — which is the case in a headless capture, and a menu that crashes on a
     * machine with no pad is worse than one that only reads the keyboard there.
     */
    private static Controller firstPad() {
        try {
            Controller pad = Controllers.getCurrent();
            return pad != null && pad.getMapping() != null ? pad : null;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
