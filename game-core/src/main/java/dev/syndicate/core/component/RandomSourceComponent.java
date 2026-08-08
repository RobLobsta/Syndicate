/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.util.StreamId;
import java.util.EnumMap;
import java.util.Map;

/**
 * The match's random state, captured for snapshot and replay
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/06_physics_simulation.md#D06-S5.8).
 *
 * <p>{@code World} already holds the live {@code RandomSource}; this component holds the
 * <em>serialisable</em> view of it. G4 requires a replay from a given seed to reproduce a match
 * exactly, and that only holds if a rollback restores each stream's position as well as its seed —
 * a stream rewound to tick N must be about to produce the same number it produced then, not merely
 * to have started from the same seed.
 *
 * <p>An {@code EnumMap} rather than a hash map: it iterates in enum declaration order, which makes
 * serialisation byte-stable across runs and platforms (G3).
 */
public final class RandomSourceComponent implements Component {

    /** The seed every stream derives from. */
    public long matchSeed;

    /** Each subsystem stream's current PCG state, keyed by stream. */
    public final Map<StreamId, Long> streams = new EnumMap<>(StreamId.class);

    @Override
    public void reset() {
        matchSeed = 0L;
        streams.clear();
    }
}
