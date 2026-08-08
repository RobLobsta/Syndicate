/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

/**
 * A part left its vehicle (docs/05_vehicle_part_system.md#D05-S5.5,
 * docs/07_damage_destruction_model.md#D07-S5.9).
 *
 * <p>A <b>structural</b> event, which is what makes it reliable and ordered on the wire (D07-R27).
 * Losing one would leave a client driving a vehicle heavier than the server's forever: mass, COM and
 * inertia are never replicated as numbers, every peer recomputes them from the structural state, and
 * a missed detach is a divergence no later snapshot corrects.
 *
 * @param vehicleEntity the vehicle the part left, or {@code EntityId.NULL} if it had already been
 *     detached
 * @param partEntity the part that left; it may already be queued for destruction when this is
 *     dispatched, so treat the id as a label rather than a handle (D04-E1)
 * @param slotPath the part's stable identity, which is what replication and the damage ledger use
 *     rather than the entity id (D05-R11)
 * @param reason why it left
 * @param tick the tick it left on
 */
public record PartDetachedEvent(int vehicleEntity, int partEntity, String slotPath, DetachReason reason, long tick) {}
