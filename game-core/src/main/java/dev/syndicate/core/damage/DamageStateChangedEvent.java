/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import dev.syndicate.model.DamageState;

/**
 * A part crossed a damage-state threshold (docs/07_damage_destruction_model.md#D07-S5.3).
 *
 * <p>Authoritative, and monotonic in severity by construction: the transition that emits this has
 * already rejected any step back toward health (G8) and any move out of a terminal state (G9), so a
 * listener may assume {@code newState} is strictly more severe than {@code oldState}.
 *
 * <p>Emitted for every transition, including the one into {@code DESTROYED} — which also emits
 * {@link PartDestroyedEvent}. The two are separate because they have different audiences: this one
 * is presentation and replication (the state is three bits on the wire, D07-S5.9), while
 * {@code PartDestroyed} is the one scoring reads.
 *
 * @param partEntity the part that transitioned
 * @param vehicleEntity the vehicle it belongs to, or {@code EntityId.NULL} once loose
 * @param slotPath the part's stable identity (D05-R11)
 * @param oldState the state it left
 * @param newState the state it entered
 * @param tick the tick of the transition
 */
public record DamageStateChangedEvent(
        int partEntity, int vehicleEntity, String slotPath, DamageState oldState, DamageState newState, long tick) {}
