/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.vehicle.WeaponLoadout;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.config.LaunchConfig;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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

    /**
     * The armament the player has chosen for each vehicle, by shipped assembly id.
     *
     * <p>Per vehicle rather than one global loadout, and remembered for the session: picking up the
     * Stampede, fitting a cannon, looking at the Eclipse and coming back should find the cannon
     * still on it. Absent until the player touches a vehicle, at which point it starts from what
     * the artist fitted (D01-NG1a).
     */
    private final Map<AssetId, WeaponLoadout> loadouts = new TreeMap<>();

    /** Which garage row to open on, or -1 for the selected vehicle. A capture affordance only. */
    private int garageRow = -1;

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

    /** Which garage row to open on, or -1. Set from {@code --garage-row}; see {@link #setGarageRow}. */
    public int garageRow() {
        return garageRow;
    }

    /**
     * Opens the garage on a given row rather than on the selected vehicle.
     *
     * <p>Exists for the same reason {@code --vehicle} does: a headless capture cannot press W/S, so
     * without it the armament list can only ever be photographed with nothing focused, and "look at
     * it" stops being a check anyone can perform on the part of the screen that moves.
     */
    public void setGarageRow(int row) {
        this.garageRow = row;
    }

    /**
     * Fits a weapon to a mounting on the selected vehicle, as if the player had chosen it.
     *
     * <p>The third capture affordance, and the one that makes the other two worth having: a
     * headless run cannot press A/D either, so without this the armament list can be photographed
     * but a <em>chosen</em> loadout cannot. Takes the same ids the garage shows.
     *
     * @param slotId the mounting, e.g. {@code hardpoint_bonnet}
     * @param weaponId the weapon, or null to clear the mounting
     * @return false when the mounting or the weapon does not exist, so a typo is reported rather
     *     than silently producing the vehicle you already had
     */
    public boolean fit(String slotId, AssetId weaponId) {
        AssemblyDef base = assets.assembly(selectedAssembly);
        if (base == null || slotId == null) {
            return false;
        }
        boolean known = WeaponLoadout.mountingsOf(base, assets).stream()
                .anyMatch(slot -> slot.slotId().equals(slotId));
        if (!known || (weaponId != null && assets.weapon(weaponId) == null)) {
            return false;
        }
        setLoadout(loadout().with(slotId, weaponId));
        return true;
    }

    /** The vehicle the player will take into the next match, or null when none is loaded. */
    public AssetId selectedAssembly() {
        return selectedAssembly;
    }

    public void selectAssembly(AssetId assemblyId) {
        this.selectedAssembly = assemblyId;
    }

    /**
     * The armament chosen for the selected vehicle, defaulting to what it ships with.
     *
     * <p>Never null: a vehicle with no hardpoints yields an empty loadout, which is a truthful
     * description of it rather than a missing one (G18).
     */
    public WeaponLoadout loadout() {
        AssemblyDef base = assets.assembly(selectedAssembly);
        if (base == null) {
            return WeaponLoadout.empty();
        }
        return loadouts.computeIfAbsent(selectedAssembly, id -> WeaponLoadout.of(base, assets));
    }

    /** Replaces the selected vehicle's armament. */
    public void setLoadout(WeaponLoadout loadout) {
        if (selectedAssembly != null && loadout != null) {
            loadouts.put(selectedAssembly, loadout);
        }
    }

    /**
     * The assembly to preview and to drive: the shipped vehicle, or the player's configuration of it.
     *
     * <p>Registered on the index as it is produced, because everything downstream — the preview, the
     * spawn path, the match report — resolves a vehicle by id and none of them should have to know
     * whether a player has been in the garage. A configuration equal to what the vehicle ships with
     * returns the shipped id unchanged, so an untouched playthrough puts nothing extra on the index.
     */
    public AssetId configuredAssembly() {
        AssemblyDef base = assets.assembly(selectedAssembly);
        if (base == null) {
            return selectedAssembly;
        }
        AssemblyDef configured = loadout().applyTo(base, assets);
        if (configured == base) {
            return base.assemblyId();
        }
        assets.putConfigured(configured);
        return configured.assemblyId();
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
