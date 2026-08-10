/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

/**
 * A part reached zero hit points (docs/07_damage_destruction_model.md#D07-S5.3, #D07-S5.9).
 *
 * <p>Structural, replicated reliably and in order, and emitted <b>exactly once</b> per part: the
 * transition into {@code DESTROYED} is terminal (G9), so a second damage event that would also have
 * destroyed the part is discarded before it can emit anything (D07-E9).
 *
 * <p>{@link #killerPlayerEntity} is the player whose damage crossed zero, captured at the moment it
 * did rather than read later from {@code HealthComponent.lastAttacker}. Later is too late: the same
 * tick's propagation can write a different attacker onto the part, and a part that fractures is
 * gone before {@code ScoreSystem} (17) runs.
 *
 * @param partEntity the part that died
 * @param vehicleEntity the vehicle it belonged to, or {@code EntityId.NULL} if it was already loose
 * @param slotPath the part's stable identity (D05-R11)
 * @param isChassis true when this part is its vehicle's chassis, which makes the kill a vehicle kill
 *     rather than a part kill (D01-S5.4)
 * @param killerPlayerEntity the player credited, or {@code EntityId.NULL} for world damage
 * @param tick the tick it died on
 */
public record PartDestroyedEvent(
        int partEntity, int vehicleEntity, String slotPath, boolean isChassis, int killerPlayerEntity, long tick) {}
