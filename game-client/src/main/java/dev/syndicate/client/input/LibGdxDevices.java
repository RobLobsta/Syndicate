/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;

/**
 * The two device states, backed by real hardware.
 *
 * <p>Everything that talks to libGDX or to gdx-controllers is in this one file, and it contains no
 * decisions: dead zones, curves, ramps and the mapping to intent all live in {@link GamepadSource}
 * and {@link KeyboardMouseSource}, which is what lets those be tested without a pad plugged in or a
 * window open.
 *
 * <p>The split is not ceremony. Input feel is the thing in a driving game most worth iterating on
 * and least testable by inspection, and a version where the tuning is entangled with the hardware
 * read is a version where every experiment needs a human with a controller.
 */
public final class LibGdxDevices {

    private LibGdxDevices() {
        throw new AssertionError("no instances");
    }

    /** How many weapon groups the shipped bindings map buttons to. */
    public static final int WEAPON_GROUPS = 4;

    /**
     * A gamepad, through gdx-controllers.
     *
     * <p>The first connected pad wins. Local multiplayer would need a pad per player and a way to
     * say which is whose; there is no local multiplayer, and inventing the assignment now would be
     * inventing a UI for it too.
     */
    public static final class Pad implements GamepadSource.State {

        private final int rightTriggerAxis;
        private final int leftTriggerAxis;

        /** A pad whose triggers are read as buttons, which every backend supports. */
        public Pad() {
            this(-1, -1);
        }

        /**
         * A pad with backend-specific analogue trigger axes.
         *
         * @param rightTriggerAxis the axis index, or -1 to use the digital button
         * @param leftTriggerAxis the same for the left trigger
         */
        public Pad(int rightTriggerAxis, int leftTriggerAxis) {
            this.rightTriggerAxis = rightTriggerAxis;
            this.leftTriggerAxis = leftTriggerAxis;
        }

        private Controller controller() {
            if (Controllers.getControllers().size == 0) {
                return null;
            }
            return Controllers.getControllers().first();
        }

        @Override
        public boolean isConnected() {
            return controller() != null;
        }

        @Override
        public float leftStickX() {
            Controller pad = controller();
            return pad == null ? 0f : pad.getAxis(pad.getMapping().axisLeftX);
        }

        @Override
        public float rightStickX() {
            Controller pad = controller();
            return pad == null ? 0f : pad.getAxis(pad.getMapping().axisRightX);
        }

        @Override
        public float rightStickY() {
            Controller pad = controller();
            // Negated: gdx-controllers reports a stick pushed forward as negative, and every
            // caller here works in "up is positive".
            return pad == null ? 0f : -pad.getAxis(pad.getMapping().axisRightY);
        }

        @Override
        public float rightTrigger() {
            return trigger(rightTriggerAxis, mappingOf() == null ? -1 : mappingOf().buttonR2);
        }

        @Override
        public float leftTrigger() {
            return trigger(leftTriggerAxis, mappingOf() == null ? -1 : mappingOf().buttonL2);
        }

        /**
         * A trigger, analogue where the backend offers it and digital where it does not.
         *
         * <p>gdx-controllers 2.x's portable {@code ControllerMapping} has no trigger axes: it maps
         * both triggers to {@code buttonL2}/{@code buttonR2}, which are on-or-off. Analogue triggers
         * do exist on the desktop backend, at axis indices that vary by pad and platform — so the
         * index is content ({@code analogueTriggerAxisRight} in {@code bindings.json}) rather than a
         * constant this file guesses, and it defaults to the digital button.
         *
         * <p>The distinction is not cosmetic. An on/off throttle on a pad is precisely what the
         * analogue mapping exists to avoid, and a player who can hold three-quarter throttle through
         * a corner is the reason a pad is a first-class device here rather than a concession.
         */
        private float trigger(int axisIndex, int buttonIndex) {
            Controller pad = controller();
            if (pad == null) {
                return 0f;
            }
            if (axisIndex >= 0) {
                float axis = pad.getAxis(axisIndex);
                if (axis != 0f) {
                    // Some backends rest a trigger at -1 and others at 0; taking the magnitude
                    // reads both as "not pressed" at rest without a per-backend branch.
                    return Math.min(1f, Math.abs(axis));
                }
            }
            return buttonIndex >= 0 && pad.getButton(buttonIndex) ? 1f : 0f;
        }

        private com.badlogic.gdx.controllers.ControllerMapping mappingOf() {
            Controller pad = controller();
            return pad == null ? null : pad.getMapping();
        }

        @Override
        public boolean isFireHeld(int weaponGroup) {
            Controller pad = controller();
            if (pad == null) {
                return false;
            }
            var mapping = pad.getMapping();
            return switch (weaponGroup) {
                case 0 -> pad.getButton(mapping.buttonR1);
                case 1 -> pad.getButton(mapping.buttonL1);
                case 2 -> pad.getButton(mapping.buttonA);
                case 3 -> pad.getButton(mapping.buttonX);
                default -> false;
            };
        }

        @Override
        public boolean isHandbrakeHeld() {
            Controller pad = controller();
            return pad != null && pad.getButton(pad.getMapping().buttonB);
        }

        @Override
        public int weaponGroupCount() {
            return WEAPON_GROUPS;
        }
    }

    /**
     * A keyboard and mouse, through {@code Gdx.input}.
     *
     * <p>WASD and the arrow keys are both bound, because both are what people reach for and there is
     * no reason to make anybody discover which one this game chose.
     */
    public static final class Desk implements KeyboardMouseSource.State {

        @Override
        public boolean isPresent() {
            return Gdx.input != null;
        }

        @Override
        public boolean isForwardHeld() {
            return held(Input.Keys.W) || held(Input.Keys.UP);
        }

        @Override
        public boolean isReverseHeld() {
            return held(Input.Keys.S) || held(Input.Keys.DOWN);
        }

        @Override
        public boolean isLeftHeld() {
            return held(Input.Keys.A) || held(Input.Keys.LEFT);
        }

        @Override
        public boolean isRightHeld() {
            return held(Input.Keys.D) || held(Input.Keys.RIGHT);
        }

        @Override
        public boolean isBrakeHeld() {
            return held(Input.Keys.SPACE);
        }

        @Override
        public float mouseDeltaX() {
            return Gdx.input == null ? 0f : Gdx.input.getDeltaX();
        }

        @Override
        public float mouseDeltaY() {
            return Gdx.input == null ? 0f : Gdx.input.getDeltaY();
        }

        @Override
        public boolean isFireHeld(int weaponGroup) {
            if (Gdx.input == null) {
                return false;
            }
            return switch (weaponGroup) {
                case 0 -> Gdx.input.isButtonPressed(Input.Buttons.LEFT);
                case 1 -> Gdx.input.isButtonPressed(Input.Buttons.RIGHT);
                case 2 -> held(Input.Keys.NUM_1);
                case 3 -> held(Input.Keys.NUM_2);
                default -> false;
            };
        }

        @Override
        public int weaponGroupCount() {
            return WEAPON_GROUPS;
        }

        private static boolean held(int key) {
            return Gdx.input != null && Gdx.input.isKeyPressed(key);
        }
    }
}
