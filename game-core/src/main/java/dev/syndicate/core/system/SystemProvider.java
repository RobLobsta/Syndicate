/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.model.RuntimeMode;

/**
 * Builds the system that fills one {@link SystemSlot}, or declines to
 * (docs/03_runtime_modes.md#D03-S5.2).
 *
 * <p>D03-S5.2 writes the schedule as a list of class names, which reads as though one place could
 * name all 27. It cannot: six of them live in {@code game-client}, and {@code game-core} sits below
 * it in the layer order (D02-S5.6), so naming them here would be the dependency cycle that layering
 * exists to make unrepresentable. The catalogue and its mode filter stay in {@code game-core}, where
 * they are the single description of causality D04-R13 wants; <em>construction</em> is delegated.
 *
 * <p>A provider returns null for a slot it does not implement. {@code SystemSetFactory} consults
 * providers in order and takes the first non-null, so a client composes
 * {@code [clientProvider, coreProvider]} and a dedicated server passes the core provider alone.
 * Null is also how an unimplemented slot is expressed — most of the catalogue, today — which keeps
 * a mode's schedule honest about what exists rather than failing to start over a system nobody has
 * written yet.
 */
@FunctionalInterface
public interface SystemProvider {

    /**
     * Creates the system for a slot.
     *
     * @param mode the resolved runtime mode, which decides between the two implementations of a
     *     {@link SystemSlot.Availability#AUTHORITY_OR_PREDICTED} slot
     * @return the system, or null when this provider does not implement the slot
     */
    EntitySystem create(SystemSlot slot, RuntimeMode mode);
}
