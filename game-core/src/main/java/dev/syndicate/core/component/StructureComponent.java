/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.AssetId;

/**
 * Marks an entity as a destructible structure (docs/16_procedural_arena_generation.md#D16-S7).
 *
 * <p><b>A marker and an identity, and nothing else.</b> D16-R80 says a structure needs no new
 * system, no new component and no new schedule slot, and this component is the closest thing to a
 * violation of that sentence in the codebase — so it is worth being exact about what it does and
 * does not do.
 *
 * <p>What it does: it names which structure an entity is, and how much room it takes up, so that the
 * placement pass can space instances apart and a report can say what an arena was built out of. What
 * it does <em>not</em> do is participate in damage, fracture, detachment, lifetime or teardown —
 * those act on the structure's <em>parts</em>, which carry the same {@code PartComponent} set a
 * vehicle's parts do and are indistinguishable to every system that touches them (D16-R76). Nothing
 * in the twenty-seven slots reads this component.
 *
 * <p>The alternative was to give a structure a {@code VehicleChassisComponent} and let it be a
 * vehicle with no wheels, which would have needed no new component and would have made a building
 * turn up in every family query about vehicles — including the ones that steer, aggregate stats and
 * replicate. That is a worse trade, and it is the trade this component exists to avoid.
 */
public final class StructureComponent implements Component {

    /** Which structure this is (D16-R18). */
    public AssetId structureId;

    /** The part standing on the ground; every other part hangs off it, directly or not. */
    public int rootPartEntity = EntityId.NULL;

    /** How many parts were actually placed, which can be fewer than the definition names. */
    public int partCount;

    /** The radius enclosing the horizontal extent, metres — what placement spaces by (D16-R20). */
    public float footprintRadiusM;

    /** How tall it stands, metres. */
    public float footprintHeightM;

    @Override
    public void reset() {
        structureId = null;
        rootPartEntity = EntityId.NULL;
        partCount = 0;
        footprintRadiusM = 0f;
        footprintHeightM = 0f;
    }
}
