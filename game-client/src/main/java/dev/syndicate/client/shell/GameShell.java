/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.client.ClientRuntime;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.config.LaunchConfig;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the window is showing, and the only place that changes (docs/03_runtime_modes.md#D03-S5.1).
 *
 * <p>One screen is current. Each frame it is rendered and then asked what should be showing next;
 * if that is not itself, the current screen is disposed and its successor constructed. Two rules
 * make this safe: a screen never constructs another screen, and a transition never happens
 * mid-render. Together they mean the match's Bullet world is torn down at a point where nothing is
 * drawing it, which is the failure mode a "just swap the pointer" version has.
 *
 * <p><b>Where the match's lifetime comes from.</b> Content is loaded once, here, and outlives every
 * screen. A {@code ClientRuntime} is built when a match starts and closed when it ends — so a
 * player can play, quit to the menu, pick a different car and play again, and the second match gets
 * a clean physics world rather than the first one's leftovers (G19).
 */
public final class GameShell implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(GameShell.class);

    private final MenuContext context;
    private final LaunchConfig config;

    private Screen current;
    private ScreenId currentId;
    private boolean quitRequested;
    private int widthPx;
    private int heightPx;

    /**
     * @param startScreen where to begin. {@link ScreenId#MATCH} skips the menu entirely, which is
     *     what {@code --auto-start} asks for and what every headless capture uses.
     */
    public GameShell(LaunchConfig config, ScreenId startScreen) {
        this(config, startScreen, null);
    }

    /**
     * @param vehicle which vehicle to select on entry, by assembly id or a substring of one; null
     *     leaves the roster's first. Matched loosely on purpose — this is a capture and debugging
     *     affordance, so {@code --vehicle stampede} should work without the full asset id.
     */
    public GameShell(LaunchConfig config, ScreenId startScreen, String vehicle) {
        this(config, startScreen, vehicle, -1, List.of());
    }

    public GameShell(LaunchConfig config, ScreenId startScreen, String vehicle, int garageRow) {
        this(config, startScreen, vehicle, garageRow, List.of());
    }

    /**
     * @param garageRow which garage row to open on, or -1 for the selected vehicle. A capture
     *     affordance, exactly as {@code vehicle} is.
     */
    /**
     * @param garageRow which garage row to open on, or -1 for the selected vehicle
     * @param fittings {@code slotId=weaponId} pairs applied to the selected vehicle before the first
     *     frame, so a capture can photograph a chosen loadout
     */
    public GameShell(LaunchConfig config, ScreenId startScreen, String vehicle, int garageRow, List<String> fittings) {
        this.config = config;
        InMemoryAssetIndex assets = ClientRuntime.loadAssets(config);
        this.context = new MenuContext(config, assets);
        selectVehicle(vehicle);
        context.setGarageRow(garageRow);
        applyFittings(fittings);
        enter(startScreen);
    }

    /** Applies each {@code slotId=weaponId} pair, logging any that does not resolve. */
    private void applyFittings(List<String> fittings) {
        for (String fitting : fittings) {
            int split = fitting.indexOf('=');
            String slotId = split < 0 ? fitting : fitting.substring(0, split);
            String weapon = split < 0 ? "" : fitting.substring(split + 1);
            AssetId weaponId = weapon.isBlank() || "none".equals(weapon) ? null : AssetId.of(weapon);
            if (!context.fit(slotId, weaponId)) {
                LOG.warn("--fit {} names no mounting or no weapon on the selected vehicle", fitting);
            }
        }
    }

    /** The screen showing right now, so a capture can report what it photographed. */
    public ScreenId currentScreenId() {
        return currentId;
    }

    /** The current screen, or null. */
    public Screen current() {
        return current;
    }

    /** True once a screen has asked to close the window. */
    public boolean isQuitRequested() {
        return quitRequested;
    }

    /** Renders one frame and applies any transition it asked for. */
    public void render(float frameDeltaSeconds) {
        if (current == null) {
            return;
        }
        current.render(frameDeltaSeconds);

        ScreenId requested = current.next();
        if (requested != currentId) {
            enter(requested);
        }
    }

    public void resize(int width, int height) {
        this.widthPx = width;
        this.heightPx = height;
        if (current != null) {
            current.resize(width, height);
        }
    }

    /** Disposes what is showing and constructs what was asked for. */
    private void enter(ScreenId target) {
        if (current != null) {
            LOG.info("screen {} -> {}", currentId, target);
            current.dispose();
            current = null;
        }
        currentId = target;

        switch (target) {
            case MAIN_MENU -> current = new MainMenuScreen(context);
            case GARAGE -> current = new GarageScreen(context);
            case MATCH -> current = startMatch();
            case QUIT -> quitRequested = true;
        }
        if (current != null && widthPx > 0) {
            current.resize(widthPx, heightPx);
        }
    }

    /**
     * Builds a match around the garage's choice.
     *
     * <p>A failure here returns to the menu rather than killing the process, because by this point
     * a window is open and a player is looking at it: "that car would not load, pick another" is a
     * recoverable state and an exit code is not. A failure during the <em>first</em> screen's
     * startup is different and still exits, which is {@code ClientMain}'s job.
     */
    private Screen startMatch() {
        ScreenId exitTo = context.roster().isEmpty() ? ScreenId.QUIT : ScreenId.MAIN_MENU;
        try {
            // The configured vehicle, so the player drives the loadout they just chose (D01-NG1a).
            ClientRuntime runtime = ClientRuntime.start(config, context.assets(), context.configuredAssembly());
            return new MatchScreen(runtime, exitTo);
        } catch (ClientRuntime.StartupException e) {
            LOG.error("could not start the match: {}", e.getMessage(), e.getCause());
        } catch (RuntimeException e) {
            LOG.error("could not start the match", e);
        }
        currentId = ScreenId.MAIN_MENU;
        return new MainMenuScreen(context);
    }

    @Override
    public void dispose() {
        if (current != null) {
            current.dispose();
            current = null;
        }
        context.dispose();
    }

    /** Selects the first roster entry whose assembly id contains {@code vehicle}, if any. */
    private void selectVehicle(String vehicle) {
        if (vehicle == null || vehicle.isBlank()) {
            return;
        }
        String wanted = vehicle.trim().toLowerCase(java.util.Locale.ROOT);
        for (dev.syndicate.core.asset.AssemblyDef assembly : context.roster()) {
            if (assembly.assemblyId().value().toLowerCase(java.util.Locale.ROOT).contains(wanted)) {
                context.selectAssembly(assembly.assemblyId());
                return;
            }
        }
    }
}
