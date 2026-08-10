/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

/**
 * Which kind of device is driving.
 *
 * <p>Read by the HUD, so a button prompt says the right thing, and by nothing in the simulation:
 * {@code PlayerInputComponent} carries intent and no downstream system may know how it was produced
 * (G17, and the same argument that makes a bot indistinguishable from a human).
 */
public enum InputDeviceKind {
    /** A gamepad: analogue triggers, two sticks. */
    GAMEPAD,

    /** A keyboard and mouse: digital keys ramped into axes, mouse movement as aim. */
    KEYBOARD_MOUSE,

    /** Nothing has been touched yet. The router starts here and leaves on the first input. */
    NONE
}
