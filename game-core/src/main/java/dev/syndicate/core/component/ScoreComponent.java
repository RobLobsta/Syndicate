/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;

/**
 * A player's tally (docs/04_entity_component_model.md#D04-S4.3.5).
 *
 * <p>Lives on the {@code PLAYER} entity, which outlives the vehicles that player drives — a score
 * that lived on the vehicle would reset on every respawn.
 */
public final class ScoreComponent implements Component {

    public int kills;

    public int assists;

    public int deaths;

    /** Total hit points removed from enemies; drives assist credit and the end-of-match summary. */
    public float damageDealt;

    /** Mode-specific objective points, separate from kills so scoring rules can weight them. */
    public int objectiveScore;

    @Override
    public void reset() {
        kills = 0;
        assists = 0;
        deaths = 0;
        damageDealt = 0f;
        objectiveScore = 0;
    }
}
