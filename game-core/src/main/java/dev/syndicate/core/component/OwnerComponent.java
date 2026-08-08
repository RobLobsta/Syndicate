/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;

/**
 * Who is responsible for what an entity does
 * (docs/04_entity_component_model.md#D04-S4.3.4).
 *
 * <p>Carried by projectiles so a kill can be attributed after the weapon that fired it has itself
 * been destroyed. The owner is the <em>player</em> entity rather than the weapon part, precisely so
 * that attribution survives the weapon: a shot in flight when its launcher is blown off still
 * scores for the player who fired it.
 */
public final class OwnerComponent implements Component {

    /** The owning player entity, or {@link EntityId#NULL} for world-caused effects. */
    public int ownerEntity = EntityId.NULL;

    @Override
    public void reset() {
        ownerEntity = EntityId.NULL;
    }
}
