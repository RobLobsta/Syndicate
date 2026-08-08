/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import java.util.Arrays;

/**
 * A cached, incrementally maintained set of entities matching a {@link ComponentQuery}
 * (docs/04_entity_component_model.md#D04-S5.7).
 *
 * <p>Membership is kept in an <em>ascending, sorted</em> id array, which is the mechanism behind G3:
 * a system that iterates a family visits entities in the same order on every peer and on every run,
 * so the simulation cannot depend on hash order or insertion history. This is the single reason
 * this class exists rather than a {@code HashSet}.
 *
 * <p>Ordering is by packed id, exactly as D04-R9 words it. Because the generation occupies the high
 * bits, an index that has been recycled sorts differently than it did in its previous life — that is
 * still fully deterministic, since every peer derives the same generation from the same spawn and
 * destroy history (D04-R24).
 *
 * <p>{@link #snapshot()} hands out a stable array so a system may add or remove components mid-loop
 * without the collection shifting underneath it (D04-R12).
 */
public final class Family {

    private final ComponentQuery query;
    private int[] members = new int[16];
    private int size;

    /** Reused between ticks; a family's snapshot is consumed before the next call. */
    private int[] snapshot = new int[16];

    Family(ComponentQuery query) {
        this.query = query;
    }

    /** The query this family caches. */
    public ComponentQuery query() {
        return query;
    }

    /** How many entities currently match. */
    public int size() {
        return size;
    }

    /** True when no entity matches. */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * The matching ids in ascending order, valid until the next call on this family.
     *
     * <p>Iterate this rather than the live set: it is what makes structural changes during a loop
     * safe, and it is the array the deterministic ordering guarantee applies to.
     */
    public int[] snapshot() {
        if (snapshot.length < size) {
            snapshot = new int[Math.max(size, snapshot.length * 2)];
        }
        System.arraycopy(members, 0, snapshot, 0, size);
        return snapshot;
    }

    /** Copies the members into a right-sized array. For tests and diagnostics. */
    public int[] toArray() {
        return Arrays.copyOf(members, size);
    }

    /** True when the id is currently a member. */
    public boolean contains(int entityId) {
        return indexOf(entityId) >= 0;
    }

    /**
     * Recomputes membership for one entity after its mask or active flag changed.
     *
     * <p>Called on every component add and remove, so it must stay O(log n) for the search plus the
     * array shift; a full rescan here would be O(entities) per component change and would dominate
     * spawn cost for a 64-part vehicle.
     */
    void onEntityChanged(Entity entity) {
        boolean shouldBeMember = entity.isActive() && query.matches(entity.componentMask());
        int position = indexOf(entity.id());
        if (shouldBeMember && position < 0) {
            insertAt(-(position + 1), entity.id());
        } else if (!shouldBeMember && position >= 0) {
            removeAt(position);
        }
    }

    void onEntityRemoved(int entityId) {
        int position = indexOf(entityId);
        if (position >= 0) {
            removeAt(position);
        }
    }

    void clear() {
        size = 0;
    }

    private int indexOf(int entityId) {
        return Arrays.binarySearch(members, 0, size, entityId);
    }

    private void insertAt(int position, int entityId) {
        if (size == members.length) {
            members = Arrays.copyOf(members, members.length * 2);
        }
        System.arraycopy(members, position, members, position + 1, size - position);
        members[position] = entityId;
        size++;
    }

    private void removeAt(int position) {
        System.arraycopy(members, position + 1, members, position, size - position - 1);
        size--;
    }

    @Override
    public String toString() {
        return "Family(" + query + ", " + size + " members)";
    }
}
