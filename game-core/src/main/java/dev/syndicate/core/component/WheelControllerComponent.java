/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

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

    @Override
    public void reset() {
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
    }
}
