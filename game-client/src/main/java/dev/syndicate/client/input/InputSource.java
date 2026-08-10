/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import dev.syndicate.core.component.PlayerInputComponent;

/**
 * One way of driving: a gamepad, or a keyboard and mouse.
 *
 * <p>Both implementations are peers. A gamepad is not "controller support bolted onto a keyboard
 * game" and a keyboard is not "what you use until you find a pad" — each produces the same
 * {@link PlayerInputComponent} through its own idiom, and which one is live is decided by which the
 * player last touched (see {@link InputRouter}).
 *
 * <p>That symmetry is the whole design, and it has one consequence worth stating: neither
 * implementation may translate through the other. A keyboard source that produced a virtual stick
 * position and then ran it through the gamepad curve would inherit a dead zone it does not need and
 * lose the ramp it does.
 */
public interface InputSource {

    /** Which device this is, for the router's diagnostics and for a HUD prompt. */
    InputDeviceKind kind();

    /** Whether the device is present at all. A gamepad that is unplugged is not a candidate. */
    boolean isAvailable();

    /**
     * Reads the device and writes intent.
     *
     * @param out the component to fill; the caller owns it
     * @param dtSeconds the frame's elapsed time. Present because both sources integrate — a
     *     keyboard ramps its steering and a gamepad's aim stick is a velocity — and neither may
     *     read the clock itself
     * @return how much the player moved this device this frame, in an arbitrary but consistent
     *     unit. The router compares these to decide who is driving, so what matters is that a
     *     resting device returns zero and a touched one returns something
     */
    float poll(PlayerInputComponent out, float dtSeconds);

    /**
     * Forgets whatever the source was integrating.
     *
     * <p>Called when the router switches away. Without it a keyboard that was mid-ramp when the
     * player picked up a pad keeps its half-applied steering, and the car pulls to one side the
     * moment the pad is put down again.
     */
    void reset();
}
