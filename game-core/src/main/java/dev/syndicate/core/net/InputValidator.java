/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.model.SimulationConstants;
import dev.syndicate.model.net.NetConstants;

/**
 * The trust boundary, in one place (docs/10_networking_multiplayer.md#D10-S5.9).
 *
 * <p>G15 says a client never authors gameplay state, and this class is where that stops being a
 * principle and starts being code: every field of every command a peer sends passes through here
 * before any system reads it. There is exactly one entry point, so "did this value get validated?"
 * has one answer rather than one per call site.
 *
 * <p>Two design notes worth keeping:
 *
 * <ul>
 *   <li><b>Clamp, do not reject</b> (D10-S5.9 step 1). An out-of-range axis becomes a legal one and
 *       the peer's suspicion rises. Dropping the command instead would make a mildly buggy client
 *       undrivable, and packet reordering alone produces enough false positives that automatic
 *       action would kick real players (D10-R27).
 *   <li><b>There is no fire validation</b> (D10-S5.9 step 4). Cooldown, ammunition and heat live on
 *       the authority's own components, so a client that sets its fire mask every tick fires at
 *       exactly its weapon's real rate. Nothing is validated because there is nothing to gain.
 * </ul>
 */
public final class InputValidator {

    /** Aim pitch limits, radians. A vehicle's weapons cannot look straight up or straight down. */
    public static final float MIN_PITCH_RAD = (float) -Math.toRadians(30.0);

    public static final float MAX_PITCH_RAD = (float) Math.toRadians(60.0);

    private InputValidator() {
        throw new AssertionError("no instances");
    }

    /**
     * Clamps a command into range and decides whether it may be applied at all.
     *
     * @return true when the command should be buffered; false when it is dropped
     */
    public static boolean validate(InputCommand command, PeerSession peer, long serverTick) {
        clampAxes(command, peer);

        if (command.commandTick > serverTick + NetConstants.MAX_FUTURE_TICKS) {
            // Claiming the future is how a speed hack asks to be simulated ahead of everyone else
            // (D10-E1). The client corrects its own tick offset from the next InputAck.
            peer.suspicion += NetConstants.SUSPICION_FUTURE_TICK;
            return false;
        }
        if (command.commandTick < serverTick - NetConstants.HISTORY_TICKS) {
            // Too old to matter: the tick it was meant for is past the lag-compensation window.
            return false;
        }
        return true;
    }

    /**
     * D10-S5.9's rate limit, counted in <b>packets</b> rather than commands.
     *
     * <p>Each packet carries a redundancy window of {@code INPUT_REDUNDANCY} past commands
     * (D10-R4), so a well-behaved client at 60 Hz delivers about 420 commands a second and would
     * trip a limit of 90 on its first second. What the limit is actually for is a client sending
     * faster than the tick rate, and that is a property of the packets, not of what they contain.
     *
     * @return true when the packet may be decoded; false when the peer is sending too fast
     */
    public static boolean acceptPacket(PeerSession peer, long serverTick) {
        return withinRateLimit(peer, serverTick);
    }

    private static void clampAxes(InputCommand command, PeerSession peer) {
        boolean clamped = false;
        float throttle = clamp(command.throttle, -1f, 1f);
        float steer = clamp(command.steer, -1f, 1f);
        float brake = clamp(command.brake, 0f, 1f);
        float pitch = clamp(command.aimPitchRad, MIN_PITCH_RAD, MAX_PITCH_RAD);
        float yaw = Quantisation.wrapAngle(command.aimYawRad);

        clamped |= throttle != command.throttle;
        clamped |= steer != command.steer;
        clamped |= brake != command.brake;
        clamped |= pitch != command.aimPitchRad;

        command.throttle = throttle;
        command.steer = steer;
        command.brake = brake;
        command.aimPitchRad = pitch;
        command.aimYawRad = yaw;

        if (clamped || !Float.isFinite(throttle) || !Float.isFinite(steer)) {
            // A legitimate client never needs a clamp: its own input layer produces values in range.
            peer.suspicion += NetConstants.SUSPICION_CLAMPED;
        }
    }

    private static boolean withinRateLimit(PeerSession peer, long serverTick) {
        if (serverTick - peer.inputRateWindowStartTick >= SimulationConstants.TICK_RATE_HZ) {
            peer.inputRateWindowStartTick = serverTick;
            peer.inputsThisWindow = 0;
        }
        peer.inputsThisWindow++;
        int limit = Math.round(SimulationConstants.TICK_RATE_HZ * NetConstants.MAX_INPUT_RATE_FACTOR);
        if (peer.inputsThisWindow > limit) {
            peer.suspicion += NetConstants.SUSPICION_RATE_LIMIT;
            return false;
        }
        return true;
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            // NaN fails every comparison, so it would pass a naive clamp and then poison a rigid
            // body's transform on the first tick it is applied (D06-E2).
            return 0f;
        }
        return Math.max(min, Math.min(max, value));
    }
}
