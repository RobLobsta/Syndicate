/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;

/**
 * A rigid position + rotation pair, used wherever the blueprints write the type {@code Transform}
 * (docs/04_entity_component_model.md#D04-S4.3.2, docs/05_vehicle_part_system.md#D05-S4.3).
 *
 * <p>Mutable and reusable on purpose: slot attachment offsets are read every tick from pooled
 * components, and allocating a fresh transform per read would violate the zero-steady-state-garbage
 * goal of D04-S5.6.
 *
 * <p>Scale is deliberately absent. D08-S8 rejects non-uniform scale at asset validation and the
 * simulation treats parts as rigid, so a transform that could carry scale would invite content to
 * express something physics cannot honour.
 */
public final class Transform {

    /** Translation in metres, in the parent's space. */
    public final Vector3 position = new Vector3();

    /** Unit quaternion {@code (x,y,z,w)}; identity when reset. */
    public final Quaternion rotation = new Quaternion();

    public Transform() {
        reset();
    }

    /** Sets both fields from another transform. Returns {@code this} for chaining. */
    public Transform set(Transform other) {
        position.set(other.position);
        rotation.set(other.rotation);
        return this;
    }

    /** Sets both fields component-wise. Returns {@code this} for chaining. */
    public Transform set(Vector3 newPosition, Quaternion newRotation) {
        position.set(newPosition);
        rotation.set(newRotation);
        return this;
    }

    /** Writes this transform into {@code out} as a rotation-then-translation matrix. */
    public Matrix4 toMatrix(Matrix4 out) {
        return out.set(position, rotation);
    }

    /** Returns to identity: zero translation, identity rotation. */
    public void reset() {
        position.set(0f, 0f, 0f);
        rotation.idt();
    }

    @Override
    public String toString() {
        return "Transform[pos=" + position + ", rot=" + rotation + "]";
    }
}
