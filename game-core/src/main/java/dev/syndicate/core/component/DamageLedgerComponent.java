/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.damage.DamageLedger;
import dev.syndicate.core.ecs.Component;

/**
 * Who has damaged whom, for the whole match
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/01_product_game_design.md#D01-S5.4).
 *
 * <p>Lives on the match singleton (entity 1, D04-R23), because it outlives every vehicle it records
 * damage against — that is the point of it. A ledger on the victim would vanish with the victim, and
 * the moment it is needed is exactly the moment the victim dies.
 *
 * <p>Authoritative state (G1). Assists are computed from it, and a client that computed its own would
 * be authoring score (G15).
 *
 * <p>The component is data; {@link DamageLedger} is the structure it holds. Splitting them keeps
 * D04-R2 honest — a component with a {@code record()} method on it would be a system in disguise —
 * while letting the bucketed window live somewhere it can be unit tested on its own.
 */
public final class DamageLedgerComponent implements Component {

    /** The ledger. Never replaced, so {@link #reset()} clears it in place for pooling (D04-R17). */
    public final DamageLedger ledger = new DamageLedger();

    @Override
    public void reset() {
        ledger.clear();
    }
}
