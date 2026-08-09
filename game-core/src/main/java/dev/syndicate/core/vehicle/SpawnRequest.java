/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.AssetId;
import java.util.Objects;

/**
 * One "put this vehicle in the world" instruction, waiting for slot 5
 * (docs/04_entity_component_model.md#D04-S4.4 row 5,
 * docs/11_ai_bots_and_match_simulation.md#D11-S5.6).
 *
 * <p>A request rather than a direct call, because everything that wants a vehicle spawned wants it
 * from a different phase of the tick: {@code MatchFlowSystem} (4) respawns a dead player,
 * {@code BotDecisionSystem} (3) fills empty seats at match start, and the match bootstrap places the
 * starting grid before any system has run. Funnelling them all through slot 5 means a vehicle is
 * only ever created at one point in the tick, which is what keeps the entity ids two peers allocate
 * in the same order (D04-R24).
 *
 * @param assemblyId which assembly to instantiate; refused with a log line if it is not loaded
 * @param spawnTransform where the vehicle's chassis mesh starts, in world space
 * @param ownerEntity the player or bot that will drive it, or {@link EntityId#NULL} for an unowned
 *     vehicle
 * @param teamId which team it fights for
 * @param sequence the order this request was made in; slot 5 drains in ascending sequence so two
 *     peers processing the same requests create entities in the same order (G3)
 */
public record SpawnRequest(AssetId assemblyId, Matrix4 spawnTransform, int ownerEntity, int teamId, long sequence) {

    public SpawnRequest {
        Objects.requireNonNull(assemblyId, "assemblyId");
        // Copied: the caller usually hands over a scratch matrix it is about to reuse, and a request
        // that queued a reference would spawn the last caller's position for everyone in the queue.
        spawnTransform = new Matrix4(spawnTransform == null ? new Matrix4() : spawnTransform);
    }
}
