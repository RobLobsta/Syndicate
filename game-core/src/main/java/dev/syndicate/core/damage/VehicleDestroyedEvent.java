/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

/**
 * A vehicle's chassis died and the assembly came apart
 * (docs/07_damage_destruction_model.md#D07-S5.7, #D07-S5.9).
 *
 * <p>A <b>structural</b> event, sent reliably and in order (D07-R27), and the one destruction event
 * that scoring reads: {@link #killerEntity} is what awards the kill (D01-S5.4). It is emitted
 * exactly once per vehicle — {@code wreckVehicle} destroys the vehicle entity in the same call, and
 * a destroyed entity leaves its family immediately, so there is no second pass that could emit it
 * again (T-D07-19).
 *
 * <p>Emitted <em>before</em> the parts detach, so a listener sees "this vehicle died" ahead of the
 * twenty {@code PartDetachedEvent}s that describe how, rather than having to infer the death from
 * the last of them.
 *
 * @param vehicleEntity the vehicle that died; already queued for destruction when this is
 *     dispatched, so treat the id as a label rather than a handle (D04-E1)
 * @param killerEntity whoever last damaged the chassis part, or {@code EntityId.NULL} when nothing
 *     did — a vehicle can die to terrain, and an unattributed kill is scored as one (D01-S5.4)
 * @param tick the tick it died on
 */
public record VehicleDestroyedEvent(int vehicleEntity, int killerEntity, long tick) {}
