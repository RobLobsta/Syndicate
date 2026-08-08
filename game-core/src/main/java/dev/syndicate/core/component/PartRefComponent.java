/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.AssetId;

/**
 * What a part entity is, and which vehicle it belongs to
 * (docs/04_entity_component_model.md#D04-S4.3.2).
 *
 * <p>{@link #slotPath} rather than an entity id is the identity used by replication, test
 * assertions, and the damage ledger (D05-R11). Entity ids recycle and differ between a recording
 * and a replay; a slot path is stable for the life of the assembly, so "the left hardpoint took
 * 40 damage" survives both.
 */
public final class PartRefComponent implements Component {

    /** Which part type this is an instance of. */
    public AssetId partTypeId;

    /** The owning vehicle, or {@link EntityId#NULL} once detached (D07-S5.7). */
    public int vehicleEntity = EntityId.NULL;

    /** Stable path from the chassis, e.g. {@code root/turret_00/barrel_01} (D05-R11). */
    public String slotPath = "";

    @Override
    public void reset() {
        partTypeId = null;
        vehicleEntity = EntityId.NULL;
        slotPath = "";
    }
}
