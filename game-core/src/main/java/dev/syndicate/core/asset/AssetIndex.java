/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;

/**
 * The simulation's read-only view of loaded content (docs/08_asset_pipeline.md#D08-S5.3,
 * docs/04_entity_component_model.md#D04-S5.4).
 *
 * <p>{@code World} deliberately knows nothing about assets, so the index is a constructor
 * dependency of the systems that need it — the same shape as {@code PhysicsWorld} on
 * {@code PhysicsSystem} (DEC-012). A system that can only be constructed with an index cannot be
 * scheduled without one.
 *
 * <p>Only the lookups an implemented system actually performs are declared. The asset pipeline of
 * D08 is not written yet; part types, materials, assemblies and arenas join this interface as the
 * systems that consume them arrive, rather than being declared now and returning null for a
 * release.
 */
public interface AssetIndex {

    /**
     * The fracture manifest with this id.
     *
     * @return the manifest, or null when no manifest with that id is loaded. Null rather than a
     *     throw: a part whose manifest failed to load must still be destructible — it vanishes
     *     instead of fracturing, which is exactly what a part with no manifest reference does
     *     (D05-S4.4).
     */
    FractureManifest fractureManifest(AssetId manifestId);
}
