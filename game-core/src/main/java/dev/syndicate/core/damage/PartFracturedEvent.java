/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Vector3;

/**
 * A part broke into shards (docs/07_damage_destruction_model.md#D07-S5.6, #D07-S5.9).
 *
 * <p>Carries the parent's motion at the moment of the fracture and <b>nothing about the shards</b>.
 * Individual shard transforms and velocities are never replicated (DEC-005, D07-R5): each client
 * spawns its own shard set from the same manifest, and because the parent's linear and angular
 * velocity are here, its shards inherit the same momentum the authority's did. Sending 200 shard
 * transforms per fracture would cost more bandwidth than the entire rest of the match for something
 * no gameplay outcome depends on.
 *
 * <p>Velocities are stored as scalars rather than {@code Vector3} because an event outlives the
 * tick that emitted it — it is dispatched at the end of the tick and consumed in the next
 * (D04-R14) — and a pooled vector handed to it would have been reused by then.
 *
 * @param partEntity the part that fractured, already queued for destruction when this is dispatched
 * @param vehicleEntity the vehicle it belonged to, or {@code EntityId.NULL} if it was already loose
 * @param slotPath the part's stable identity (D05-R11)
 * @param shardCount how many debris bodies the authority spawned; a client that spawns a different
 *     number has a manifest mismatch, which is worth detecting
 * @param tick the tick of the fracture
 * @param parentLinearX parent's linear velocity, m/s
 * @param parentLinearY parent's linear velocity, m/s
 * @param parentLinearZ parent's linear velocity, m/s
 * @param parentAngularX parent's angular velocity, rad/s
 * @param parentAngularY parent's angular velocity, rad/s
 * @param parentAngularZ parent's angular velocity, rad/s
 */
public record PartFracturedEvent(
        int partEntity,
        int vehicleEntity,
        String slotPath,
        int shardCount,
        long tick,
        float parentLinearX,
        float parentLinearY,
        float parentLinearZ,
        float parentAngularX,
        float parentAngularY,
        float parentAngularZ) {

    /** Writes the parent's linear velocity into {@code out}. */
    public Vector3 parentLinear(Vector3 out) {
        return out.set(parentLinearX, parentLinearY, parentLinearZ);
    }

    /** Writes the parent's angular velocity into {@code out}. */
    public Vector3 parentAngular(Vector3 out) {
        return out.set(parentAngularX, parentAngularY, parentAngularZ);
    }
}
