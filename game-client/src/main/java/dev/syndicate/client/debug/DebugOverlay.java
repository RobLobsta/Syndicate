/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Disposable;
import java.util.List;

/**
 * Draws the debug console (docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>Separate from {@link DebugConsole} for the reason {@code Hud} is separate from
 * {@code RenderSystem}: the console decides what is true and this decides what it looks like, so
 * the half that touches the world can be tested without a GL context.
 *
 * <p>Drawn after the HUD and in its own orthographic space, so it sits over everything and does not
 * move with the car. <b>Owner of a batch, a shape renderer and a font</b> (G19).
 */
public final class DebugOverlay implements Disposable {

    /** Pixels from the left edge to the panel. */
    public static final float MARGIN_PX = 16f;

    /** Pixels. Width of the console panel. */
    public static final float PANEL_WIDTH_PX = 330f;

    /** Pixels. Height of one row. */
    public static final float ROW_HEIGHT_PX = 19f;

    /** Pixels. Height of a section's title row. */
    public static final float TITLE_HEIGHT_PX = 23f;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont font = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();

    private final Color panel = new Color(0.04f, 0.05f, 0.07f, 0.88f);
    private final Color titleBack = new Color(0.11f, 0.14f, 0.19f, 0.95f);
    private final Color selected = new Color(0.20f, 0.42f, 0.66f, 0.95f);
    private final Color accent = new Color(0.45f, 0.78f, 1f, 1f);
    private final Color muted = new Color(0.62f, 0.66f, 0.72f, 1f);

    /** Draws the whole console for one frame. Does nothing when it is closed. */
    public void render(DebugConsole console) {
        if (!console.isOpen()) {
            return;
        }
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        camera.setToOrtho(false, width, height);
        camera.update();

        List<DebugConsole.Section> sections = console.sections();
        float panelHeight = panelHeight(sections);
        float top = height - MARGIN_PX;
        float left = MARGIN_PX;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(panel);
        shapes.rect(left, top - panelHeight, PANEL_WIDTH_PX, panelHeight);
        drawRowBackgrounds(console, sections, left, top);
        shapes.end();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        drawText(console, sections, left, top);
        batch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Rebuilds the projection after a window resize. */
    public void resize(int width, int height) {
        camera.setToOrtho(false, width, height);
        camera.update();
    }

    private float panelHeight(List<DebugConsole.Section> sections) {
        float total = ROW_HEIGHT_PX * 2f; // the header and the status line
        for (DebugConsole.Section section : sections) {
            total += TITLE_HEIGHT_PX + section.rows().size() * ROW_HEIGHT_PX;
        }
        return total + 12f;
    }

    private void drawRowBackgrounds(DebugConsole console, List<DebugConsole.Section> sections, float left, float top) {
        float y = top - ROW_HEIGHT_PX;
        for (int s = 0; s < sections.size(); s++) {
            DebugConsole.Section section = sections.get(s);
            shapes.setColor(titleBack);
            shapes.rect(left, y - TITLE_HEIGHT_PX, PANEL_WIDTH_PX, TITLE_HEIGHT_PX);
            y -= TITLE_HEIGHT_PX;
            for (int r = 0; r < section.rows().size(); r++) {
                if (s == console.cursorSection() && r == console.cursorRow()) {
                    shapes.setColor(selected);
                    shapes.rect(left, y - ROW_HEIGHT_PX, PANEL_WIDTH_PX, ROW_HEIGHT_PX);
                }
                y -= ROW_HEIGHT_PX;
            }
        }
    }

    private void drawText(DebugConsole console, List<DebugConsole.Section> sections, float left, float top) {
        float x = left + 10f;
        float valueX = left + PANEL_WIDTH_PX - 12f;
        float y = top - 5f;

        font.setColor(accent);
        font.draw(batch, "DEBUG CONSOLE   ` or F1 to close", x, y);
        y -= ROW_HEIGHT_PX;

        for (int s = 0; s < sections.size(); s++) {
            DebugConsole.Section section = sections.get(s);
            font.setColor(accent);
            font.draw(batch, section.title(), x, y - 6f);
            y -= TITLE_HEIGHT_PX;
            for (int r = 0; r < section.rows().size(); r++) {
                DebugCommand row = section.rows().get(r);
                boolean isCursor = s == console.cursorSection() && r == console.cursorRow();
                font.setColor(isCursor ? Color.WHITE : row.actionable() ? Color.LIGHT_GRAY : muted);
                font.draw(batch, row.label(), x + 6f, y - 4f);
                String value = row.value().get();
                if (!value.isEmpty()) {
                    // Right-aligned by asking the font to lay the string out in a zero-width box
                    // at the right edge, so a value that grows does not push the label about.
                    font.draw(batch, value, valueX - 160f, y - 4f, 160f, com.badlogic.gdx.utils.Align.right, false);
                }
                y -= ROW_HEIGHT_PX;
            }
        }

        font.setColor(muted);
        font.draw(batch, console.lastMessage(), x, y - 4f);
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
        font.dispose();
    }
}
