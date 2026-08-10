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
 * D08 is not written yet; materials, assemblies and arenas join this interface as the systems that
 * consume them arrive, rather than being declared now and returning null for a release.
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

    /**
     * The part type with this id.
     *
     * <p>Read by {@code SpawnSystem} (slot 5), which copies a type's authored fields onto the
     * components of the part it creates, and by {@code DetachSystem} (slot 14), which needs a part's
     * collision mesh to build the debris body it becomes and its {@code hangsBeforeFalling} flag to
     * decide when it leaves (D07-S5.7).
     *
     * @return the part type, or null when no type with that id is loaded. Null rather than a throw,
     *     for the same reason {@link #fractureManifest} returns null: a part whose type failed to
     *     load must still be able to leave its vehicle, and refusing to detach it would leave a
     *     destroyed part welded to a live chassis.
     */
    PartType partType(AssetId partTypeId);

    /**
     * The assembly with this id (D08-S4.4).
     *
     * @return the assembly, or null when none with that id is loaded. A spawn request naming an
     *     unloaded assembly is refused with a log line rather than an exception, because a bad
     *     assembly id arriving from a client's loadout choice must not be able to abort a tick
     *     (D10-S4.6).
     */
    AssemblyDef assembly(AssetId assemblyId);

    /**
     * The material with this id (D08-S4.3).
     *
     * @return the material, or null when none with that id is loaded
     */
    MaterialDef material(AssetId materialId);

    /**
     * The arena with this id (D08-S4.7).
     *
     * <p>Read by {@code ArenaFactory} at world construction (D04-S5.4) and by whatever chooses a
     * spawn point.
     *
     * @return the arena, or null when none with that id is loaded. A process with no arena runs a
     *     world with no ground rather than refusing to start, which is what {@code ServerMain} has
     *     been doing since it became a process.
     */
    ArenaDef arena(AssetId arenaId);
}
