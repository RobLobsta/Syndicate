/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.graphics.Color;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.vehicle.VehicleProfile;
import dev.syndicate.core.vehicle.VehicleProfiles;
import java.util.List;
import java.util.Locale;

/**
 * Pick the machine you are going to drive (docs/01_product_game_design.md#D01-S3, NG1).
 *
 * <p><b>A selection screen, not an editor.</b> D01-NG1 rules out building a vehicle part by part:
 * the data model permits arbitrary assemblies and the product ships prebuilt ones. So this lists
 * what is loaded, shows it, says what it is like to drive, and gets out of the way.
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

    private final MenuContext context;
    private final GaragePreview preview;
    private final List<AssemblyDef> roster;

    private int cursor;
    private ScreenId next = ScreenId.GARAGE;

    public GarageScreen(MenuContext context) {
        this.context = context;
        this.roster = context.roster();
        this.cursor = context.selectedIndex();
        this.preview = new GaragePreview(context.config().assetRoot(), context.assets());
        context.input().reset();
    }

    @Override
    public void render(float frameDeltaSeconds) {
        if (!roster.isEmpty()) {
            switch (context.input().poll(frameDeltaSeconds)) {
                case UP -> cursor = Math.floorMod(cursor - 1, roster.size());
                case DOWN -> cursor = Math.floorMod(cursor + 1, roster.size());
                case CONFIRM -> {
                    context.selectAssembly(roster.get(cursor).assemblyId());
                    next = ScreenId.MATCH;
                }
                case BACK -> next = ScreenId.MAIN_MENU;
                default -> {}
            }
            // Kept in step every frame rather than only on confirm, so the preview and the stat
            // block are always describing the row the cursor is on.
            context.selectAssembly(roster.get(cursor).assemblyId());
        } else if (context.input().poll(frameDeltaSeconds) != MenuInput.Action.NONE) {
            next = ScreenId.MAIN_MENU;
        }
        draw(frameDeltaSeconds);
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
        drawStats(chrome, width, height);

        chrome.text(
                "W/S OR STICK  SELECT      ENTER OR (A)  DEPLOY      ESC OR (B)  BACK",
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

        preview.render(
                context.selectedAssembly(),
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
            boolean isSelected = i == cursor;

            if (isSelected) {
                chrome.fill(LIST_X - 18f, y - 10f, LIST_WIDTH, ROW_HEIGHT - 6f, MenuChrome.ACCENT_WASH);
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

    private void drawStats(MenuChrome chrome, float width, float height) {
        AssemblyDef assembly = roster.get(cursor);
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
        chrome.statRow("Parts", String.valueOf(assembly.partCount()), rowX, y, rowWidth, 15f);
        y -= rowStep;
        chrome.statRow("Armour", armourValue(assembly), rowX, y, rowWidth, 15f);
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
