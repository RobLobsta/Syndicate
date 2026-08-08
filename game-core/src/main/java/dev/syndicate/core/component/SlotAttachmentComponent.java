/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.physics.bullet.dynamics.btTypedConstraint;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.util.Transform;

/**
 * How a part is attached to its parent (docs/04_entity_component_model.md#D04-S4.3.2).
 *
 * <p>{@link #constraintHandle} is present only for the minority of parts that are separate bodies
 * joined by a constraint (D06-S5.6). Most parts are geometry inside the vehicle's single compound
 * shape (DEC-004) and have no constraint at all, which is why the field is nullable rather than the
 * attachment always being a joint.
 *
 * <p><b>Native ownership (G19).</b> The constraint is owned by {@code PhysicsWorld}, which disposes
 * it <em>before</em> either endpoint body (D02-S5.7 rules 4 and 5). Disposing it from here would
 * leave the world holding a freed pointer until the next step.
 */
public final class SlotAttachmentComponent implements Component {

    /** The part offering the slot. */
    public int parentEntity = EntityId.NULL;

    /** The slot's id on the parent, e.g. {@code hardpoint_left}. */
    public String slotId = "";

    /** Attachment offset from the slot, from the slot definition. */
    public final Transform localTransform = new Transform();

    /** OWNER: {@code PhysicsWorld}. Null when the part is compound geometry rather than a body. */
    public btTypedConstraint constraintHandle;

    /** Newton-seconds. The impulse at which the joint breaks and the part detaches (D07-S5.7). */
    public float breakImpulseN;

    @Override
    public void reset() {
        parentEntity = EntityId.NULL;
        slotId = "";
        localTransform.reset();
        constraintHandle = null;
        breakImpulseN = 0f;
    }
}
