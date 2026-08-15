/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.config.LaunchConfig;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What every screen shares, and the one place the player's choices are remembered
 * (docs/03_runtime_modes.md#D03-S5.1).
 *
 * <p>The content index is loaded <b>once, before the first screen</b>, and outlives every match.
 * That ordering is the point of this class: the garage has to list the vehicles before a world
 * exists to put one in, so asset loading can no longer be a step inside {@code ClientRuntime}. A
 * match built and closed three times in a session re-reads no JSON and re-parses no mesh.
 *
 * <p>The selected assembly lives here rather than on the garage screen for the same reason — the
 * garage is disposed when the match starts, and coming back from a match should land on the car the
 * player was driving rather than on the top of the list.
 *
 * <p><b>Owner of {@link MenuChrome}</b> (G19).
 */
public final class MenuContext implements Disposable {

    private final LaunchConfig config;
    private final InMemoryAssetIndex assets;
    private final MenuChrome chrome;
    private final MenuInput input = new MenuInput();
    private final List<AssemblyDef> roster;

    private AssetId selectedAssembly;

    public MenuContext(LaunchConfig config, InMemoryAssetIndex assets) {
        this.config = Objects.requireNonNull(config, "config");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.chrome = new MenuChrome(config.assetRoot());
        // Sorted by id, not by encounter order: the garage's rows must be the same on every run and
        // on every machine, which is G3 applied to a menu. A player learning "mine is second" and
        // finding it third after a reinstall is the failure this prevents.
        this.roster = assets.assemblies().values().stream()
                .sorted(Comparator.comparing(a -> a.assemblyId().value()))
                .toList();
        this.selectedAssembly = roster.isEmpty() ? null : roster.get(0).assemblyId();
    }

    public LaunchConfig config() {
        return config;
    }

    public InMemoryAssetIndex assets() {
        return assets;
    }

    public MenuChrome chrome() {
        return chrome;
    }

    public MenuInput input() {
        return input;
    }

    /** Every vehicle the player may drive, in a fixed order. Possibly empty (G18). */
    public List<AssemblyDef> roster() {
        return roster;
    }

    /** The vehicle the player will take into the next match, or null when none is loaded. */
    public AssetId selectedAssembly() {
        return selectedAssembly;
    }

    public void selectAssembly(AssetId assemblyId) {
        this.selectedAssembly = assemblyId;
    }

    /** The index of {@link #selectedAssembly} in {@link #roster}, or 0. */
    public int selectedIndex() {
        for (int i = 0; i < roster.size(); i++) {
            if (roster.get(i).assemblyId().equals(selectedAssembly)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void dispose() {
        chrome.dispose();
    }
}
