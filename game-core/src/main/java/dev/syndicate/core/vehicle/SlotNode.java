/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.util.Transform;

/**
 * One occupied slot in a vehicle's slot graph
 * (docs/04_entity_component_model.md#D04-S4.3.2, docs/05_vehicle_part_system.md#D05-S4.3).
 *
 * <p>A node is an <em>edge</em> of the tree, not a vertex: it names the parent part, the slot on
 * that parent, and the part occupying it. The chassis has no node — it is the root, identified by
 * {@code VehicleChassisComponent.chassisPartEntity}.
 *
 * <p>Mutable and pooled with its owning {@code SlotGraphComponent}. Nodes are held in an array
 * sorted by {@link #slotPath}, which is what makes graph iteration deterministic (G3) without a
 * sort on every traversal.
 */
public final class SlotNode {

    /** The part that offers the slot. {@link EntityId#NULL} only on an uninitialised node. */
    public int parentEntity = EntityId.NULL;

    /** The part occupying the slot. */
    public int childEntity = EntityId.NULL;

    /** The slot's id on the parent part, e.g. {@code hardpoint_left}. */
    public String slotId = "";

    /** The full {@code /}-joined path from the chassis, e.g. {@code root/hardpoint_left} (D05-R11). */
    public String slotPath = "";

    /** Which categories this slot accepts (D05-S4.3). */
    public SlotType slotType = SlotType.ACCESSORY;

    /** The child's attachment offset from the slot, in the parent's local space. */
    public final Transform localTransform = new Transform();

    /** Returns this node to its pristine state so it can be pooled (D04-R17). */
    public void reset() {
        parentEntity = EntityId.NULL;
        childEntity = EntityId.NULL;
        slotId = "";
        slotPath = "";
        slotType = SlotType.ACCESSORY;
        localTransform.reset();
    }

    @Override
    public String toString() {
        return "SlotNode[" + slotPath + " -> " + EntityId.toString(childEntity) + "]";
    }
}
