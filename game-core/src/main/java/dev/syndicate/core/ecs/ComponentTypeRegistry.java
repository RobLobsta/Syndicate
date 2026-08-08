/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps component classes to dense indices used for bit masks and array slots
 * (docs/04_entity_component_model.md#D04-S6.3).
 *
 * <p>Indices come from an append-only registration order, never from class-name hashing: the same
 * index is intended to be the component's wire type id (D04-R22), so a reordering would silently
 * change the protocol between two builds that both believe they agree. Retiring a type leaves its
 * index permanently burned.
 *
 * <p><b>Not yet wire-stable.</b> D04-R22 requires indices to come from a checked-in, append-only
 * {@code component_types.txt}. Until replication exists and that list is written, indices derive
 * from first-use order, which is stable within a build but not across builds. Nothing may serialise
 * an index to the wire before {@link #registerAll} is fed that list — the handshake hash of D04-E10
 * is what will make a mismatch visible.
 *
 * <p>The mask is 64 bits wide (D04-R26). Exceeding it is a hard failure rather than a silent
 * truncation, because a truncated mask makes family matching wrong in a way that looks like a
 * gameplay bug.
 */
public final class ComponentTypeRegistry {

    /** Bits available in an entity's component mask (D04-R26). */
    public static final int MAX_COMPONENT_TYPES = 64;

    private final Map<Class<? extends Component>, Integer> indices = new LinkedHashMap<>();
    private final Class<?>[] byIndex = new Class<?>[MAX_COMPONENT_TYPES];
    private int nextIndex;

    /**
     * Registers a component type and returns its index. Registering the same type twice returns the
     * existing index, so a registry can be built idempotently at world construction.
     *
     * @throws IllegalStateException when more than {@value #MAX_COMPONENT_TYPES} types are
     *     registered; the fix is the two-word bitset specified in D04-S6.3, not a wider hash
     */
    public int register(Class<? extends Component> type) {
        Integer existing = indices.get(type);
        if (existing != null) {
            return existing;
        }
        if (nextIndex >= MAX_COMPONENT_TYPES) {
            throw new IllegalStateException("component catalogue exceeds " + MAX_COMPONENT_TYPES
                    + " types; D04-S6.3 specifies a two-word bitset for this case");
        }
        int index = nextIndex++;
        indices.put(type, index);
        byIndex[index] = type;
        return index;
    }

    /**
     * Registers types in a fixed order, which is how the wire-stable list of D04-R22 will be
     * applied once {@code component_types.txt} exists. Call before anything else touches the
     * registry; it is a no-op for types already present, so a late call cannot silently renumber.
     */
    public void registerAll(Iterable<Class<? extends Component>> types) {
        for (Class<? extends Component> type : types) {
            register(type);
        }
    }

    /**
     * The index of an already-registered type.
     *
     * @throws IllegalArgumentException if the type was never registered — an unregistered type in a
     *     family query would match nothing and look like a missing entity
     */
    public int indexOf(Class<? extends Component> type) {
        Integer index = indices.get(type);
        if (index == null) {
            throw new IllegalArgumentException("component type not registered: " + type.getName());
        }
        return index;
    }

    /**
     * The index of a type, or {@code -1} when it was never registered.
     *
     * <p>Used by the read paths of {@code World}: a type nobody has ever attached cannot be on any
     * entity, so a query for it is legitimately "absent" rather than an error. D04-E1 requires
     * component access to return null rather than throw.
     */
    public int indexOfOrAbsent(Class<? extends Component> type) {
        Integer index = indices.get(type);
        return index == null ? -1 : index;
    }

    /** True when the type has been registered. */
    public boolean isRegistered(Class<? extends Component> type) {
        return indices.containsKey(type);
    }

    /** The single-bit mask for a registered type. */
    public long bit(Class<? extends Component> type) {
        return 1L << indexOf(type);
    }

    /** The type at an index, or null. For diagnostics only. */
    public Class<?> typeAt(int index) {
        return index >= 0 && index < byIndex.length ? byIndex[index] : null;
    }

    /** How many types are registered. */
    public int size() {
        return nextIndex;
    }
}
