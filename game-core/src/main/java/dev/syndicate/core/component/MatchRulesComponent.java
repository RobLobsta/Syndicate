/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.GameMode;

/**
 * The rules the match is being played under
 * (docs/04_entity_component_model.md#D04-S4.3.5, docs/01_product_game_design.md#D01-S4.2).
 *
 * <p>A component on the match singleton rather than a static configuration object, because the
 * offline match simulator of D11-S5.8 runs many worlds in one process with different rules — and
 * because rules are replicated, so a joining client learns them the same way it learns everything
 * else.
 */
public final class MatchRulesComponent implements Component {

    /** Which mode is being played. */
    public GameMode mode = GameMode.DEATHMATCH;

    /** Score at which the match ends, or {@code 0} for no score limit. */
    public int scoreLimit;

    /** Ticks between a player's death and their respawn. */
    public int respawnDelayTicks;

    /** Whether damage to teammates counts. Always false in free-for-all modes. */
    public boolean friendlyFire;

    @Override
    public void reset() {
        mode = GameMode.DEATHMATCH;
        scoreLimit = 0;
        respawnDelayTicks = 0;
        friendlyFire = false;
    }
}
