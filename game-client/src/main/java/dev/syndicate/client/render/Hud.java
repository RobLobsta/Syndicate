/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.client.input.InputDeviceKind;
import dev.syndicate.model.MatchOutcome;
import dev.syndicate.model.MatchPhase;
import dev.syndicate.model.SimulationConstants;
import java.util.List;

/**
 * What the player is told, over the top of what they are shown
 * (docs/02_technical_architecture.md#D02-S4.5, docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>Four things, chosen because each answers a question the 3D view cannot: <b>how fast am I
 * going</b> (the view shows motion, not a number), <b>how broken am I</b> (damage is distributed
 * across parts and the visible dents lag the health), <b>who is winning</b>, and <b>how long is
 * left</b>. Everything else the scene already says better than text would.
 *
 * <p>Drawn in screen pixels through an orthographic camera of its own rather than in the 3D camera's
 * space, so the layout does not move with the car — and rebuilt against the real back buffer size
 * each frame, which is what keeps it correct on a resized window and on a high-DPI display where
 * logical and physical pixels differ.
 *
 * <p><b>Owner of a font, a sprite batch and a shape renderer</b> (G19).
 */
public final class Hud implements Disposable {

    /** Pixels from the window edge to the panels. */
    public static final float MARGIN_PX = 18f;

    /** Pixels. Height of the health and boost bars. */
    public static final float BAR_HEIGHT_PX = 14f;

    /** Pixels. Width of the health bar. */
    public static final float BAR_WIDTH_PX = 260f;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont font = new BitmapFont();
    private final BitmapFont bigFont = new BitmapFont();
    private final OrthographicCamera camera = new OrthographicCamera();

    private final GlyphLayout layout = new GlyphLayout();
    private final Color barBack = new Color(0f, 0f, 0f, 0.55f);
    private final Color panelBack = new Color(0.06f, 0.07f, 0.09f, 0.55f);

    public Hud() {
        font.setColor(Color.WHITE);
        bigFont.getData().setScale(1.8f);
        bigFont.setColor(Color.WHITE);
    }

    /** What one frame of HUD needs to know. A record so the renderer cannot reach into the world. */
    public record Frame(
            boolean hasVehicle,
            float speedMps,
            float topSpeedMps,
            float healthFraction,
            int livePartCount,
            int totalPartCount,
            MatchPhase phase,
            MatchOutcome outcome,
            int ticksRemaining,
            List<ScoreRow> scoreboard,
            InputDeviceKind device,
            int framesPerSecond) {}

    /** One player's line on the scoreboard. */
    public record ScoreRow(String name, boolean isBot, boolean isLocal, int kills, int deaths, int score) {}

    /** Draws the whole HUD for one frame. */
    public void render(Frame frame) {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        camera.setToOrtho(false, width, height);
        camera.update();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawPanels(frame, width, height);
        shapes.end();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        drawText(frame, width, height);
        batch.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Rebuilds the projection after a window resize. */
    public void resize(int width, int height) {
        camera.setToOrtho(false, width, height);
        camera.update();
    }

    private void drawPanels(Frame frame, int width, int height) {
        if (frame.hasVehicle()) {
            float y = MARGIN_PX + 44f;
            shapes.setColor(barBack);
            shapes.rect(MARGIN_PX, y, BAR_WIDTH_PX, BAR_HEIGHT_PX);
            // Green through amber to red rather than a single colour fading out: the colour is the
            // reading a player takes at a glance, and length alone is hard to judge under fire.
            float health = Math.max(0f, Math.min(1f, frame.healthFraction()));
            shapes.setColor(health, Math.min(1f, health * 1.6f), health * 0.25f, 0.92f);
            shapes.rect(MARGIN_PX, y, BAR_WIDTH_PX * health, BAR_HEIGHT_PX);
        }

        if (!frame.scoreboard().isEmpty()) {
            float rows = Math.min(8, frame.scoreboard().size());
            float panelHeight = 24f + rows * 18f;
            shapes.setColor(panelBack);
            shapes.rect(width - MARGIN_PX - 260f, height - MARGIN_PX - panelHeight, 260f, panelHeight);
        }
    }

    private void drawText(Frame frame, int width, int height) {
        if (frame.hasVehicle()) {
            bigFont.draw(batch, String.format("%3.0f", frame.speedMps() * 3.6f), MARGIN_PX, MARGIN_PX + 38f);
            font.draw(batch, "km/h", MARGIN_PX + 68f, MARGIN_PX + 24f);
            font.draw(
                    batch,
                    String.format("%d / %d parts", frame.livePartCount(), frame.totalPartCount()),
                    MARGIN_PX + BAR_WIDTH_PX + 12f,
                    MARGIN_PX + 44f + BAR_HEIGHT_PX);
        } else {
            font.draw(batch, "no vehicle — spectating", MARGIN_PX, MARGIN_PX + 40f);
        }

        String clock = frame.ticksRemaining() < 0
                ? "--:--"
                : String.format(
                        "%d:%02d",
                        frame.ticksRemaining() / SimulationConstants.TICK_RATE_HZ / 60,
                        frame.ticksRemaining() / SimulationConstants.TICK_RATE_HZ % 60);
        String banner = frame.phase() == MatchPhase.RESULTS || frame.phase() == MatchPhase.ENDING
                ? frame.phase() + "  " + frame.outcome()
                : frame.phase() + "   " + clock;
        layout.setText(bigFont, banner);
        bigFont.draw(batch, layout, (width - layout.width) * 0.5f, height - MARGIN_PX);

        float y = height - MARGIN_PX - 24f;
        List<ScoreRow> rows = frame.scoreboard();
        for (int i = 0; i < Math.min(8, rows.size()); i++) {
            ScoreRow row = rows.get(i);
            font.setColor(row.isLocal() ? Color.GOLD : Color.WHITE);
            font.draw(
                    batch,
                    String.format("%-14s %2d/%-2d %4d", trim(row.name()), row.kills(), row.deaths(), row.score()),
                    width - MARGIN_PX - 250f,
                    y);
            y -= 18f;
        }
        font.setColor(Color.WHITE);

        String footer = frame.framesPerSecond() + " fps   " + frame.device();
        // Right-aligned by measuring rather than by a guessed offset: the device name changes
        // length when the player picks up a pad, and a fixed offset clips one of the two spellings.
        layout.setText(font, footer);
        font.draw(batch, layout, width - MARGIN_PX - layout.width, MARGIN_PX + 16f);
    }

    private static String trim(String name) {
        return name == null ? "?" : name.length() <= 14 ? name : name.substring(0, 14);
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
        font.dispose();
        bigFont.dispose();
    }
}
