/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.Component;

/**
 * One rotor's running state (docs/04_entity_component_model.md#D04-S4.3.2, DEC-090).
 *
 * <p>The {@code rotor} block's fields, flattened onto the part at spawn, in exactly the shape
 * {@code WeaponControllerComponent} flattens a {@code weapon} block: {@code RotorControl} runs on
 * every rotor every tick and must not do an asset-index lookup to learn a radius that cannot
 * change.
 *
 * <p>{@link #currentRpm} is the one field that is not a copy of the block. It is <b>authoritative
 * state</b> — the client reads it to spin the blades (G6) — and it is here rather than derived in
 * the renderer because a rotor slowing to a stop after its engine dies is something both peers must
 * agree about, and a client that integrated its own blade angle would drift from the server's
 * within seconds.
 */
public final class RotorControllerComponent implements Component {

    /** True when this rotor lifts the vehicle; false when it opposes the torque of one that does. */
    public boolean isMain;

    /** Metres, hub to blade tip. */
    public float radiusM;

    /** How many blades, for the client's spin articulation. */
    public int bladeCount = 2;

    /** The disc's governed speed, revolutions per minute. */
    public float maxRpm;

    /** Revolutions per minute right now. Spun up toward {@link #maxRpm} while the rotor is alive. */
    public float currentRpm;

    /** The axis the disc turns about, in the part's local space, unit length. */
    public final Vector3 spinAxisLocal = new Vector3(0f, 1f, 0f);

    @Override
    public void reset() {
        isMain = false;
        radiusM = 0f;
        bladeCount = 2;
        maxRpm = 0f;
        currentRpm = 0f;
        spinAxisLocal.set(0f, 1f, 0f);
    }
}
