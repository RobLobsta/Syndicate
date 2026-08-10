/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.BotDifficulty;

/**
 * Who a participant is (docs/04_entity_component_model.md#D04-R4 {@code PLAYER} archetype,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.6).
 *
 * <p>A <em>player</em> entity is not a vehicle. It outlives every vehicle it drives, which is what
 * makes a respawn expressible at all: the score, the team and the chosen assembly stay put while
 * the thing being shot at is replaced. {@link ControlledVehicleComponent} holds the link to
 * whichever vehicle is current.
 *
 * <p><b>{@link #isBot} is the only place the distinction is recorded.</b> Every gameplay system
 * downstream reads {@code PlayerInputComponent} and cannot tell a bot from a human (D11-R2, G17);
 * this flag exists for the match report, for the scoreboard, and for D11-R11's rule that a human
 * joining a full match displaces the oldest <em>bot</em> rather than a person.
 */
public final class PlayerIdentityComponent implements Component {

    /**
     * A stable, ascending identifier assigned in join order.
     *
     * <p>Separate from the entity id because D11-S5.7 iterates players "sorted by playerId" and an
     * entity id carries a generation counter that changes when an index is recycled. Two peers must
     * order the same players the same way whether or not either has recycled an index (G3).
     */
    public int playerId;

    /** What a scoreboard shows. Never read by simulation code. */
    public String displayName = "";

    /** Whether this participant is driven by {@code BotDecisionSystem} rather than by a person. */
    public boolean isBot;

    /** The bot's difficulty, or null for a human. */
    public BotDifficulty botDifficulty;

    /** The tick this player joined; D11-R11 removes bots oldest-first, which means lowest here. */
    public long joinTick;

    /** The assembly this player respawns in. */
    public AssetId selectedAssemblyId;

    @Override
    public void reset() {
        playerId = 0;
        displayName = "";
        isBot = false;
        botDifficulty = null;
        joinTick = 0L;
        selectedAssemblyId = null;
    }
}
