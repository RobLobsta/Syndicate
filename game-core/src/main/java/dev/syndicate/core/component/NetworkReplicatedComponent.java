/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.net.NetworkId;
import dev.syndicate.core.net.ReplicationClass;

/**
 * Marks an entity as replicated, and says how
 * (docs/04_entity_component_model.md#D04-S4.3.5).
 *
 * <p>Replication is opt-in per entity. An entity without this component is invisible to
 * {@code NetworkSendSystem} entirely — which is how client-only entities (effects, cosmetic debris)
 * are kept off the wire by construction rather than by a filter that could be forgotten (G6).
 *
 * <p>{@link #lastSentTick} is server-side only and classified {@code L}: it describes this
 * process's send history, so replicating it would be sending a peer a fact about itself.
 */
public final class NetworkReplicatedComponent implements Component {

    /** The wire identity, assigned by the authority and never recycled in a match (D04-R25). */
    public int networkId = NetworkId.NONE;

    /** How often this entity's state goes out (D10-S5.3). */
    public ReplicationClass replicationClass = ReplicationClass.LOW_FREQ;

    /** Which peer drives this entity, so its own predicted state is not corrected against itself. */
    public int ownerPeerId = -1;

    /** The last tick a snapshot carried this entity. Authority-side only. */
    public long lastSentTick;

    @Override
    public void reset() {
        networkId = NetworkId.NONE;
        replicationClass = ReplicationClass.LOW_FREQ;
        ownerPeerId = -1;
        lastSentTick = 0L;
    }
}
