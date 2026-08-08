/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;

/**
 * Which side an entity is on (docs/04_entity_component_model.md#D04-S4.3.4).
 *
 * <p>{@link #FREE_FOR_ALL} is a distinct value rather than "team 0" so that friendly-fire and
 * scoring logic can distinguish "no teams in this mode" from "everyone happens to be on team 0",
 * which would make every player friendly to every other.
 */
public final class TeamComponent implements Component {

    /** No team; every other entity is hostile. */
    public static final int FREE_FOR_ALL = -1;

    /** The team id, or {@link #FREE_FOR_ALL}. */
    public int teamId = FREE_FOR_ALL;

    @Override
    public void reset() {
        teamId = FREE_FOR_ALL;
    }
}
