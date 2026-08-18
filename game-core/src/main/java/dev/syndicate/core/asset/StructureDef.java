/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import java.util.Objects;

/**
 * A destructible structure — a building, a tower, a piece of street furniture
 * (docs/16_procedural_arena_generation.md#D16-S4.6).
 *
 * <p><b>It is an assembly, and that is the whole design</b> (DEC-071, D16-R18). This record carries
 * an {@link AssemblyDef} rather than a parallel part list, so a structure resolves through
 * {@link AssemblyLayout} and spawns through the same slot-graph walk a vehicle does. Every system
 * that acts on a part — damage (12), fracture (13), detach (14), lifetime (16), destroy (27) — then
 * acts on a structure with no change and no knowledge that it is one (D16-R76).
 *
 * <p>Three fields are a structure's own, and each earns its place:
 *
 * <ul>
 *   <li>{@link #staticRoot} — the root's body is zero-mass and on the {@code STATIC} layer, which is
 *       what "bolted to the ground" means (D16-R20). A structure with a dynamic root would be a
 *       vehicle with no wheels.
 *   <li>{@link #footprintRadiusM} — what placement spaces instances by and what the terrain pad
 *       flattens (D16-R23). It must enclose the horizontal extent.
 *   <li>{@link #footprintHeightM} — what a road-span placement needs to know it clears.
 * </ul>
 *
 * <p>Immutable and shared, one instance per structure per process, like {@link PartType}.
 *
 * @param structureId the asset id, matching its directory name under {@code assets/structures/}
 * @param assembly the root, the parts on its slots, and their expected total mass
 * @param staticRoot whether the root part is fixed to the world (D16-R20); always true today
 * @param footprintRadiusM the radius enclosing the structure's horizontal extent, metres
 * @param footprintHeightM how tall it stands, metres
 */
public record StructureDef(
        AssetId structureId, AssemblyDef assembly, boolean staticRoot, float footprintRadiusM, float footprintHeightM) {

    /** Metres. A footprint below this is a content error: nothing placeable is that small. */
    public static final float MIN_FOOTPRINT_RADIUS_M = 0.1f;

    public StructureDef {
        Objects.requireNonNull(structureId, "structureId");
        Objects.requireNonNull(assembly, "assembly");
        footprintRadiusM = Math.max(MIN_FOOTPRINT_RADIUS_M, footprintRadiusM);
        footprintHeightM = Math.max(0f, footprintHeightM);
    }

    /** The part standing on the ground, which every other part hangs off (D16-R18). */
    public AssetId rootPartTypeId() {
        return assembly.chassisPartTypeId();
    }

    /** The name a player — or a level designer reading a generation report — sees. */
    public String displayName() {
        return assembly.displayName();
    }

    /** How many parts this structure is made of, root included. */
    public int partCount() {
        return assembly.parts().size() + 1;
    }
}
