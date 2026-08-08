/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

/**
 * A unit of behaviour executed at a fixed point in the schedule
 * (docs/04_entity_component_model.md#D04-S4.4, #D04-S4.5).
 *
 * <p>Named {@code EntitySystem} rather than {@code System}: D00-E9 anticipates exactly this
 * collision with a JDK type and directs that code disambiguate it. Concrete implementations still
 * follow the {@code <Noun>System} convention of D02-R15.
 *
 * <p>Systems are stateless with respect to gameplay (D04-R3). Cross-tick state lives in a component
 * or an explicitly declared, snapshot-able store — never in an ad-hoc field, which would be
 * invisible to replication and to rollback.
 *
 * <p>Systems communicate only through components and the event bus; direct system-to-system calls
 * are prohibited so that the schedule remains the single description of causality (D04-R13).
 */
public interface EntitySystem {

    /** The phase this system runs in. */
    Phase phase();

    /**
     * The system's fixed number from the D04-S4.4 catalogue. The schedule is sorted by it, and a
     * mode filter may drop systems but never reorder them (D03-S5.2, G3).
     */
    int order();

    /** Called once when the system is registered, before the first tick. */
    default void initialize(World world) {
        // Most systems need no setup; those that cache families override this.
    }

    /**
     * Advances this system by exactly one fixed step.
     *
     * @param dtSeconds always {@code SimulationConstants.TICK_DT}; it is a parameter for clarity at
     *     the call site, never a variable the caller may choose (G2, D03-R10)
     * @param tick the current tick number — the only clock a simulation system may read (G5)
     */
    void update(World world, float dtSeconds, long tick);

    /** Releases anything this system owns. Called in reverse registration order (D03-S5.6). */
    default void dispose() {
        // Systems that own no native or pooled resource need nothing here.
    }

    /** A stable name for profiling output and error messages. */
    default String systemName() {
        return getClass().getSimpleName();
    }
}
