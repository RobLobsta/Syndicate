/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;

/**
 * A driver's current intent (docs/04_entity_component_model.md#D04-S4.3.4).
 *
 * <p>Written by {@code InputCollectionSystem} on a client, by {@code InputReceiveSystem} on the
 * authority, and by {@code BotDecisionSystem} for bots. That a bot and a human write the same
 * component is what makes G17 hold for AI: every gameplay system downstream reads intent and cannot
 * tell which produced it.
 *
 * <p>Classified authoritative <em>as intent</em>, not as outcome — the distinction G15 rests on.
 * The authority accepts what a client wants to do and decides what happens; it never accepts a
 * client's claim about the result.
 */
public final class PlayerInputComponent implements Component {

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

    /** One bit per weapon group; bit {@code n} fires the group with {@code groupIndex == n}. */
    public int fireMask;

    /** The tick this input was produced for. */
    public long commandTick;

    /** Monotonic per client; what {@code InputAck} acknowledges (D10-S5.5). */
    public int sequence;

    @Override
    public void reset() {
        throttle = 0f;
        steer = 0f;
        brake = 0f;
        aimYawRad = 0f;
        aimPitchRad = 0f;
        fireMask = 0;
        commandTick = 0L;
        sequence = 0;
    }
}
