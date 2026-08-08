/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;

/**
 * Marks an entity as debris (docs/04_entity_component_model.md#D04-S4.3.3).
 *
 * <p>Primarily a family marker: the debris budget of D07-S5.8 needs to count and to evict debris
 * without walking every entity in the world, and a marker component makes that a family query.
 *
 * <p>{@link #sourcePartEntity} is classified {@code L} and will usually be a stale id by the time
 * anyone reads it — the part it names was destroyed in the same tick this entity was created. That
 * is fine for its purpose: {@code World.get} on a stale id returns null rather than throwing
 * (D04-E1), and the id is still a usable label in a telemetry line.
 */
public final class DebrisTagComponent implements Component {

    /** Which part this shard came from. Usually already destroyed; for debugging and telemetry. */
    public int sourcePartEntity = EntityId.NULL;

    /** The tick this debris entity was created; the debris budget evicts oldest-first. */
    public long spawnTick;

    @Override
    public void reset() {
        sourcePartEntity = EntityId.NULL;
        spawnTick = 0L;
    }
}
