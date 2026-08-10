/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import dev.syndicate.client.input.GamepadSource;
import dev.syndicate.client.input.InputBindings;
import dev.syndicate.client.input.InputCollectionSystem;
import dev.syndicate.client.input.InputRouter;
import dev.syndicate.client.input.KeyboardMouseSource;
import dev.syndicate.client.input.LibGdxDevices;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.system.SystemProvider;
import dev.syndicate.core.system.SystemSlot;
import dev.syndicate.model.RuntimeMode;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The {@link SystemProvider} for the six {@code game-client} slots of D04-S4.4
 * (docs/03_runtime_modes.md#D03-S5.2, DEC-030).
 *
 * <p>{@code game-core} cannot construct a client system — the dependency runs the other way
 * (D02-S5.6) — so the client contributes this and {@code SystemSetFactory} asks it first, falling
 * through to {@code CoreSystemProvider} for everything else.
 *
 * <p>One of the six exists. {@code Interpolation} (22), {@code DamageVisual} (23), {@code Effect}
 * (24), {@code Audio} (25) and {@code Render} (26) arrive with the renderer; returning null for them
 * leaves a gap in the schedule that {@code SystemSetFactory} names at startup, which is what the
 * whole provider mechanism does with an unimplemented slot rather than refusing to boot.
 */
public final class ClientSystemProvider implements SystemProvider {

    private final InputCollectionSystem input;

    /** Builds the provider with the real devices and the shipped bindings. */
    public ClientSystemProvider(Path assetRoot) {
        this(InputBindings.load(Objects.requireNonNull(assetRoot, "assetRoot")));
    }

    /** Builds it with explicit bindings, for a test or a tuning session. */
    public ClientSystemProvider(InputBindings bindings) {
        LibGdxDevices.Pad pad = new LibGdxDevices.Pad(
                bindings.gamepad().analogueTriggerAxisRight(),
                bindings.gamepad().analogueTriggerAxisLeft());
        // Gamepad first in the list, which decides nothing about priority — the router picks by
        // what the player last touched — but does decide the order two simultaneously-touched
        // devices are compared in, and that has to be fixed rather than incidental.
        this.input = new InputCollectionSystem(new InputRouter(
                new GamepadSource(pad, bindings), new KeyboardMouseSource(new LibGdxDevices.Desk(), bindings)));
    }

    /** The input system, so the client can tell it which player it is driving for. */
    public InputCollectionSystem inputCollection() {
        return input;
    }

    @Override
    public EntitySystem create(SystemSlot slot, RuntimeMode mode) {
        return switch (slot) {
            case INPUT_COLLECTION -> input;
            default -> null;
        };
    }
}
