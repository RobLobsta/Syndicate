/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;

/**
 * Where an entity is (docs/04_entity_component_model.md#D04-S4.3.1).
 *
 * <p>{@link #position} and {@link #rotation} are world space for root entities and parent-local for
 * parts, selected by {@link #parent}. {@link #worldMatrix} is a derived cache recomputed by
 * {@code TransformSystem} (schedule slot 21) and is classified {@code L} — it is never replicated,
 * because sending a matrix that the receiver can recompute wastes bandwidth on every entity in
 * every snapshot.
 */
public final class TransformComponent implements Component {

    /** Metres. World space when {@link #parent} is {@link EntityId#NULL}, parent-local otherwise. */
    public final Vector3 position = new Vector3();

    /** Unit quaternion {@code (x,y,z,w)}. */
    public final Quaternion rotation = new Quaternion();

    /**
     * Uniform in practice. Non-uniform scale is rejected by asset validation (D08-S8), because a
     * non-uniformly scaled convex hull is not the convex hull of the scaled mesh, so collision and
     * render would silently disagree.
     */
    public final Vector3 scale = new Vector3(1f, 1f, 1f);

    /** Derived cache, recomputed by {@code TransformSystem}. Never replicated. */
    public final Matrix4 worldMatrix = new Matrix4();

    /** The parent entity, or {@link EntityId#NULL} for a root. */
    public int parent = EntityId.NULL;

    /** Set when position/rotation/scale changed since {@link #worldMatrix} was last rebuilt. */
    public boolean dirty = true;

    @Override
    public void reset() {
        position.set(0f, 0f, 0f);
        rotation.idt();
        scale.set(1f, 1f, 1f);
        worldMatrix.idt();
        parent = EntityId.NULL;
        dirty = true;
    }
}
