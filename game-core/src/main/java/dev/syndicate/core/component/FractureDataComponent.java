/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.AssetId;

/**
 * What a part breaks into, and whether it already has
 * (docs/04_entity_component_model.md#D04-S4.3.3, docs/07_damage_destruction_model.md#D07-S5.6).
 *
 * <p>A part without this component does not fracture: it vanishes on destruction (D05-S4.4). That
 * is a content decision made per part type, not a runtime fallback.
 *
 * <p>{@link #hasFractured} is one-way and authoritative. G9 makes fracture irreversible, so a part
 * whose health is somehow restored after fracturing stays a pile of shards; the flag is what
 * {@code FractureSystem} checks to refuse the second attempt.
 */
public final class FractureDataComponent implements Component {

    /** Which fracture manifest describes this part's shards (D09-S4.4). */
    public AssetId manifestRef;

    /** How many shards the manifest declares. Cached so spawning does not reload the manifest. */
    public int shardCount;

    /** Set once, by {@code FractureSystem}. Never cleared for the life of the entity (G9). */
    public boolean hasFractured;

    @Override
    public void reset() {
        manifestRef = null;
        shardCount = 0;
        hasFractured = false;
    }
}
