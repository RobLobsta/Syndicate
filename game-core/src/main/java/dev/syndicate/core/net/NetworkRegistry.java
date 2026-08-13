/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

import dev.syndicate.core.ecs.EntityId;
import java.util.Map;
import java.util.TreeMap;

/**
 * The two-way index between wire identities and entity ids
 * (docs/04_entity_component_model.md#D04-S6.2).
 *
 * <p>On the authority it also allocates: ids ascend from {@link NetworkId#FIRST} and are
 * <b>never recycled within a match</b> (D04-R25). That is the whole reason a network id exists
 * separately from an entity id — entity indices recycle, so a packet delayed across a recycle would
 * address whatever now occupies the index, while a stale network id resolves to "unknown, ignore".
 *
 * <p>Ids are allocated in <b>contiguous blocks</b>, one block per spawned vehicle covering the
 * vehicle and every part in ascending slot-path order. A client that is told the block's base can
 * therefore derive every part's id by building the same assembly through the same factory, which is
 * what keeps a 40-part vehicle's spawn message at one line instead of forty (DEC-059).
 */
public final class NetworkRegistry {

    private final TreeMap<Integer, Integer> entityByNetworkId = new TreeMap<>();
    private final TreeMap<Integer, Integer> networkIdByEntity = new TreeMap<>();
    private int nextNetworkId = NetworkId.FIRST;

    /**
     * Reserves {@code count} consecutive ids and returns the first.
     *
     * @throws IllegalArgumentException when {@code count} is not positive
     */
    public int allocateBlock(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("a block must contain at least one id, got " + count);
        }
        int base = nextNetworkId;
        for (int i = 0; i < count; i++) {
            nextNetworkId = NetworkId.next(nextNetworkId);
        }
        return base;
    }

    /** The next id that would be allocated. For tests and diagnostics. */
    public int peekNextNetworkId() {
        return nextNetworkId;
    }

    /**
     * Moves the allocator past {@code networkId}, so a client that is told about ids it did not
     * allocate cannot later hand one of them out for a locally spawned entity.
     */
    public void observeAllocated(int networkId) {
        if (Integer.compareUnsigned(networkId, nextNetworkId) >= 0) {
            nextNetworkId = NetworkId.next(networkId);
        }
    }

    /** Records that {@code networkId} names {@code entityId}. */
    public void bind(int networkId, int entityId) {
        if (!NetworkId.isValid(networkId)) {
            throw new IllegalArgumentException("cannot bind NetworkId.NONE");
        }
        entityByNetworkId.put(networkId, entityId);
        networkIdByEntity.put(entityId, networkId);
        observeAllocated(networkId);
    }

    /** The entity a network id names, or {@link EntityId#NULL} when this peer has never seen it. */
    public int entityOf(int networkId) {
        Integer entityId = entityByNetworkId.get(networkId);
        return entityId == null ? EntityId.NULL : entityId;
    }

    /** The network id of an entity, or {@link NetworkId#NONE}. */
    public int networkIdOf(int entityId) {
        Integer networkId = networkIdByEntity.get(entityId);
        return networkId == null ? NetworkId.NONE : networkId;
    }

    /** Forgets a network id. The id itself is never reissued (D04-R25). */
    public void unbind(int networkId) {
        Integer entityId = entityByNetworkId.remove(networkId);
        if (entityId != null) {
            networkIdByEntity.remove(entityId);
        }
    }

    /** Forgets whatever id names this entity. */
    public void unbindEntity(int entityId) {
        Integer networkId = networkIdByEntity.remove(entityId);
        if (networkId != null) {
            entityByNetworkId.remove(networkId);
        }
    }

    /** Every binding, in ascending network id order (G3). */
    public Iterable<Map.Entry<Integer, Integer>> bindings() {
        return entityByNetworkId.entrySet();
    }

    /** How many entities are currently bound. */
    public int size() {
        return entityByNetworkId.size();
    }

    /** Drops every binding. The allocator is <b>not</b> rewound: a match's ids are spent for good. */
    public void clearBindings() {
        entityByNetworkId.clear();
        networkIdByEntity.clear();
    }
}
