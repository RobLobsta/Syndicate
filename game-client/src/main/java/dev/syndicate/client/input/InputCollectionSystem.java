/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.input;

import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import java.util.Objects;

/**
 * Schedule slot 1: reads the local player's device and writes their intent
 * (docs/04_entity_component_model.md#D04-S4.4 row 1).
 *
 * <p>Client-only, and the first slot in the tick for a reason: everything downstream reads
 * {@code PlayerInputComponent}, and a human's input has to be in place before
 * {@code VehicleControlSystem} (7) and {@code WeaponSystem} (8) look at it. It writes exactly the
 * component a bot writes at slot 3 and nothing else, so no system below can tell a person from an
 * AI (G17) — which is the same property that lets the whole game run headless.
 *
 * <p><b>Which device is not this system's business.</b> That is {@link InputRouter}'s, decided from
 * which one the player last touched rather than from a settings screen. This system's job is the
 * schedule slot, finding the local player's vehicle, and honouring the match's input gate.
 *
 * <p>D01-R21/R23 close that gate during {@code COUNTDOWN} and {@code ENDING}, and slot 4 enforces it
 * by erasing intent after every writer has run — so this system does not check the phase. Writing
 * input that slot 4 will zero three slots later is correct and is one fewer copy of a rule to keep
 * in step.
 */
public final class InputCollectionSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 1;

    private final InputRouter router;
    private final PlayerInputComponent scratch = new PlayerInputComponent();

    private Family drivenVehicles;
    private int localPlayerEntity = EntityId.NULL;

    public InputCollectionSystem(InputRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /**
     * Names the player this client is driving for.
     *
     * <p>Set by the client when it joins or spawns. Until it is set, the system drives the first
     * vehicle it finds, which is what makes a single-player session work before any lobby exists.
     */
    public void setLocalPlayer(int playerEntity) {
        this.localPlayerEntity = playerEntity;
    }

    /** Which device is currently driving, for a HUD prompt. */
    public InputDeviceKind activeDevice() {
        return router.activeKind();
    }

    @Override
    public Phase phase() {
        return Phase.INPUT;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        drivenVehicles = world.family(ComponentQuery.all(PlayerInputComponent.class, VehicleChassisComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int vehicle = localVehicle(world);
        if (vehicle == EntityId.NULL) {
            // Still poll, so the router keeps tracking which device the player is on while they
            // are dead or in the lobby — otherwise the first frame after a respawn is on whichever
            // device happened to be active before they died.
            router.poll(scratch, dtSeconds);
            return;
        }
        PlayerInputComponent input = world.getComponent(vehicle, PlayerInputComponent.class);
        if (input == null) {
            return;
        }
        if (router.poll(input, dtSeconds)) {
            input.commandTick = tick;
            input.sequence++;
        }
    }

    /**
     * The vehicle this client drives.
     *
     * <p>Ascending entity order and the first match, so a client with no local player set — a test,
     * or a single-player session before a lobby exists — drives something rather than nothing.
     */
    private int localVehicle(World world) {
        int[] entityIds = drivenVehicles.snapshot();
        int count = drivenVehicles.size();
        for (int i = 0; i < count; i++) {
            int vehicle = entityIds[i];
            if (localPlayerEntity == EntityId.NULL) {
                return vehicle;
            }
            OwnerComponent owner = world.getComponent(vehicle, OwnerComponent.class);
            if (owner != null && owner.ownerEntity == localPlayerEntity) {
                return vehicle;
            }
        }
        return EntityId.NULL;
    }
}
