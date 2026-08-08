/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.physics.ShapeCacheKey;
import java.util.ArrayList;
import java.util.List;

/**
 * The immovable collision geometry of an arena
 * (docs/04_entity_component_model.md#D04-S4.3.1).
 *
 * <p>Holds cache keys rather than shapes: arena geometry is the largest collision data in the world
 * and is identical on every peer, so it is loaded once into the shape cache and referenced from
 * there (D02-S5.7 rule 2). Classified {@code L} — arena geometry is loaded from the same asset on
 * every peer and never changes, so replicating it would send a megabyte to say nothing.
 */
public final class StaticCollisionComponent implements Component {

    /**
     * The shapes making up this arena piece, in load order. Kept as a list rather than a single key
     * because an arena is authored as many disjoint pieces and a single concave mesh shape for all
     * of them would defeat broadphase culling.
     */
    public final List<ShapeCacheKey> shapes = new ArrayList<>();

    @Override
    public void reset() {
        shapes.clear();
    }
}
