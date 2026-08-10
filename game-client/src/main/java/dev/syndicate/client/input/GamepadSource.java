/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import dev.syndicate.core.component.PlayerInputComponent;
import java.util.Objects;

/**
 * Driving with a gamepad, treated as the device the game was built around.
 *
 * <p>The mapping is the one every driving game converges on because it is the one that works:
 *
 * <ul>
 *   <li><b>Right trigger is throttle, left trigger is brake.</b> Analogue, so a player can hold
 *       three-quarter throttle through a corner — which is the single largest advantage a pad has
 *       over a keyboard and the reason a pad is not a downgrade here.
 *   <li><b>Left stick X is steering</b>, through a response curve rather than raw. Full lock is a
 *       rare input and the first ten degrees are most of the driving, so an exponent above 1 buys
 *       precision exactly where it is spent.
 *   <li><b>Right stick is aim</b>, as a <em>rate</em>. A stick reports a position and aiming needs a
 *       velocity; treating the position as an absolute angle would make the reticle snap back to
 *       centre whenever the player let go.
 *   <li><b>Shoulders and face buttons fire</b>, one weapon group per bit, which is the same
 *       {@code fireMask} a human's keyboard and a bot's decision both write.
 * </ul>
 *
 * <p>The device is read through {@link State} rather than through libGDX's {@code Controller}
 * directly, so every line below can be tested without a physical pad plugged into the machine
 * running the suite. {@link LibGdxDevices.Pad} is the implementation that talks to hardware.
 */
public final class GamepadSource implements InputSource {

    /**
     * The raw device, abstracted to what this class actually reads.
     *
     * <p>Axes are {@code [-1,1]} and triggers {@code [0,1]}, which is what every backend normalises
     * to — so an implementation for a different backend is a mapping of indices, not a rewrite.
     */
    public interface State {

        /** Whether a pad is connected. */
        boolean isConnected();

        /** Left stick, {@code [-1,1]}, right positive. */
        float leftStickX();

        /** Right stick horizontal, {@code [-1,1]}, right positive. */
        float rightStickX();

        /** Right stick vertical, {@code [-1,1]}, up positive. */
        float rightStickY();

        /** Right trigger, {@code [0,1]}. */
        float rightTrigger();

        /** Left trigger, {@code [0,1]}. */
        float leftTrigger();

        /** Whether the button for a weapon group is held. */
        boolean isFireHeld(int weaponGroup);

        /** Whether the handbrake button is held. */
        boolean isHandbrakeHeld();

        /** How many weapon groups this pad has buttons for. */
        int weaponGroupCount();
    }

    private final State state;
    private final InputBindings bindings;

    private float aimYawRad;
    private float aimPitchRad;

    public GamepadSource(State state, InputBindings bindings) {
        this.state = Objects.requireNonNull(state, "state");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    @Override
    public InputDeviceKind kind() {
        return InputDeviceKind.GAMEPAD;
    }

    @Override
    public boolean isAvailable() {
        return state.isConnected();
    }

    @Override
    public float poll(PlayerInputComponent out, float dtSeconds) {
        InputBindings.Gamepad tuning = bindings.gamepad();

        float steerRaw = InputBindings.applyDeadZone(state.leftStickX(), tuning.stickDeadZone());
        float throttleRaw = InputBindings.applyDeadZone(state.rightTrigger(), tuning.triggerDeadZone());
        float brakeRaw = InputBindings.applyDeadZone(state.leftTrigger(), tuning.triggerDeadZone());

        out.steer = InputBindings.applyCurve(steerRaw, tuning.steerExponent());
        out.throttle = throttleRaw;
        // The handbrake and the brake trigger are the same channel as far as intent goes: the
        // simulation has one brake, and a player who wants to stop does not care which control
        // they used.
        out.brake = Math.max(brakeRaw, state.isHandbrakeHeld() ? 1f : 0f);

        float aimX = InputBindings.applyCurve(
                InputBindings.applyDeadZone(state.rightStickX(), tuning.stickDeadZone()), tuning.aimExponent());
        float aimY = InputBindings.applyCurve(
                InputBindings.applyDeadZone(state.rightStickY(), tuning.stickDeadZone()), tuning.aimExponent());
        if (tuning.invertAimY()) {
            aimY = -aimY;
        }
        aimYawRad = normalise(aimYawRad + aimX * tuning.aimRateRadPerSec() * dtSeconds);
        aimPitchRad = clamp(aimPitchRad + aimY * tuning.aimRateRadPerSec() * dtSeconds, MIN_PITCH, MAX_PITCH);
        out.aimYawRad = aimYawRad;
        out.aimPitchRad = aimPitchRad;

        int fireMask = 0;
        for (int group = 0; group < state.weaponGroupCount(); group++) {
            if (state.isFireHeld(group)) {
                fireMask |= 1 << group;
            }
        }
        out.fireMask = fireMask;

        return activity(steerRaw, throttleRaw, brakeRaw, aimX, aimY, fireMask);
    }

    /**
     * How much the player is doing, for the router's switching decision.
     *
     * <p>The maximum of the axes rather than their sum: a player holding throttle and steering is
     * not "twice as active" as one only steering, and summing would make a pad with a slightly
     * drifting stick permanently outrank a keyboard being typed on.
     */
    private static float activity(float steer, float throttle, float brake, float aimX, float aimY, int fireMask) {
        float most = Math.max(Math.abs(steer), Math.max(throttle, brake));
        most = Math.max(most, Math.max(Math.abs(aimX), Math.abs(aimY)));
        return fireMask != 0 ? Math.max(most, 1f) : most;
    }

    @Override
    public void reset() {
        aimYawRad = 0f;
        aimPitchRad = 0f;
    }

    /** How far up a player may aim. Beyond vertical the yaw becomes meaningless. */
    public static final float MAX_PITCH = 1.4f;

    /** How far down. */
    public static final float MIN_PITCH = -1.4f;

    private static float normalise(float radians) {
        return (float) Math.IEEEremainder(radians, Math.PI * 2.0);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
