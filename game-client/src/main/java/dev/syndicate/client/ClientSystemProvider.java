/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client;

import dev.syndicate.client.audio.AudioSystem;
import dev.syndicate.client.audio.SoundBank;
import dev.syndicate.client.effect.EffectSystem;
import dev.syndicate.client.input.GamepadSource;
import dev.syndicate.client.input.InputBindings;
import dev.syndicate.client.input.InputCollectionSystem;
import dev.syndicate.client.input.InputRouter;
import dev.syndicate.client.input.KeyboardMouseSource;
import dev.syndicate.client.input.LibGdxDevices;
import dev.syndicate.client.input.ScriptedSource;
import dev.syndicate.client.present.DamageVisualSystem;
import dev.syndicate.client.present.InterpolationSystem;
import dev.syndicate.client.render.RenderContext;
import dev.syndicate.client.render.RenderSystem;
import dev.syndicate.core.asset.AssetIndex;
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
 * <p>Slot 1 needs only a device list, while slots 22 to 26 need a GL context, a loaded sound bank
 * and an asset index. That is why the presentation half arrives separately: a test, a tuning
 * session, or any future mode that reads input without drawing constructs the provider with
 * bindings alone, and the five presentation slots are then reported missing by
 * {@code SystemSetFactory} rather than half-built.
 */
public final class ClientSystemProvider implements SystemProvider {

    private final InputCollectionSystem input;
    private final InterpolationSystem interpolation;
    private final DamageVisualSystem damageVisual;
    private final EffectSystem effects;
    private final AudioSystem audio;
    private final RenderSystem render;

    /** Builds the provider with the real devices and the shipped bindings, and no presentation. */
    public ClientSystemProvider(Path assetRoot) {
        this(InputBindings.load(Objects.requireNonNull(assetRoot, "assetRoot")));
    }

    /** Builds it with explicit bindings and no presentation, for a test or a tuning session. */
    public ClientSystemProvider(InputBindings bindings) {
        this.input = buildInput(bindings);
        this.interpolation = null;
        this.damageVisual = null;
        this.effects = null;
        this.audio = null;
        this.render = null;
    }

    /**
     * Builds the full client: input and the five presentation slots.
     *
     * @param context the GL resources, already constructed — this class never creates a GL object,
     *     so the ordering rule that they are made after the window exists lives in one place
     * @param bank the loaded sound bank; an unavailable one still produces a working slot 25
     * @param assets the asset index slot 25 resolves materials and destruction classes through
     */
    public ClientSystemProvider(
            InputBindings bindings, RenderContext context, SoundBank bank, AssetIndex assets, LocalPlayer localPlayer) {
        this.input = buildInput(bindings);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(localPlayer, "localPlayer");

        this.interpolation = new InterpolationSystem();
        this.damageVisual = new DamageVisualSystem();
        this.effects = new EffectSystem();
        this.audio = new AudioSystem(bank, assets, localPlayer);
        this.render = new RenderSystem(context, assets, localPlayer, effects, input::activeDevice);
    }

    private static InputCollectionSystem buildInput(InputBindings bindings) {
        LibGdxDevices.Pad pad = new LibGdxDevices.Pad(
                bindings.gamepad().analogueTriggerAxisRight(),
                bindings.gamepad().analogueTriggerAxisLeft());
        // Gamepad first in the list, which decides nothing about priority — the router picks by
        // what the player last touched — but does decide the order two simultaneously-touched
        // devices are compared in, and that has to be fixed rather than incidental.
        ScriptedSource scripted = ScriptedSource.launchSource();
        if (scripted != null) {
            // First and alone: a scripted run is one the script drives from the first frame to the
            // last, and leaving the real devices in the router would have an idle keyboard take the
            // car back during any segment that holds a steady input.
            return new InputCollectionSystem(new InputRouter(scripted));
        }
        return new InputCollectionSystem(new InputRouter(
                new GamepadSource(pad, bindings), new KeyboardMouseSource(new LibGdxDevices.Desk(), bindings)));
    }

    /** The input system, so the client can tell it which player it is driving for. */
    public InputCollectionSystem inputCollection() {
        return input;
    }

    /** The renderer, or null when this provider was built without presentation. */
    public RenderSystem renderSystem() {
        return render;
    }

    /**
     * The audio system, or null when this provider was built without presentation.
     *
     * <p>Exposed because it owns a native audio device and the thread feeding it (DEC-055), and
     * something has to close them. Nothing did before, and nothing needed to — the sounds it played
     * were the {@code SoundBank}'s to dispose.
     */
    public AudioSystem audioSystem() {
        return audio;
    }

    @Override
    public EntitySystem create(SystemSlot slot, RuntimeMode mode) {
        return switch (slot) {
            case INPUT_COLLECTION -> input;
            case INTERPOLATION -> interpolation;
            case DAMAGE_VISUAL -> damageVisual;
            case EFFECT -> effects;
            case AUDIO -> audio;
            case RENDER -> render;
            default -> null;
        };
    }
}
