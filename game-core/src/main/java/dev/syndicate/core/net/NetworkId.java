/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.net;

/**
 * The identity an entity is known by on the wire
 * (docs/04_entity_component_model.md#D04-S6.2).
 *
 * <p>A {@code uint32} carried in an {@code int}, assigned by the authority, increasing from 1 and
 * <b>never recycled within a match</b> (D04-R25). That is the whole point of its existing
 * separately from {@code EntityId}: entity indices recycle, so a delayed packet naming a recycled
 * index would address the wrong object, while a stale {@code NetworkId} resolves to "unknown,
 * ignore" (G16).
 *
 * <p>A holder of constants and static helpers rather than a wrapper type: the id is stored in a
 * component field on a replication hot path, and boxing it per entity per snapshot is exactly the
 * per-tick garbage D04-S5.6 forbids.
 */
public final class NetworkId {

    /** Not yet assigned, or "no entity". Never a valid wire identity. */
    public static final int NONE = 0;

    /** The first id an authority hands out (D04-R25). */
    public static final int FIRST = 1;

    private NetworkId() {}

    /** True when {@code id} could name a replicated entity. */
    public static boolean isValid(int id) {
        return id != NONE;
    }

    /**
     * The id after {@code previous}.
     *
     * @throws IllegalStateException on wrap. A match that allocates four billion network ids has a
     *     spawn leak; silently wrapping would resurrect the recycling problem this type exists to
     *     avoid.
     */
    public static int next(int previous) {
        if (previous == -1) {
            throw new IllegalStateException("NetworkId space exhausted within one match (D04-R25)");
        }
        return previous + 1;
    }

    /** Unsigned rendering, since ids past 2^31 are legal and would otherwise print negative. */
    public static String toString(int id) {
        return id == NONE ? "net:none" : "net:" + Integer.toUnsignedString(id);
    }
}
