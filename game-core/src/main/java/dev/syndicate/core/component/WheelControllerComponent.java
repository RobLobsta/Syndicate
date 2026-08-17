/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.arena.Surface;
import dev.syndicate.core.ecs.Component;

/**
 * One ray-cast wheel's parameters (docs/04_entity_component_model.md#D04-S4.3.4,
 * docs/06_physics_simulation.md#D06-S5.5).
 *
 * <p>{@link #wheelIndex} must equal this wheel's index in the vehicle's {@code btRaycastVehicle}
 * and its position in {@code VehicleChassisComponent.wheelEntities}. Those three orderings are one
 * ordering; if they diverge, the game applies a damaged wheel's reduced grip to a healthy wheel and
 * the vehicle pulls to the wrong side.
 *
 * <p>Only {@link #frictionSlip} has an effective counterpart. The suspension parameters do not
 * degrade with damage in v1 — a wheel loses grip and eventually detaches, but its spring does not
 * soften — so a second copy of them would be state that is by construction always equal.
 */
public final class WheelControllerComponent implements Component {

    /** Index into the vehicle's {@code btRaycastVehicle}. */
    public int wheelIndex;

    /** Whether steering input turns this wheel. */
    public boolean isSteering;

    /** Whether engine force is applied at this wheel. */
    public boolean isDriven;

    /** Wheel radius in metres. */
    public float radiusM;

    /** Suspension rest length in metres. */
    public float suspensionRestLengthM;

    /** Suspension spring constant. */
    public float suspensionStiffness;

    /** Damping while the suspension extends. */
    public float dampingRelax;

    /** Damping while the suspension compresses. */
    public float dampingCompress;

    /** Tyre grip at full health. */
    public float frictionSlip;

    /** How much lateral force induces body roll, {@code [0,1]}. */
    public float rollInfluence;

    /** {@link #frictionSlip} after the degradation curve (D05-S5.4). */
    public float effectiveFrictionSlip;

    // ---- Contact state, mirrored out of Bullet each tick -----------------------------------
    //
    // D15-R36 keys tyre roll and skid on "per surface, blended by slip", and notes that the ray-cast
    // wheel already computes what that needs. It did — inside Bullet, where nothing outside the
    // physics step could read it, which is why both tyre families had correct sounds in the bank and
    // no way to trigger them. `VehicleControlSystem` (7) mirrors the three numbers out here after the
    // step, in the same pass that writes engine force and steering in.
    //
    // Cosmetic in the G6 sense: written by the simulation, read by presentation, and never read back
    // into a gameplay decision.

    /** Whether this wheel's suspension ray found ground on the last step. */
    public boolean isInContact;

    /** The suspension force this wheel is carrying, in newtons. Zero when it is off the ground. */
    public float suspensionLoadN;

    /**
     * How much this wheel is sliding rather than gripping, {@code [0,1]}.
     *
     * <p>Zero is full grip and one is a full slide. Bullet's own {@code skidInfo} runs the other way
     * — 1.0 means no skid — and is inverted here, because every consumer wants "how much squeal",
     * and a quantity whose name says skid but whose value falls as skidding rises is a trap.
     */
    public float skid;

    /**
     * What this wheel is standing on, or null off a generated arena (D16-R54, D16-R56).
     *
     * <p>Written once per tick by the shared vehicle control operation, from the surface grid at the
     * suspension ray's contact point. Slot 25 reads it for the tyre loop rather than looking the
     * surface up a second time: D16-R56 requires the physics and the audio to agree, and two
     * independent lookups is exactly how they come to disagree.
     */
    public Surface surface;

    @Override
    public void reset() {
        surface = null;
        wheelIndex = 0;
        isSteering = false;
        isDriven = false;
        radiusM = 0f;
        suspensionRestLengthM = 0f;
        suspensionStiffness = 0f;
        dampingRelax = 0f;
        dampingCompress = 0f;
        frictionSlip = 0f;
        rollInfluence = 0f;
        effectiveFrictionSlip = 0f;
        isInContact = false;
        suspensionLoadN = 0f;
        skid = 0f;
    }
}
