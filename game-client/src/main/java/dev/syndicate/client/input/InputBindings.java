/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a device's raw state becomes driver intent — read from content, not compiled in.
 *
 * <p>Bindings are content for the same reason bot difficulty is (D11-R4): they are numbers a person
 * tunes by feel, and recompiling to try a different steering curve is the difference between tuning
 * a game and not tuning it. The defaults here are the shipped file's values, and a test asserts the
 * two agree — the code copy exists so a missing file leaves the game playable rather than leaving
 * every axis at zero.
 *
 * <p><b>Every parameter is per device kind, not global.</b> A gamepad stick and a keyboard key are
 * not the same instrument: one reports a continuous position that needs a dead zone and a response
 * curve, the other reports on or off and needs a ramp to become steering at all. Sharing one set of
 * numbers between them makes one of the two feel wrong, and which one depends on whose machine it
 * was tuned on.
 */
public final class InputBindings {

    private static final Logger LOG = LoggerFactory.getLogger(InputBindings.class);

    /** Where the shipped bindings live under the asset root. */
    public static final String ASSET_PATH = "input/bindings.json";

    /**
     * Gamepad tuning.
     *
     * @param stickDeadZone below this magnitude a stick reads as centred. Every real stick rests
     *     slightly off centre and would otherwise steer the car gently into a wall while nobody is
     *     touching it
     * @param triggerDeadZone the same for the analogue triggers
     * @param steerExponent response curve on the steer axis. Above 1 makes small movements finer,
     *     which is what a driving game wants: full lock is a rare input and the first ten degrees
     *     are most of the driving
     * @param aimExponent the same for the aim stick
     * @param aimRateRadPerSec how fast a fully deflected aim stick sweeps. A stick reports a
     *     position and aiming needs a velocity, so this is the conversion between them
     * @param invertAimY whether pulling down aims up
     * @param analogueTriggerAxisRight the backend axis index for the right trigger, or -1 to read it
     *     as a digital button. gdx-controllers 2.x's portable mapping has no trigger axes and the
     *     real indices vary by pad and platform, so this is content rather than a constant the code
     *     guesses — and the default is the digital button, which every backend supports
     * @param analogueTriggerAxisLeft the same for the left trigger
     */
    public record Gamepad(
            float stickDeadZone,
            float triggerDeadZone,
            float steerExponent,
            float aimExponent,
            float aimRateRadPerSec,
            boolean invertAimY,
            int analogueTriggerAxisRight,
            int analogueTriggerAxisLeft) {}

    /**
     * Keyboard and mouse tuning.
     *
     * @param steerRampPerSec how fast held steering reaches full lock. A key is on or off, and
     *     applying full lock the instant it goes down is undriveable at speed — this ramp is what
     *     makes a keyboard competitive with a stick rather than merely usable
     * @param steerReturnPerSec how fast steering recentres when nothing is held. Faster than the
     *     ramp, because letting go should feel immediate
     * @param throttleRampPerSec the same ramp for throttle
     * @param mouseSensitivityRadPerPixel how far the aim turns per pixel of mouse movement
     * @param invertAimY whether pushing the mouse forward aims down
     */
    public record KeyboardMouse(
            float steerRampPerSec,
            float steerReturnPerSec,
            float throttleRampPerSec,
            float mouseSensitivityRadPerPixel,
            boolean invertAimY) {}

    private final Gamepad gamepad;
    private final KeyboardMouse keyboardMouse;

    public InputBindings(Gamepad gamepad, KeyboardMouse keyboardMouse) {
        this.gamepad = gamepad == null ? defaults().gamepad() : gamepad;
        this.keyboardMouse = keyboardMouse == null ? defaults().keyboardMouse() : keyboardMouse;
    }

    public Gamepad gamepad() {
        return gamepad;
    }

    public KeyboardMouse keyboardMouse() {
        return keyboardMouse;
    }

    /**
     * The shipped values, in code.
     *
     * <p>Duplicated with the content file deliberately, and the duplication is checked: a bindings
     * file that fails to load must leave a playable game rather than a car that ignores the
     * controls, which is what an all-zero binding set would be.
     */
    public static InputBindings defaults() {
        return new InputBindings(
                new Gamepad(0.18f, 0.10f, 1.8f, 2.0f, 3.2f, false, -1, -1),
                new KeyboardMouse(2.6f, 6.0f, 3.0f, 0.0032f, false));
    }

    /** Reads {@code input/bindings.json}, falling back to {@link #defaults()}. */
    public static InputBindings load(Path assetRoot) {
        Path file = assetRoot.resolve(ASSET_PATH);
        if (!Files.isRegularFile(file)) {
            LOG.warn("{} is absent; using the built-in bindings", file);
            return defaults();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            InputBindings fallback = defaults();
            JsonNode pad = root.path("gamepad");
            JsonNode key = root.path("keyboardMouse");
            return new InputBindings(
                    new Gamepad(
                            (float) pad.path("stickDeadZone").asDouble(fallback.gamepad.stickDeadZone()),
                            (float) pad.path("triggerDeadZone").asDouble(fallback.gamepad.triggerDeadZone()),
                            (float) pad.path("steerExponent").asDouble(fallback.gamepad.steerExponent()),
                            (float) pad.path("aimExponent").asDouble(fallback.gamepad.aimExponent()),
                            (float) pad.path("aimRateRadPerSec").asDouble(fallback.gamepad.aimRateRadPerSec()),
                            pad.path("invertAimY").asBoolean(fallback.gamepad.invertAimY()),
                            pad.path("analogueTriggerAxisRight").asInt(fallback.gamepad.analogueTriggerAxisRight()),
                            pad.path("analogueTriggerAxisLeft").asInt(fallback.gamepad.analogueTriggerAxisLeft())),
                    new KeyboardMouse(
                            (float) key.path("steerRampPerSec").asDouble(fallback.keyboardMouse.steerRampPerSec()),
                            (float) key.path("steerReturnPerSec").asDouble(fallback.keyboardMouse.steerReturnPerSec()),
                            (float) key.path("throttleRampPerSec")
                                    .asDouble(fallback.keyboardMouse.throttleRampPerSec()),
                            (float) key.path("mouseSensitivityRadPerPixel")
                                    .asDouble(fallback.keyboardMouse.mouseSensitivityRadPerPixel()),
                            key.path("invertAimY").asBoolean(fallback.keyboardMouse.invertAimY())));
        } catch (IOException e) {
            LOG.error("{} could not be read; using the built-in bindings", file, e);
            return defaults();
        }
    }

    /**
     * Applies a dead zone and rescales what is left to the full range.
     *
     * <p>Rescaling matters and is the half people leave out. Simply zeroing below the threshold
     * leaves a step at the edge of the dead zone — the axis jumps from 0 to 0.18 the moment the
     * stick crosses it — and the car twitches. Rescaling makes the response continuous from rest.
     */
    public static float applyDeadZone(float value, float deadZone) {
        float magnitude = Math.abs(value);
        if (magnitude <= deadZone) {
            return 0f;
        }
        float scaled = (magnitude - deadZone) / Math.max(1e-6f, 1f - deadZone);
        return Math.signum(value) * Math.min(1f, scaled);
    }

    /** Applies a response curve, preserving sign. Exponents above 1 make small movements finer. */
    public static float applyCurve(float value, float exponent) {
        return Math.signum(value) * (float) Math.pow(Math.abs(value), exponent);
    }
}
