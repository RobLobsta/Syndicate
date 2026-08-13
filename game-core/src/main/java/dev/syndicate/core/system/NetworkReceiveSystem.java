/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.net.NetworkClient;
import java.util.Objects;

/**
 * Schedule slot 19: a client sends its intent and applies what the authority sent back
 * (docs/04_entity_component_model.md#D04-S4.4, docs/10_networking_multiplayer.md#D10-S5.5).
 *
 * <p>Three steps, and the order of them is the whole design:
 *
 * <ol>
 *   <li><b>Record the prediction.</b> Physics ran at slot 10, so by now this tick's predicted
 *       transform for the local vehicle exists. It is stored against the tick number, which is what
 *       lets slot 20 later compare like with like — the authority's state for tick T against what
 *       this client thought tick T would look like, not against wherever the car is now.
 *   <li><b>Send the input.</b> This tick's command plus the redundancy window of D10-R4, and the
 *       acknowledgement of the newest snapshot riding along with it.
 *   <li><b>Apply what arrived.</b> Remote entities are set from the snapshot; the local vehicle's
 *       authoritative state is handed to slot 20 instead (D10-R19).
 * </ol>
 *
 * <p><b>The client's outbound send lives here rather than in slot 18</b> (DEC-062). D04-S4.4 marks
 * slot 18 as authority-only, so a pure client has no send slot at all; putting the input packet in
 * slot 19 keeps a client's whole network turn in the one slot its mode actually schedules.
 */
public final class NetworkReceiveSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 19;

    private final NetworkClient client;

    public NetworkReceiveSystem(NetworkClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Phase phase() {
        return Phase.NET;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        client.recordPrediction(world, tick);
        sendLocalInput(world, tick);
        client.receive(world, tick);
    }

    private void sendLocalInput(World world, long tick) {
        int vehicle = client.localVehicleEntity();
        PlayerInputComponent input =
                vehicle == EntityId.NULL ? null : world.getComponent(vehicle, PlayerInputComponent.class);
        if (input == null) {
            // Still sent, and deliberately: a peer with no vehicle — respawning, or still syncing —
            // is a peer the authority must still hear from, or its timeout starts counting down
            // (D10-S5.8).
            client.sendInput(world, tick, 0f, 0f, 0f, 0f, 0f, 0);
            return;
        }
        client.sendInput(
                world,
                tick,
                input.throttle,
                input.steer,
                input.brake,
                input.aimYawRad,
                input.aimPitchRad,
                input.fireMask);
    }
}
