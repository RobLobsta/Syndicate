/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

/**
 * Packing and unpacking of {@code EntityId} (docs/04_entity_component_model.md#D04-S6.1).
 *
 * <p>An id is a {@code uint32} held in a Java {@code int}: low 24 bits are the index into the
 * world's entity array, high 8 bits are a generation counter. The generation is what makes a stale
 * reference detectable — when an index is recycled, its generation increments, so an id captured
 * before the recycle fails the liveness check instead of silently addressing a different entity.
 *
 * <p>Ids are never persisted and never appear in authored content (D00-R20). For the wire, the
 * authority assigns a {@code NetworkId} that is never recycled (D04-S6.2).
 */
public final class EntityId {

    /** The null/invalid entity. Never allocated (D04-R5). */
    public static final int NULL = 0;

    /** The match singleton's reserved id (D04-R5). */
    public static final int MATCH = 1;

    /** Entity capacity per world (D04-R10). Exceeding it is fatal, never a silent grow. */
    public static final int MAX_ENTITIES = 16384;

    /** Indices the authority allocates from (D04-R24). */
    public static final int AUTHORITY_INDEX_MIN = 2;

    public static final int AUTHORITY_INDEX_MAX = 12287;

    /** Indices a client allocates its own effect/debris entities from, disjoint from the above. */
    public static final int CLIENT_LOCAL_INDEX_MIN = 12288;

    public static final int CLIENT_LOCAL_INDEX_MAX = 16383;

    private static final int INDEX_MASK = 0x00FF_FFFF;
    private static final int GENERATION_SHIFT = 24;
    private static final int GENERATION_MASK = 0xFF;

    private EntityId() {
        throw new AssertionError("no instances");
    }

    /** Packs an index and generation into an id (D04-S5.1). */
    public static int pack(int index, int generation) {
        if (index < 0 || index > INDEX_MASK) {
            throw new IllegalArgumentException("entity index out of range: " + index);
        }
        return ((generation & GENERATION_MASK) << GENERATION_SHIFT) | (index & INDEX_MASK);
    }

    /** The array index this id addresses. */
    public static int index(int id) {
        return id & INDEX_MASK;
    }

    /**
     * The generation this id was minted with. Eight bits, and it wraps; wrapping is safe because an
     * id is only ever compared against the index's <em>current</em> generation, and free indices are
     * recycled FIFO so a wrap collision is practically unreachable within a match (D04-R11).
     */
    public static int generation(int id) {
        return (id >>> GENERATION_SHIFT) & GENERATION_MASK;
    }

    /** The next generation for an index being freed. */
    public static int nextGeneration(int generation) {
        return (generation + 1) & GENERATION_MASK;
    }

    /** True when the index falls in the range the authority allocates from (D04-R24). */
    public static boolean isAuthorityIndex(int index) {
        return index >= AUTHORITY_INDEX_MIN && index <= AUTHORITY_INDEX_MAX;
    }

    /** True when the index falls in the client-local range (D04-R24). */
    public static boolean isClientLocalIndex(int index) {
        return index >= CLIENT_LOCAL_INDEX_MIN && index <= CLIENT_LOCAL_INDEX_MAX;
    }

    /** Human-readable form for logs and assertion messages: {@code #1234g2}. */
    public static String toString(int id) {
        if (id == NULL) {
            return "#null";
        }
        return "#" + index(id) + "g" + generation(id);
    }
}
