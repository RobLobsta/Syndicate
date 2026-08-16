/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.graphics.Color;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.asset.WeaponDef;
import dev.syndicate.core.vehicle.VehicleProfile;
import dev.syndicate.core.vehicle.VehicleProfiles;
import dev.syndicate.core.vehicle.WeaponLoadout;
import dev.syndicate.model.AssetId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pick the machine you are going to drive (docs/01_product_game_design.md#D01-S3, NG1).
 *
 * <p><b>A selection screen and an armoury, not an editor.</b> D01-NG1 still rules out building a
 * vehicle part by part — the chassis, the panels, the wheels and the glass are the vehicle the
 * artist authored. What D01-NG1a opens is the hardpoints: which weapon sits on each mounting the
 * chassis already offers. That is a much smaller door than a part editor, and it is the one the
 * player actually wants to walk through.
 *
 * <p>One cursor covers both lists — vehicles first, then that vehicle's hardpoints — so there is no
 * mode to be in and no key that means something different depending on where you are. Up and down
 * move; on a hardpoint row, left and right cycle through the weapons that mounting will take,
 * {@code NONE} included, because leaving a hardpoint empty is a real choice.
 *
 * <p>The stats come from two places and prefer the richer one. A {@link VehicleProfile} exists for
 * every hand-authored vehicle and carries the figures the handling was derived from — the real
 * car's mass, its 0-100 and its top speed — which is what a player actually chooses on. A vehicle
 * that came out of the preparation pipeline has no profile, so it falls back to what the assembly
 * itself asserts: class, mass, power budget and part count. Neither path can show a number nothing
 * computed, which is why there is no "handling: 7/10".
 */
public final class GarageScreen implements Screen {

    /** Design units: the vehicle list's column. */
    private static final float LIST_X = 70f;

    private static final float LIST_WIDTH = 330f;

    /** Design units: one row in the vehicle list. */
    private static final float ROW_HEIGHT = 44f;

    /** Design units: one row in the armament list, tighter than a vehicle row. */
    private static final float ARMAMENT_ROW_HEIGHT = 42f;

    /** What an empty hardpoint reads as. */
    private static final String EMPTY = "EMPTY";

    private final MenuContext context;
    private final GaragePreview preview;
    private final List<AssemblyDef> roster;

    /** The selected vehicle's mountings, recomputed whenever the selection changes. */
    private List<SlotDefinition> hardpoints = List.of();

    private AssetId hardpointsFor;

    private int cursor;
    private ScreenId next = ScreenId.GARAGE;

    public GarageScreen(MenuContext context) {
        this.context = context;
        this.roster = context.roster();
        this.preview = new GaragePreview(context.config().assetRoot(), context.assets());
        refreshHardpoints();
        // A capture cannot press W/S, so without a way to open on a given row the armament list can
        // only ever be photographed unfocused — the same reason `--vehicle` exists. Clamped rather
        // than validated: an out-of-range row is a typo in a debug flag, not a reason to refuse.
        int requested = context.garageRow();
        this.cursor =
                requested >= 0 ? Math.min(requested, roster.size() + hardpoints.size() - 1) : context.selectedIndex();
        context.input().reset();
    }

    @Override
    public void render(float frameDeltaSeconds) {
        if (!roster.isEmpty()) {
            int rows = roster.size() + hardpoints.size();
            switch (context.input().poll(frameDeltaSeconds)) {
                case UP -> cursor = Math.floorMod(cursor - 1, Math.max(1, rows));
                case DOWN -> cursor = Math.floorMod(cursor + 1, Math.max(1, rows));
                case LEFT -> cycleWeapon(-1);
                case RIGHT -> cycleWeapon(1);
                case CONFIRM -> next = ScreenId.MATCH;
                case BACK -> next = ScreenId.MAIN_MENU;
                default -> {}
            }
            // Kept in step every frame rather than only on confirm, so the preview and the stat
            // block are always describing the row the cursor is on. A cursor down among the
            // hardpoints leaves the vehicle where it is — the armament belongs to the vehicle above
            // it, not to a row of its own.
            if (cursor < roster.size()) {
                context.selectAssembly(roster.get(cursor).assemblyId());
            }
            refreshHardpoints();
        } else if (context.input().poll(frameDeltaSeconds) != MenuInput.Action.NONE) {
            next = ScreenId.MAIN_MENU;
        }
        draw(frameDeltaSeconds);
    }

    /** The hardpoint the cursor is on, or null when it is up among the vehicles. */
    private SlotDefinition focusedHardpoint() {
        int index = cursor - roster.size();
        return index >= 0 && index < hardpoints.size() ? hardpoints.get(index) : null;
    }

    /**
     * Steps the focused hardpoint's weapon one place along its own list of options.
     *
     * <p>The list is {@code null} — meaning empty — followed by every weapon the mounting accepts,
     * so cycling always terminates and always includes taking the gun off. Wrapping rather than
     * clamping: four options and two keys is a ring, not a slider.
     */
    private void cycleWeapon(int step) {
        SlotDefinition slot = focusedHardpoint();
        if (slot == null) {
            return;
        }
        List<AssetId> options = optionsFor(slot);
        AssetId current = context.loadout().on(slot.slotId());
        int at = options.indexOf(current);
        AssetId chosen = options.get(Math.floorMod(at + step, options.size()));
        context.setLoadout(context.loadout().with(slot.slotId(), chosen));
        // The preview keys its rebuild on the assembly id, and a re-armed vehicle keeps its id.
        preview.invalidate();
    }

    /** Nothing, then everything this mounting will take, in a fixed order (G3). */
    private List<AssetId> optionsFor(SlotDefinition slot) {
        List<AssetId> options = new ArrayList<>();
        options.add(null);
        options.addAll(WeaponLoadout.fittableOn(slot, context.assets()));
        return options;
    }

    private void refreshHardpoints() {
        AssetId selected = context.selectedAssembly();
        if (selected != null && selected.equals(hardpointsFor)) {
            return;
        }
        hardpointsFor = selected;
        AssemblyDef assembly = context.assets().assembly(selected);
        hardpoints = WeaponLoadout.mountingsOf(assembly, context.assets());
        // Changing vehicle can leave the cursor pointing past the new one's hardpoints.
        cursor = Math.min(cursor, roster.size() + hardpoints.size() - 1);
    }

    private void draw(float frameDeltaSeconds) {
        MenuChrome chrome = context.chrome();
        chrome.begin();

        float height = chrome.height();
        float width = chrome.width();

        // The preview goes down first: it clears depth inside its own scissor and the panels are
        // drawn over it, which is what puts the car behind the list instead of on top of it.
        drawPreview(chrome, width, height, frameDeltaSeconds);

        chrome.text("GARAGE", LIST_X, height - 96f, 40f, MenuChrome.TEXT);
        chrome.fill(LIST_X, height - 110f, 168f, 3f, MenuChrome.ACCENT);

        if (roster.isEmpty()) {
            chrome.text("NO VEHICLES LOADED", LIST_X, height * 0.5f, 24f, MenuChrome.ACCENT);
            chrome.text(
                    "Check that assets/ sits beside the executable.",
                    LIST_X,
                    height * 0.5f - 30f,
                    16f,
                    MenuChrome.TEXT_DIM);
            chrome.text("ESC  BACK", LIST_X, 52f, 14f, MenuChrome.TEXT_DIM);
            chrome.end();
            return;
        }

        drawList(chrome, height);
        drawArmament(chrome, height);
        drawStats(chrome, width, height);

        chrome.text(
                focusedHardpoint() == null
                        ? "W/S OR STICK  SELECT      ENTER OR (A)  DEPLOY      ESC OR (B)  BACK"
                        : "W/S  SELECT      A/D  CHANGE WEAPON      ENTER OR (A)  DEPLOY      ESC OR (B)  BACK",
                LIST_X,
                52f,
                14f,
                MenuChrome.TEXT_DIM);
        chrome.end();
    }

    private void drawPreview(MenuChrome chrome, float width, float height, float frameDeltaSeconds) {
        float viewLeft = LIST_X + LIST_WIDTH + 30f;
        float viewWidth = width - viewLeft - 40f;

        // A floor line for the vehicle to stand on. The 3D pass clears depth inside its scissor but
        // not colour, so this shows through wherever the car does not — which is what stops the
        // preview reading as a car cut out and pasted onto a black rectangle. A *line* rather than
        // the lit slab tried first: at any alpha, a hard-edged rectangle behind the car reads as a
        // grey box somebody forgot to remove.
        chrome.fill(viewLeft + viewWidth * 0.12f, height * 0.34f, viewWidth * 0.76f, 2f, MenuChrome.EDGE);

        // The configured vehicle, not the shipped one: what the garage shows has to be what the
        // player will drive, or the loadout is a menu that changes nothing you can see.
        preview.render(
                context.configuredAssembly(),
                frameDeltaSeconds,
                (int) chrome.px(viewLeft),
                (int) chrome.px(190f),
                (int) chrome.px(viewWidth),
                (int) chrome.px(height - 320f));
    }

    private void drawList(MenuChrome chrome, float height) {
        float firstRowY = height - 190f;
        for (int i = 0; i < roster.size(); i++) {
            AssemblyDef assembly = roster.get(i);
            float y = firstRowY - i * ROW_HEIGHT;
            // Selected and focused are the same row until the cursor drops into the armament list,
            // at which point the vehicle stays lit and loses its cursor bar.
            boolean isSelected = i == context.selectedIndex();
            boolean isFocused = i == cursor;

            if (isSelected) {
                chrome.fill(LIST_X - 18f, y - 10f, LIST_WIDTH, ROW_HEIGHT - 6f, MenuChrome.ACCENT_WASH);
            }
            if (isFocused) {
                chrome.fill(LIST_X - 18f, y - 10f, 4f, ROW_HEIGHT - 6f, MenuChrome.ACCENT);
            }
            Color colour = isSelected ? MenuChrome.ACCENT : MenuChrome.TEXT;
            chrome.text(assembly.displayName().toUpperCase(Locale.ROOT), LIST_X, y, 22f, colour);
            chrome.textRight(
                    assembly.vehicleClass().toUpperCase(Locale.ROOT),
                    LIST_X + LIST_WIDTH - 32f,
                    y + 2f,
                    13f,
                    MenuChrome.TEXT_DIM);
        }
    }

    /**
     * The armament block: one row per hardpoint, naming what is on it.
     *
     * <p>Every mounting the chassis has is listed, including the empty ones. A hardpoint you cannot
     * see is a hardpoint you do not know you have, and "this car has three mountings and I have
     * filled one" is the decision this screen exists to support.
     */
    private void drawArmament(MenuChrome chrome, float height) {
        float y = height - 190f - roster.size() * ROW_HEIGHT - 22f;
        chrome.text("ARMAMENT", LIST_X, y, 15f, MenuChrome.TEXT_DIM);
        y -= 10f;
        chrome.rule(LIST_X, y, LIST_WIDTH - 32f, MenuChrome.EDGE);
        y -= 26f;

        if (hardpoints.isEmpty()) {
            chrome.text("no mountings", LIST_X, y, 15f, MenuChrome.TEXT_DIM);
            return;
        }
        WeaponLoadout loadout = context.loadout();
        for (int i = 0; i < hardpoints.size(); i++) {
            SlotDefinition slot = hardpoints.get(i);
            boolean isFocused = cursor - roster.size() == i;
            float rowY = y - i * ARMAMENT_ROW_HEIGHT;

            if (isFocused) {
                chrome.fill(LIST_X - 18f, rowY - 14f, LIST_WIDTH, ARMAMENT_ROW_HEIGHT - 6f, MenuChrome.ACCENT_WASH);
                chrome.fill(LIST_X - 18f, rowY - 14f, 4f, ARMAMENT_ROW_HEIGHT - 6f, MenuChrome.ACCENT);
            }
            chrome.text(slotLabel(slot), LIST_X, rowY + 14f, 12f, MenuChrome.TEXT_DIM);

            AssetId weaponId = loadout.on(slot.slotId());
            String name = weaponName(weaponId);
            Color colour = weaponId == null ? MenuChrome.TEXT_DIM : (isFocused ? MenuChrome.ACCENT : MenuChrome.TEXT);
            chrome.text(name, LIST_X, rowY - 8f, 17f, colour);
            // The arrows only on the focused row: an affordance on every row is decoration.
            if (isFocused) {
                chrome.textRight("< >", LIST_X + LIST_WIDTH - 32f, rowY - 8f, 15f, MenuChrome.ACCENT);
            }
            chrome.textRight(slot.sizeClass().name(), LIST_X + LIST_WIDTH - 32f, rowY + 14f, 11f, MenuChrome.TEXT_DIM);
        }
    }

    /** {@code hardpoint_flank_l} reads as {@code FLANK L}; {@code turret_main} as {@code TURRET MAIN}. */
    private static String slotLabel(SlotDefinition slot) {
        String id = slot.slotId();
        if (id.startsWith("hardpoint_")) {
            id = id.substring("hardpoint_".length());
        }
        return id.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private String weaponName(AssetId weaponId) {
        if (weaponId == null) {
            return EMPTY;
        }
        WeaponDef weapon = context.assets().weapon(weaponId);
        if (weapon == null) {
            return weaponId.value().toUpperCase(Locale.ROOT);
        }
        // The family and the mass, not the asset id: "CANNON  178 kg" is what a player chooses on,
        // and `weapon_cannon_01` is what a developer greps for.
        return String.format(Locale.ROOT, "%s   %.0f kg", weapon.family().name(), weapon.totalMassKg());
    }

    private void drawStats(MenuChrome chrome, float width, float height) {
        // The selected vehicle, not the cursor's row: the cursor may be down among the mountings,
        // and the panel describes the car those mountings are on.
        AssemblyDef assembly = roster.get(context.selectedIndex());
        // The part count has to be the configured vehicle's, or fitting a gun leaves a panel that
        // says 37 next to a car with 47 parts on it.
        AssemblyDef configured = context.assets().assembly(context.configuredAssembly());
        VehicleProfile profile = VehicleProfiles.byId(assembly.assemblyId());

        float panelWidth = 380f;
        float panelX = width - panelWidth - 60f;
        float panelHeight = 296f;
        float panelY = 96f;
        chrome.panel(panelX, panelY, panelWidth, panelHeight, MenuChrome.PANEL);

        float inset = 26f;
        float rowX = panelX + inset;
        float rowWidth = panelWidth - inset * 2f;
        float y = panelY + panelHeight - 46f;

        chrome.text(assembly.displayName().toUpperCase(Locale.ROOT), rowX, y, 24f, MenuChrome.ACCENT);
        y -= 24f;
        chrome.text(subtitle(assembly, profile), rowX, y, 13f, MenuChrome.TEXT_DIM);
        y -= 18f;
        chrome.rule(rowX, y, rowWidth, MenuChrome.EDGE);
        y -= 30f;

        float rowStep = 26f;
        if (profile != null) {
            chrome.statRow("Mass", format(profile.totalMassKg(), "kg"), rowX, y, rowWidth, 15f);
            y -= rowStep;
            chrome.statRow("Top speed", format(profile.derivedTopSpeedKph(), "km/h"), rowX, y, rowWidth, 15f);
            y -= rowStep;
            chrome.statRow(
                    "0-100 km/h",
                    String.format(Locale.ROOT, "%.1f s", profile.zeroToHundredS()),
                    rowX,
                    y,
                    rowWidth,
                    15f);
            y -= rowStep;
            chrome.statRow("Power", format(profile.enginePowerKw(), "kW"), rowX, y, rowWidth, 15f);
            y -= rowStep;
        } else {
            chrome.statRow("Mass", expectedMass(assembly), rowX, y, rowWidth, 15f);
            y -= rowStep;
            chrome.statRow("Power budget", expectedPower(assembly), rowX, y, rowWidth, 15f);
            y -= rowStep;
        }
        chrome.statRow(
                "Parts",
                String.valueOf((configured == null ? assembly : configured).partCount()),
                rowX,
                y,
                rowWidth,
                15f);
        y -= rowStep;
        chrome.statRow("Armour", armourValue(assembly), rowX, y, rowWidth, 15f);
        y -= rowStep;
        // Below the fixed figures, because unlike them it changes while you are looking at it.
        chrome.statRow(
                "Armament", format(context.loadout().totalMassKg(context.assets()), "kg"), rowX, y, rowWidth, 15f);
    }

    /**
     * The line under the name.
     *
     * <p>Names the real car a hand-authored vehicle was derived from. That is not trivia: the
     * handling figures are that car's published ones (DEC-033), so a player who knows what a
     * mid-engined supercar drives like has been told something true about this one.
     */
    private static String subtitle(AssemblyDef assembly, VehicleProfile profile) {
        String vehicleClass = assembly.vehicleClass().toUpperCase(Locale.ROOT) + " CLASS";
        return profile == null ? vehicleClass : vehicleClass + "   ·   " + profile.referenceVehicle();
    }

    private String armourValue(AssemblyDef assembly) {
        PartType chassis = context.assets().partType(assembly.chassisPartTypeId());
        return chassis == null ? "—" : String.format(Locale.ROOT, "%.0f", chassis.armorValue());
    }

    private static String expectedMass(AssemblyDef assembly) {
        return assembly.expected() == null ? "—" : format(assembly.expected().totalMassKg(), "kg");
    }

    private static String expectedPower(AssemblyDef assembly) {
        return assembly.expected() == null
                ? "—"
                : String.format(Locale.ROOT, "%.0f", assembly.expected().powerBudget());
    }

    private static String format(float value, String unit) {
        return String.format(Locale.ROOT, "%.0f %s", value, unit);
    }

    @Override
    public ScreenId next() {
        return next;
    }

    @Override
    public void dispose() {
        preview.dispose();
    }
}
