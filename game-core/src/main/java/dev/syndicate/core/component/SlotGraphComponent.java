/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.vehicle.SlotNode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The tree of parts making up a vehicle (docs/04_entity_component_model.md#D04-S4.3.2,
 * docs/05_vehicle_part_system.md#D05-S4.3).
 *
 * <p>{@link #structuralVersion} is the pivot of the whole vehicle subsystem. Every cache derived
 * from the graph — the compound collision shape, aggregated stats, mass properties, the armour
 * coverage map — is invalidated by a version change and recomputed in the same tick (D05-R12, G10).
 * A structural change that forgets to bump it leaves a vehicle driving with the mass it had before
 * it lost a wheel, which presents as a physics bug several seconds later.
 */
public final class SlotGraphComponent implements Component {

    /**
     * Occupied slots, kept sorted by {@code slotPath}. Sorted rather than insertion-ordered so
     * traversal is deterministic across peers that attached the same parts in different orders (G3).
     */
    public final List<SlotNode> nodes = new ArrayList<>();

    /**
     * Derived child → parent index, rebuilt whenever {@link #structuralVersion} changes. A
     * {@code TreeMap} rather than a hash map so iteration is by ascending entity id (G3).
     */
    public final TreeMap<Integer, Integer> parentOf = new TreeMap<>();

    /** Incremented on every attach and every detach. Never decremented, never reset mid-match. */
    public int structuralVersion;

    @Override
    public void reset() {
        nodes.clear();
        parentOf.clear();
        structuralVersion = 0;
    }
}
