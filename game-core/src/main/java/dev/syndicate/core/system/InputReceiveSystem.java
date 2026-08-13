/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.net.NetworkAuthority;
import java.util.Objects;

/**
 * Schedule slot 2: the authority reads its peers' input
 * (docs/04_entity_component_model.md#D04-S4.4, docs/10_networking_multiplayer.md#D10-S5.2).
 *
 * <p>This is the <b>only</b> point in the tick at which a message from a client can change anything,
 * and that is the point of it. The transport is polled here rather than delivering asynchronously,
 * so a packet cannot arrive between two systems and change the world underneath one of them (G2).
 *
 * <p>It runs before {@code BotDecisionSystem} (3) and both write the same
 * {@code PlayerInputComponent}, which is what makes a bot and a human indistinguishable to every
 * system downstream (G17).
 *
 * <p>What arrives here is never trusted. Every command has passed {@code InputValidator} before it
 * reaches a component, and the authority's own simulation decides what that intent produces —
 * a client cannot assert a position, a health value or a hit (G15, D10-R26).
 */
public final class InputReceiveSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 2;

    private final NetworkAuthority authority;

    public InputReceiveSystem(NetworkAuthority authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
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
    public void update(World world, float dtSeconds, long tick) {
        authority.receive(world, tick);
        authority.applyInputs(world, tick);
    }
}
