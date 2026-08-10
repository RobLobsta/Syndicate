/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.syndicate.core.component.PlayerInputComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The client input layer: a gamepad and a keyboard as peers, and a router that picks between them.
 *
 * <p>Every test here drives a fake device. That is the point of splitting the hardware read out of
 * the tuning: input feel is the thing in a driving game most worth iterating on and least testable
 * by inspection, and a design where trying a different steering ramp needs a human with a controller
 * is a design where nobody tries one.
 */
@Tag("unit")
class InputTest {

    private static final float DT = 1f / 60f;

    private final InputBindings bindings = InputBindings.defaults();

    // ---- Dead zones and curves -----------------------------------------------------------

    /** A resting stick reads as centred, or the car steers itself into a wall unattended. */
    @Test
    void aRestingStickIsCentred() {
        assertThat(InputBindings.applyDeadZone(0.1f, 0.18f)).isZero();
        assertThat(InputBindings.applyDeadZone(-0.1f, 0.18f)).isZero();
    }

    /**
     * The response is continuous across the dead zone's edge.
     *
     * <p>The half people leave out. Zeroing below the threshold without rescaling leaves a step —
     * the axis jumps from 0 to 0.18 the instant the stick crosses — and the car twitches.
     */
    @Test
    void theDeadZoneRescalesRatherThanStepping() {
        assertThat(InputBindings.applyDeadZone(0.181f, 0.18f)).isCloseTo(0f, within(0.01f));
        assertThat(InputBindings.applyDeadZone(1f, 0.18f)).isCloseTo(1f, within(1e-5f));
        assertThat(InputBindings.applyDeadZone(-1f, 0.18f)).isCloseTo(-1f, within(1e-5f));
    }

    /** The curve keeps the sign and makes small movements finer. */
    @Test
    void theCurvePreservesSignAndSoftensTheCentre() {
        assertThat(InputBindings.applyCurve(0.5f, 1.8f)).isLessThan(0.5f).isPositive();
        assertThat(InputBindings.applyCurve(-0.5f, 1.8f)).isGreaterThan(-0.5f).isNegative();
        assertThat(InputBindings.applyCurve(1f, 1.8f)).isCloseTo(1f, within(1e-5f));
    }

    // ---- Gamepad --------------------------------------------------------------------------

    /** Triggers are throttle and brake, analogue, so part throttle is expressible. */
    @Test
    void gamepadTriggersDriveAndBrake() {
        FakePad pad = new FakePad();
        pad.rightTrigger = 0.6f;
        pad.leftTrigger = 0.3f;
        GamepadSource source = new GamepadSource(pad, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        source.poll(input, DT);

        assertThat(input.throttle).isBetween(0.4f, 0.7f);
        assertThat(input.brake).isBetween(0.1f, 0.4f);
    }

    /** The aim stick is a rate: held over, the aim keeps turning. */
    @Test
    void theAimStickSweepsRatherThanSnapping() {
        FakePad pad = new FakePad();
        pad.rightStickX = 1f;
        GamepadSource source = new GamepadSource(pad, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        source.poll(input, DT);
        float afterOneFrame = input.aimYawRad;
        for (int i = 0; i < 10; i++) {
            source.poll(input, DT);
        }

        assertThat(afterOneFrame).isNotZero();
        assertThat(Math.abs(input.aimYawRad)).isGreaterThan(Math.abs(afterOneFrame));
    }

    /** Aim pitch is clamped, because past vertical the yaw stops meaning anything. */
    @Test
    void aimPitchIsClamped() {
        FakePad pad = new FakePad();
        pad.rightStickY = 1f;
        GamepadSource source = new GamepadSource(pad, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        for (int i = 0; i < 600; i++) {
            source.poll(input, DT);
        }

        assertThat(input.aimPitchRad).isLessThanOrEqualTo(GamepadSource.MAX_PITCH);
    }

    /** One bit per weapon group — the same mask a bot writes. */
    @Test
    void gamepadButtonsSetTheFireMask() {
        FakePad pad = new FakePad();
        pad.fire[0] = true;
        pad.fire[2] = true;
        GamepadSource source = new GamepadSource(pad, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        source.poll(input, DT);

        assertThat(input.fireMask).isEqualTo(0b0101);
    }

    /** An unplugged pad is not a candidate at all. */
    @Test
    void aDisconnectedPadIsUnavailable() {
        FakePad pad = new FakePad();
        pad.connected = false;
        assertThat(new GamepadSource(pad, bindings).isAvailable()).isFalse();
    }

    // ---- Keyboard --------------------------------------------------------------------------

    /**
     * Steering ramps rather than snapping.
     *
     * <p>The one thing that makes a keyboard driveable at speed. Full lock the instant a key goes
     * down turns every corner into a series of stabs the car never settles between.
     */
    @Test
    void keyboardSteeringRampsUp() {
        FakeDesk desk = new FakeDesk();
        desk.right = true;
        KeyboardMouseSource source = new KeyboardMouseSource(desk, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        source.poll(input, DT);
        float afterOneFrame = input.steer;
        for (int i = 0; i < 30; i++) {
            source.poll(input, DT);
        }

        assertThat(afterOneFrame).isPositive().isLessThan(0.2f);
        assertThat(input.steer).isGreaterThan(afterOneFrame);
    }

    /** Releasing recentres faster than holding builds. Letting go must feel immediate. */
    @Test
    void keyboardSteeringReturnsFasterThanItBuilds() {
        FakeDesk desk = new FakeDesk();
        KeyboardMouseSource source = new KeyboardMouseSource(desk, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        desk.right = true;
        for (int i = 0; i < 60; i++) {
            source.poll(input, DT);
        }
        float held = input.steer;

        desk.right = false;
        source.poll(input, DT);
        float releasedStep = held - input.steer;

        desk.right = true;
        source.reset();
        source.poll(input, DT);
        float appliedStep = input.steer;

        assertThat(releasedStep).isGreaterThan(appliedStep);
    }

    /** The mouse is already a rate, so it maps to an angular delta with no integration of position. */
    @Test
    void theMouseTurnsTheAim() {
        FakeDesk desk = new FakeDesk();
        desk.mouseDx = 100f;
        KeyboardMouseSource source = new KeyboardMouseSource(desk, bindings);
        PlayerInputComponent input = new PlayerInputComponent();

        source.poll(input, DT);

        assertThat(input.aimYawRad)
                .isCloseTo(100f * bindings.keyboardMouse().mouseSensitivityRadPerPixel(), within(1e-4f));
    }

    // ---- The router ---------------------------------------------------------------------------

    /** Nothing touched yet: no device is driving and the intent is zeroed rather than stale. */
    @Test
    void theRouterStartsWithNoDevice() {
        FakePad pad = new FakePad();
        FakeDesk desk = new FakeDesk();
        InputRouter router = new InputRouter(new GamepadSource(pad, bindings), new KeyboardMouseSource(desk, bindings));
        PlayerInputComponent input = new PlayerInputComponent();
        input.throttle = 0.9f;

        router.poll(input, DT);

        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.NONE);
        assertThat(input.throttle).isZero();
    }

    /** Touching a device makes it the active one, on the frame it was touched. */
    @Test
    void theFirstDeviceTouchedBecomesActive() {
        FakePad pad = new FakePad();
        FakeDesk desk = new FakeDesk();
        InputRouter router = new InputRouter(new GamepadSource(pad, bindings), new KeyboardMouseSource(desk, bindings));
        PlayerInputComponent input = new PlayerInputComponent();

        desk.forward = true;
        router.poll(input, DT);

        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.KEYBOARD_MOUSE);
        assertThat(input.throttle).isPositive();
    }

    /** Picking up a pad mid-game switches to it, once the keyboard has gone quiet. */
    @Test
    void pickingUpAPadTakesOverAfterTheKeyboardGoesQuiet() {
        FakePad pad = new FakePad();
        FakeDesk desk = new FakeDesk();
        InputRouter router = new InputRouter(new GamepadSource(pad, bindings), new KeyboardMouseSource(desk, bindings));
        PlayerInputComponent input = new PlayerInputComponent();

        desk.forward = true;
        router.poll(input, DT);
        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.KEYBOARD_MOUSE);

        desk.forward = false;
        pad.rightTrigger = 1f;
        // The quiet period has to elapse first: a hand resting on a keyboard must not steal
        // control from a pad mid-corner, and vice versa.
        router.poll(input, DT);
        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.KEYBOARD_MOUSE);

        for (int i = 0; i < 40; i++) {
            router.poll(input, DT);
        }
        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.GAMEPAD);
    }

    /** Stick noise below the switching threshold must not steal control. */
    @Test
    void stickJitterDoesNotStealControl() {
        FakePad pad = new FakePad();
        FakeDesk desk = new FakeDesk();
        InputRouter router = new InputRouter(new GamepadSource(pad, bindings), new KeyboardMouseSource(desk, bindings));
        PlayerInputComponent input = new PlayerInputComponent();

        desk.forward = true;
        router.poll(input, DT);
        desk.forward = false;
        // Just past the dead zone, nowhere near deliberate.
        pad.leftStickX = 0.20f;

        for (int i = 0; i < 120; i++) {
            router.poll(input, DT);
        }

        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.KEYBOARD_MOUSE);
    }

    /** An unplugged pad hands over immediately: there is nothing to be loyal to. */
    @Test
    void unpluggingTheActivePadHandsOverAtOnce() {
        FakePad pad = new FakePad();
        FakeDesk desk = new FakeDesk();
        InputRouter router = new InputRouter(new GamepadSource(pad, bindings), new KeyboardMouseSource(desk, bindings));
        PlayerInputComponent input = new PlayerInputComponent();

        pad.rightTrigger = 1f;
        router.poll(input, DT);
        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.GAMEPAD);

        pad.connected = false;
        desk.forward = true;
        router.poll(input, DT);
        router.poll(input, DT);

        assertThat(router.activeKind()).isEqualTo(InputDeviceKind.KEYBOARD_MOUSE);
    }

    // ---- Bindings content ----------------------------------------------------------------------

    /** The shipped file and the code defaults agree, or the fallback is a different game. */
    @Test
    void theShippedBindingsMatchTheDefaults() {
        Path assetRoot = repositoryRoot().resolve("assets");
        assumeTrue(Files.isRegularFile(assetRoot.resolve(InputBindings.ASSET_PATH)), "no bindings file");

        InputBindings loaded = InputBindings.load(assetRoot);

        assertThat(loaded.gamepad()).isEqualTo(InputBindings.defaults().gamepad());
        assertThat(loaded.keyboardMouse()).isEqualTo(InputBindings.defaults().keyboardMouse());
    }

    /** A missing file leaves a playable game, not a car that ignores the controls. */
    @Test
    void missingBindingsFallBackToTheDefaults() {
        InputBindings loaded = InputBindings.load(Path.of("does", "not", "exist"));
        assertThat(loaded.gamepad()).isEqualTo(InputBindings.defaults().gamepad());
    }

    // ---- Fakes ------------------------------------------------------------------------------------

    private static final class FakePad implements GamepadSource.State {
        boolean connected = true;
        float leftStickX;
        float rightStickX;
        float rightStickY;
        float rightTrigger;
        float leftTrigger;
        boolean handbrake;
        final boolean[] fire = new boolean[4];

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public float leftStickX() {
            return leftStickX;
        }

        @Override
        public float rightStickX() {
            return rightStickX;
        }

        @Override
        public float rightStickY() {
            return rightStickY;
        }

        @Override
        public float rightTrigger() {
            return rightTrigger;
        }

        @Override
        public float leftTrigger() {
            return leftTrigger;
        }

        @Override
        public boolean isFireHeld(int weaponGroup) {
            return weaponGroup < fire.length && fire[weaponGroup];
        }

        @Override
        public boolean isHandbrakeHeld() {
            return handbrake;
        }

        @Override
        public int weaponGroupCount() {
            return fire.length;
        }
    }

    private static final class FakeDesk implements KeyboardMouseSource.State {
        boolean forward;
        boolean reverse;
        boolean left;
        boolean right;
        boolean brake;
        float mouseDx;
        float mouseDy;
        final boolean[] fire = new boolean[4];

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public boolean isForwardHeld() {
            return forward;
        }

        @Override
        public boolean isReverseHeld() {
            return reverse;
        }

        @Override
        public boolean isLeftHeld() {
            return left;
        }

        @Override
        public boolean isRightHeld() {
            return right;
        }

        @Override
        public boolean isBrakeHeld() {
            return brake;
        }

        @Override
        public float mouseDeltaX() {
            return mouseDx;
        }

        @Override
        public float mouseDeltaY() {
            return mouseDy;
        }

        @Override
        public boolean isFireHeld(int weaponGroup) {
            return weaponGroup < fire.length && fire[weaponGroup];
        }

        @Override
        public int weaponGroupCount() {
            return fire.length;
        }
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("docs"))) {
            path = path.getParent();
        }
        return path == null ? Path.of("") : path;
    }
}
