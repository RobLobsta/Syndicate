/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.graphics.Color;
import java.util.List;

/**
 * The title screen: what the game is called, and the two things you can do about it.
 *
 * <p>Two entries, because there are two things this build can do. A menu that lists MULTIPLAYER,
 * OPTIONS and PROFILE greyed out is a menu that advertises what is missing; the roster of entries
 * grows when the features do, and the code for that is one row in {@link #ENTRIES}.
 *
 * <p>Deliberately not a 3D scene. A rotating hero car behind the title is the obvious next thing
 * and it costs a camera, a light rig and a model instance that has to be disposed on a transition
 * — worth doing once the garage's preview has proved that machinery, not before.
 */
public final class MainMenuScreen implements Screen {

    /** One selectable line, and where it goes. */
    private record Entry(String label, String hint, ScreenId target) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry("PLAY", "Pick a machine and take it out", ScreenId.GARAGE),
            new Entry("QUIT", "Close the game", ScreenId.QUIT));

    /** Design units from the left edge to the menu column. */
    private static final float COLUMN_X = 90f;

    private final MenuContext context;
    private int cursor;
    private ScreenId next = ScreenId.MAIN_MENU;

    public MainMenuScreen(MenuContext context) {
        this.context = context;
        context.input().reset();
    }

    @Override
    public void render(float frameDeltaSeconds) {
        switch (context.input().poll(frameDeltaSeconds)) {
            case UP -> cursor = Math.floorMod(cursor - 1, ENTRIES.size());
            case DOWN -> cursor = Math.floorMod(cursor + 1, ENTRIES.size());
            case CONFIRM -> next = ENTRIES.get(cursor).target();
                // Escape on the title screen means the same as choosing QUIT. Anywhere deeper it means
                // "go back one", and the two never collide because this is the screen with no parent.
            case BACK -> next = ScreenId.QUIT;
            default -> {}
        }
        draw();
    }

    private void draw() {
        MenuChrome chrome = context.chrome();
        chrome.begin();

        float height = chrome.height();

        chrome.text("SYNDICATE", COLUMN_X, height - 190f, 76f, MenuChrome.TEXT);
        chrome.fill(COLUMN_X, height - 208f, 300f, 3f, MenuChrome.ACCENT);
        chrome.text("MODULAR VEHICULAR COMBAT", COLUMN_X, height - 244f, 17f, MenuChrome.TEXT_DIM);

        float rowHeight = 52f;
        float firstRowY = height - 380f;
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            float y = firstRowY - i * rowHeight;
            boolean isSelected = i == cursor;

            if (isSelected) {
                chrome.fill(COLUMN_X - 22f, y - 12f, 420f, rowHeight - 8f, MenuChrome.ACCENT_WASH);
                chrome.fill(COLUMN_X - 22f, y - 12f, 5f, rowHeight - 8f, MenuChrome.ACCENT);
            }
            Color colour = isSelected ? MenuChrome.ACCENT : MenuChrome.TEXT;
            chrome.text(entry.label(), COLUMN_X, y, 30f, colour);
            if (isSelected) {
                chrome.text(entry.hint(), COLUMN_X + 190f, y + 5f, 15f, MenuChrome.TEXT_DIM);
            }
        }

        chrome.text(footer(), COLUMN_X, 52f, 14f, MenuChrome.TEXT_DIM);
        chrome.end();
    }

    /**
     * The one line of state worth showing on a title screen: what is actually loaded.
     *
     * <p>An empty content directory is the most common way this build fails for somebody who moved
     * the executable without the assets beside it, and "0 VEHICLES" here diagnoses it in a glance
     * where an empty garage two screens later does not.
     */
    private String footer() {
        dev.syndicate.core.asset.ArenaDef arena =
                context.assets().arena(context.config().arenaId());
        String arenaName = arena == null ? context.config().arenaId().value() : arena.displayName();
        return context.roster().size() + " VEHICLES   ·   ARENA: "
                + arenaName.toUpperCase(java.util.Locale.ROOT)
                + "   ·   W/S OR STICK TO MOVE   ·   ENTER OR (A) TO SELECT";
    }

    @Override
    public ScreenId next() {
        return next;
    }
}
