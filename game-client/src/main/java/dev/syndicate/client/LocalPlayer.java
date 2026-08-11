/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import dev.syndicate.core.component.ControlledVehicleComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PlayerIdentityComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;

/**
 * Which player this process is, and what they are currently driving.
 *
 * <p>Four client systems need the same answer — input writes to that vehicle, the camera follows it,
 * the HUD reports it and the engine loop is pitched from it — and deriving it four times invites
 * them to disagree for a frame after a respawn, which is exactly when a player is looking. So it is
 * derived once, here, and read by all of them.
 *
 * <p>Purely a client concern (G6, G17). The simulation has no notion of a local player: a bot and a
 * human write the same {@code PlayerInputComponent}, which is what lets the whole game run headless.
 */
public final class LocalPlayer {

    private Family drivenVehicles;
    private int playerEntity = EntityId.NULL;

    /** The player entity this client controls, or {@link EntityId#NULL} before it has joined. */
    public int playerEntity() {
        return playerEntity;
    }

    /** Names the player entity, once the client has joined the match. */
    public void setPlayerEntity(int playerEntity) {
        this.playerEntity = playerEntity;
    }

    /** The display name to highlight on the scoreboard, or null. */
    public String displayName(World world) {
        PlayerIdentityComponent identity = world.getComponent(playerEntity, PlayerIdentityComponent.class);
        return identity == null ? null : identity.displayName;
    }

    /**
     * The vehicle this client is driving, or {@link EntityId#NULL} while dead or in the lobby.
     *
     * <p>Resolved through {@code ControlledVehicleComponent} when a player entity is known, and by
     * falling back to the lowest-numbered vehicle otherwise — which is what makes a client useful
     * before it has joined anything, such as when watching a bots-only match.
     */
    public int vehicleEntity(World world) {
        if (drivenVehicles == null) {
            drivenVehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class));
        }
        if (playerEntity != EntityId.NULL) {
            ControlledVehicleComponent controlled = world.getComponent(playerEntity, ControlledVehicleComponent.class);
            if (controlled != null && world.isAlive(controlled.vehicleEntity)) {
                return controlled.vehicleEntity;
            }
            // The controlled reference is authoritative but is written by slot 5 on the spawn tick;
            // the owner back-reference on the vehicle is written in the same tick, so either one
            // finding it is enough and neither is trusted alone.
            int[] entityIds = drivenVehicles.snapshot();
            for (int i = 0; i < drivenVehicles.size(); i++) {
                OwnerComponent owner = world.getComponent(entityIds[i], OwnerComponent.class);
                if (owner != null && owner.ownerEntity == playerEntity) {
                    return entityIds[i];
                }
            }
            return EntityId.NULL;
        }
        return drivenVehicles.isEmpty() ? EntityId.NULL : drivenVehicles.snapshot()[0];
    }
}
