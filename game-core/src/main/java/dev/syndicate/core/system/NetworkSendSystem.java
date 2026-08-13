/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.damage.PartDestroyedEvent;
import dev.syndicate.core.damage.PartDetachedEvent;
import dev.syndicate.core.damage.PartFracturedEvent;
import dev.syndicate.core.damage.VehicleDestroyedEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.net.Messages;
import dev.syndicate.core.net.NetworkAuthority;
import dev.syndicate.core.net.NetworkRegistry;
import dev.syndicate.model.net.StructuralEventType;
import java.util.Objects;

/**
 * Schedule slot 18: the authority tells its peers what happened
 * (docs/04_entity_component_model.md#D04-S4.4, docs/10_networking_multiplayer.md#D10-S5.4).
 *
 * <p>Three jobs, in order. It gives new vehicles their wire identities, it forwards the tick's
 * destruction events on the reliable channel, and it sends each peer a snapshot on the tick that
 * peer's stagger offset selects — 20 Hz per peer, spread across the interval so twelve clients do
 * not all receive a burst on the same tick (D10-S5.2 step 3).
 *
 * <p><b>Structural events are subscribed, not drained.</b> Slots 13 and 14 publish them through
 * {@code emitPipeline}, whose deferred half is delivered at the end of the tick — after this system
 * has already run. So an event raised on tick T is sent on tick T+1, one 16 ms step late on a
 * channel that is reliable and ordered anyway. Draining the same-tick half instead would consume
 * events that {@code ScoreSystem} and the client's presentation systems are also entitled to
 * (DISC-022 is what that mistake looks like).
 */
public final class NetworkSendSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 18;

    private final NetworkAuthority authority;

    private Family vehicles;

    public NetworkSendSystem(NetworkAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
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
    public void initialize(World world) {
        vehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class));
        subscribeToStructuralEvents(world);
    }

    private void subscribeToStructuralEvents(World world) {
        NetworkRegistry registry = authority.registry();
        world.events()
                .subscribe(
                        PartDestroyedEvent.class,
                        event -> authority.enqueueStructural(new Messages.StructuralEvent(
                                StructuralEventType.PART_DESTROYED,
                                registry.networkIdOf(event.vehicleEntity()),
                                registry.networkIdOf(event.partEntity()),
                                event.slotPath(),
                                event.tick())));
        world.events()
                .subscribe(
                        PartFracturedEvent.class,
                        event -> authority.enqueueStructural(new Messages.StructuralEvent(
                                StructuralEventType.PART_FRACTURED,
                                registry.networkIdOf(event.vehicleEntity()),
                                registry.networkIdOf(event.partEntity()),
                                event.slotPath(),
                                event.tick())));
        world.events()
                .subscribe(
                        PartDetachedEvent.class,
                        event -> authority.enqueueStructural(new Messages.StructuralEvent(
                                StructuralEventType.PART_DETACHED,
                                registry.networkIdOf(event.vehicleEntity()),
                                registry.networkIdOf(event.partEntity()),
                                event.slotPath(),
                                event.tick())));
        world.events()
                .subscribe(
                        VehicleDestroyedEvent.class,
                        event -> authority.enqueueStructural(new Messages.StructuralEvent(
                                StructuralEventType.VEHICLE_DESTROYED,
                                registry.networkIdOf(event.vehicleEntity()),
                                0,
                                "",
                                event.tick())));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        authority.replicate(world, tick, vehicles.snapshot(), vehicles.size());
    }
}
