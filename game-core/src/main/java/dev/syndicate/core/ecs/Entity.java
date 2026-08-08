/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import java.util.Arrays;

/**
 * An id, a component mask, and a component slot array (docs/04_entity_component_model.md#D04-S4.5).
 *
 * <p>Entities are pooled and reused, so an instance's identity is its {@link #id()}, never its
 * object reference. Holding an {@code Entity} across ticks is a bug; hold the id and resolve it,
 * which is the only path that performs the generation check.
 */
public final class Entity {

    private int id = EntityId.NULL;
    private long componentMask;
    private boolean active;
    private final Component[] components = new Component[ComponentTypeRegistry.MAX_COMPONENT_TYPES];

    /** The packed id (D04-S6.1). */
    public int id() {
        return id;
    }

    /** One bit per component type present, for family matching without touching the slot array. */
    public long componentMask() {
        return componentMask;
    }

    /**
     * False from the moment {@code destroyEntity} is called until CLEANUP tears it down. An inactive
     * entity is immediately invisible to families, which is what makes destroy-during-iteration safe
     * (D04-S5.5).
     */
    public boolean isActive() {
        return active;
    }

    /** The component in a type slot, or null. */
    public Component componentAt(int typeIndex) {
        return components[typeIndex];
    }

    // ---- Package-private lifecycle; only World mutates an entity -------------------

    void initialize(int id) {
        this.id = id;
        this.componentMask = 0L;
        this.active = true;
        Arrays.fill(components, null);
    }

    void deactivate() {
        this.active = false;
    }

    void put(int typeIndex, Component component) {
        components[typeIndex] = component;
        componentMask |= 1L << typeIndex;
    }

    Component take(int typeIndex) {
        Component previous = components[typeIndex];
        components[typeIndex] = null;
        componentMask &= ~(1L << typeIndex);
        return previous;
    }

    Component[] components() {
        return components;
    }

    void clear() {
        Arrays.fill(components, null);
        componentMask = 0L;
        active = false;
        id = EntityId.NULL;
    }

    @Override
    public String toString() {
        return "Entity(" + EntityId.toString(id) + (active ? "" : ", inactive") + ")";
    }
}
