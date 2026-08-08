/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

/**
 * One tick's worth of a client's intent, as stored in the prediction buffer
 * (docs/10_networking_multiplayer.md#D10-S4.2, #D10-S5.5).
 *
 * <p>Mutable and preallocated inside a {@code RingBuffer}, because reconciliation replays every
 * unacknowledged command after a correction and a fresh object per tick per client would allocate
 * continuously (D04-S5.6).
 *
 * <p>This is the same field set as {@code PlayerInputComponent}, deliberately duplicated rather than
 * shared: the component is the current intent the simulation reads, while this is a historical
 * record the network layer replays. Merging them would mean the replay buffer aliases live
 * simulation state, and a rewind would corrupt the very inputs it is replaying.
 */
public final class InputCommand {

    /** Monotonic per client; what {@code InputAck} acknowledges (D10-S5.5). */
    public int sequence;

    /** The tick this input was produced for. */
    public long commandTick;

    /** Forward/back, {@code [-1,1]}. */
    public float throttle;

    /** Left/right, {@code [-1,1]}. */
    public float steer;

    /** Brake, {@code [0,1]}. */
    public float brake;

    /** Aim yaw in radians. */
    public float aimYawRad;

    /** Aim pitch in radians. */
    public float aimPitchRad;

    /** One bit per weapon group. */
    public int fireMask;

    /** Copies every field from {@code other}. */
    public void set(InputCommand other) {
        sequence = other.sequence;
        commandTick = other.commandTick;
        throttle = other.throttle;
        steer = other.steer;
        brake = other.brake;
        aimYawRad = other.aimYawRad;
        aimPitchRad = other.aimPitchRad;
        fireMask = other.fireMask;
    }

    /** Returns to a neutral, unsequenced command. */
    public void reset() {
        sequence = 0;
        commandTick = 0L;
        throttle = 0f;
        steer = 0f;
        brake = 0f;
        aimYawRad = 0f;
        aimPitchRad = 0f;
        fireMask = 0;
    }
}
