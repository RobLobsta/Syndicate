/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import java.util.Objects;

/**
 * The part-type fields the simulation reads at runtime (docs/08_asset_pipeline.md#D08-S4.2).
 *
 * <p>Deliberately not the whole of D08-R5. A part type's authored data is split across the
 * components that carry it once a part is spawned — mass and the collision hull key live on
 * {@code RigidBodyComponent}, category and stats on {@code PartStatsComponent}, the break impulse on
 * {@code SlotAttachmentComponent} — and duplicating those here would create a second answer to
 * questions the components already answer. What is left is what only the type knows and no
 * component holds: the collision mesh a detached part needs a body built from, and whether the type
 * hangs before it falls.
 *
 * <p>Fields join this record as the systems that consume them arrive, for the same reason
 * {@link AssetIndex} declares only the lookups an implemented system performs: a field that is
 * declared before anything reads it has no test that would notice it being loaded wrong.
 *
 * @param partTypeId which part type this describes (D00-R19)
 * @param collisionMesh the mesh its convex hull is built from. The vehicle compound builds hulls for
 *     the parts it contains (D06-S5.3), but a wheel is a ray cast and contributes no compound
 *     geometry (D06-R6) — so this is the only source of a wheel's hull when it detaches and becomes a
 *     debris body of its own.
 * @param hangsBeforeFalling whether a destroyed part of this type hangs by a thread for up to
 *     {@code HANGING_TICKS} before it detaches (D07-S5.7 T1, D06-S5.6)
 */
public record PartType(AssetId partTypeId, MeshData collisionMesh, boolean hangsBeforeFalling) {

    public PartType {
        Objects.requireNonNull(partTypeId, "partTypeId");
        Objects.requireNonNull(collisionMesh, "collisionMesh");
    }

    /** A part type that falls the moment it is destroyed, which is the default (D08-S4.2). */
    public static PartType of(AssetId partTypeId, MeshData collisionMesh) {
        return new PartType(partTypeId, collisionMesh, false);
    }

    /** The same type, but one that hangs before it falls (D07-S5.7 T1). */
    public PartType hanging() {
        return new PartType(partTypeId, collisionMesh, true);
    }
}
