/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

/**
 * A named block of data attached to an entity (docs/04_entity_component_model.md#D04-S4.5).
 *
 * <p>Components are data only: public fields or records, no behaviour beyond trivial accessors,
 * {@link #reset()}, and validation (D04-R2). All behaviour lives in systems. That restriction is
 * what makes replication and snapshot/rollback tractable — a component with logic in it cannot be
 * serialised, diffed, or rewound generically.
 *
 * <p>An ArchUnit rule enforces the no-behaviour restriction (AC-D04-2).
 */
public interface Component {

    /**
     * Returns this instance to its pristine state so it can be pooled and reused.
     *
     * <p>D04-R17: every field must return to its declared default. A pooled component that leaks a
     * stale field into a newly spawned entity is a correctness bug, not a performance one, and it
     * presents as an impossible game state several ticks later.
     */
    void reset();
}
