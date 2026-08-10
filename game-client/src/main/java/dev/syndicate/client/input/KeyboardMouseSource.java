/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import dev.syndicate.core.component.PlayerInputComponent;
import java.util.Objects;

/**
 * Driving with a keyboard and mouse — an equal alternative, not a fallback.
 *
 * <p>A key is on or off, and the naive mapping of "held means full lock" is undriveable at speed:
 * every corner is a series of stabs and the car never settles. What makes a keyboard competitive is
 * that steering and throttle are <b>ramped</b> — held input approaches full over
 * {@code 1 / steerRampPerSec} seconds — and recentre faster than they build, so letting go feels
 * immediate while holding feels progressive. That asymmetry is the whole trick, and it is why the
 * two rates are separate numbers in {@link InputBindings}.
 *
 * <p>The mouse is the aim, and unlike a stick it is already a rate: mouse motion is a delta, so it
 * maps to an angular delta directly with no integration of a position.
 *
 * <p>The device is read through {@link State} so the ramps can be tested by stepping a fake
 * keyboard, which is the only way to assert on behaviour that only exists over time.
 */
public final class KeyboardMouseSource implements InputSource {

    /** The raw device, abstracted to what this class reads. */
    public interface State {

        /** Whether a keyboard is usable — false only in a headless or test context. */
        boolean isPresent();

        /** Accelerate. */
        boolean isForwardHeld();

        /** Reverse, and brake when moving forward. */
        boolean isReverseHeld();

        boolean isLeftHeld();

        boolean isRightHeld();

        /** Handbrake. */
        boolean isBrakeHeld();

        /** Mouse movement since the last frame, in pixels, right positive. */
        float mouseDeltaX();

        /** Mouse movement since the last frame, in pixels, down positive. */
        float mouseDeltaY();

        /** Whether the button bound to a weapon group is held. */
        boolean isFireHeld(int weaponGroup);

        /** How many weapon groups are bound. */
        int weaponGroupCount();
    }

    private final State state;
    private final InputBindings bindings;

    private float steer;
    private float throttle;
    private float aimYawRad;
    private float aimPitchRad;

    public KeyboardMouseSource(State state, InputBindings bindings) {
        this.state = Objects.requireNonNull(state, "state");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    @Override
    public InputDeviceKind kind() {
        return InputDeviceKind.KEYBOARD_MOUSE;
    }

    @Override
    public boolean isAvailable() {
        return state.isPresent();
    }

    @Override
    public float poll(PlayerInputComponent out, float dtSeconds) {
        InputBindings.KeyboardMouse tuning = bindings.keyboardMouse();

        int steerTarget = (state.isRightHeld() ? 1 : 0) - (state.isLeftHeld() ? 1 : 0);
        steer = approach(
                steer,
                steerTarget,
                // Building and returning at different rates is what makes a keyboard driveable:
                // progressive to apply, immediate to release.
                steerTarget == 0 ? tuning.steerReturnPerSec() : tuning.steerRampPerSec(),
                dtSeconds);

        int throttleTarget = (state.isForwardHeld() ? 1 : 0) - (state.isReverseHeld() ? 1 : 0);
        throttle = approach(throttle, throttleTarget, tuning.throttleRampPerSec(), dtSeconds);

        out.steer = steer;
        out.throttle = throttle;
        out.brake = state.isBrakeHeld() ? 1f : 0f;

        float deltaX = state.mouseDeltaX();
        float deltaY = state.mouseDeltaY();
        if (tuning.invertAimY()) {
            deltaY = -deltaY;
        }
        aimYawRad = normalise(aimYawRad + deltaX * tuning.mouseSensitivityRadPerPixel());
        aimPitchRad = clamp(
                aimPitchRad - deltaY * tuning.mouseSensitivityRadPerPixel(),
                GamepadSource.MIN_PITCH,
                GamepadSource.MAX_PITCH);
        out.aimYawRad = aimYawRad;
        out.aimPitchRad = aimPitchRad;

        int fireMask = 0;
        for (int group = 0; group < state.weaponGroupCount(); group++) {
            if (state.isFireHeld(group)) {
                fireMask |= 1 << group;
            }
        }
        out.fireMask = fireMask;

        return activity(steerTarget, throttleTarget, deltaX, deltaY, fireMask, state.isBrakeHeld());
    }

    /**
     * How much the player is doing.
     *
     * <p>Measured from the <em>keys</em>, not from the ramped axes. A player who has just pressed a
     * key has full intent and a nearly-zero axis, and a router that read the axis would refuse to
     * switch to the keyboard until the ramp had run — which is a second of a dead keyboard every
     * time somebody puts a pad down.
     */
    private static float activity(
            int steerTarget, int throttleTarget, float mouseX, float mouseY, int fireMask, boolean brake) {
        if (steerTarget != 0 || throttleTarget != 0 || fireMask != 0 || brake) {
            return 1f;
        }
        // A mouse on a desk picks up a pixel from a passing lorry; several pixels is a hand.
        return Math.min(1f, (Math.abs(mouseX) + Math.abs(mouseY)) / MOUSE_ACTIVITY_PIXELS);
    }

    /** Pixels of mouse movement in one frame that count as full deliberate activity. */
    public static final float MOUSE_ACTIVITY_PIXELS = 12f;

    @Override
    public void reset() {
        steer = 0f;
        throttle = 0f;
        aimYawRad = 0f;
        aimPitchRad = 0f;
    }

    /** Moves a value toward a target at a rate, without overshooting. */
    private static float approach(float current, float target, float ratePerSecond, float dtSeconds) {
        float step = ratePerSecond * dtSeconds;
        float delta = target - current;
        if (Math.abs(delta) <= step) {
            return target;
        }
        return current + Math.signum(delta) * step;
    }

    private static float normalise(float radians) {
        return (float) Math.IEEEremainder(radians, Math.PI * 2.0);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
