/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;

/**
 * Which vehicle a player is currently driving, and when they last lost one
 * (docs/04_entity_component_model.md#D04-R4 {@code ControlledVehicleRef},
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.7 {@code handleRespawns}).
 *
 * <p>The link is stored on the <em>player</em> and mirrored by {@link OwnerComponent} on the
 * vehicle, deliberately in both directions. Scoring walks vehicle → player at the instant of a kill,
 * when the vehicle is about to be destroyed; respawn walks player → vehicle every tick, when the
 * vehicle may already be gone. Deriving either from the other would mean a scan of every entity in
 * the tick that can least afford one.
 *
 * <p>{@link #deathTick} is {@link #NEVER_DIED} until the player first loses a vehicle. That is not
 * the same as "died at tick 0": a player who has not yet been given a vehicle must respawn
 * immediately, and one who died on the first tick must wait out the delay.
 */
public final class ControlledVehicleComponent implements Component {

    /** {@link #deathTick} before this player has ever lost a vehicle. */
    public static final long NEVER_DIED = Long.MIN_VALUE;

    /** The vehicle being driven, or {@link EntityId#NULL} while awaiting a spawn. */
    public int vehicleEntity = EntityId.NULL;

    /** The tick this player's last vehicle was destroyed, or {@link #NEVER_DIED}. */
    public long deathTick = NEVER_DIED;

    /**
     * The tick a spawn was last queued for this player, or {@link #NEVER_DIED}.
     *
     * <p>Distinct from {@link #deathTick} so that a refused spawn — an assembly that failed to load,
     * an arena with no free point — backs off instead of re-queueing every tick forever, without
     * that back-off pretending the player died again.
     */
    public long spawnRequestedTick = NEVER_DIED;

    @Override
    public void reset() {
        vehicleEntity = EntityId.NULL;
        deathTick = NEVER_DIED;
        spawnRequestedTick = NEVER_DIED;
    }
}
