/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.component;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import dev.syndicate.core.ecs.Component;

/**
 * The renderable instance for an entity (docs/04_entity_component_model.md#D04-S4.3.5).
 *
 * <p>The one component of the D04-S4.3 catalogue that lives outside {@code game-core}, and the
 * table says why: "never in `game-core`". A {@code ModelInstance} needs the g3d rendering types
 * that D02-R9 bans from the shared module, so placing it here is what keeps G17 true — a dedicated
 * server never loads this class because it never loads this module.
 *
 * <p>Classified {@code C}: cosmetic, client-local, never replicated, never read by a gameplay
 * system.
 */
public final class RenderModelComponent implements Component {

    /** The model instance to draw, or null while the asset is still loading. */
    public ModelInstance modelInstance;

    /** Set false to keep the entity in the world but out of the draw call, e.g. while respawning. */
    public boolean visible = true;

    @Override
    public void reset() {
        // Nulls without disposing: models are shared between instances and owned by the asset
        // manager, so disposing here would blank every other entity using the same model.
        modelInstance = null;
        visible = true;
    }
}
