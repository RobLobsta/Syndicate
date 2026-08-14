/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Disposable;

/**
 * How every menu in the game is drawn: one palette, one panel shape, one set of type sizes.
 *
 * <p>Built on the same three primitives {@code Hud} uses — a sprite batch, a shape renderer and
 * bitmap fonts — rather than on scene2d. The menus here are a title, a list and a stat block; a
 * widget toolkit would add a dependency, a skin file and a layout system to draw three things, and
 * the HUD already proves these primitives are enough.
 *
 * <p><b>The look</b> is the genre's: a scavenged machine in a dust bowl. Near-black warm greys, one
 * amber accent doing all the signalling, panels with a corner cut off so nothing reads as a
 * business form, and wide-tracked uppercase labels. Every colour is defined here and nowhere else,
 * so changing the game's mood is changing this file.
 *
 * <p><b>Owner of the fonts, a sprite batch and a shape renderer</b> (G19), disposed by
 * {@link MenuContext}.
 */
public final class MenuChrome implements Disposable {

    // ---- Palette ---------------------------------------------------------------------

    /** The void behind everything: warm near-black, not blue-black. */
    public static final Color BACKDROP = new Color(0.055f, 0.051f, 0.047f, 1f);

    /** Backdrop at the horizon, so the screen has a light source rather than being flat. */
    public static final Color BACKDROP_GLOW = new Color(0.150f, 0.118f, 0.086f, 1f);

    /** Panel fill: lighter than the backdrop, still dark enough that white type sings. */
    public static final Color PANEL = new Color(0.098f, 0.094f, 0.090f, 0.94f);

    /** The one accent. Selection, headings, and the value in a stat row. */
    public static final Color ACCENT = new Color(0.933f, 0.639f, 0.239f, 1f);

    /** Accent at low alpha, for the bar behind a selected row. */
    public static final Color ACCENT_WASH = new Color(0.933f, 0.639f, 0.239f, 0.11f);

    /** Body text. */
    public static final Color TEXT = new Color(0.878f, 0.867f, 0.847f, 1f);

    /** Labels, units, and anything the eye should skip on the way to a value. */
    public static final Color TEXT_DIM = new Color(0.482f, 0.467f, 0.447f, 1f);

    /** Rules and panel edges. */
    public static final Color EDGE = new Color(0.239f, 0.227f, 0.212f, 1f);

    // ---- Metrics ---------------------------------------------------------------------

    /**
     * The height the layout is authored against.
     *
     * <p>Every size below is in these units and scaled to the real back buffer, so the menu looks
     * the same on a 720p laptop and a 4K monitor instead of shrinking to a strip of ants on one.
     */
    public static final float DESIGN_HEIGHT_PX = 900f;

    /** How far the cut corner reaches along each edge, in design units. */
    public static final float CHAMFER_PX = 14f;

    private final SpriteBatch batch = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final MenuFonts fonts;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final GlyphLayout layout = new GlyphLayout();

    private float scale = 1f;
    private float widthPx;
    private float heightPx;

    public MenuChrome(java.nio.file.Path assetRoot) {
        this.fonts = new MenuFonts(assetRoot);
    }

    /** True when the shipped typeface loaded, so a capture can say which look it photographed. */
    public boolean hasScalableType() {
        return fonts.isScalable();
    }

    /** Design units to real pixels. */
    public float px(float designUnits) {
        return designUnits * scale;
    }

    /** Back buffer width, in design units. */
    public float width() {
        return widthPx / scale;
    }

    /** Back buffer height, in design units. Always {@link #DESIGN_HEIGHT_PX}. */
    public float height() {
        return heightPx / scale;
    }

    /**
     * Begins a frame: sizes the camera to the real back buffer and paints the backdrop.
     *
     * <p>Read from the back buffer rather than from the logical window every frame, because on a
     * high-DPI display those differ and a menu laid out in logical pixels lands in a quarter of the
     * screen. {@code Hud} does the same for the same reason.
     */
    public void begin() {
        widthPx = Gdx.graphics.getBackBufferWidth();
        heightPx = Gdx.graphics.getBackBufferHeight();
        scale = heightPx / DESIGN_HEIGHT_PX;

        camera.setToOrtho(false, widthPx, heightPx);
        camera.update();
        batch.setProjectionMatrix(camera.combined);
        shapes.setProjectionMatrix(camera.combined);

        Gdx.gl.glClearColor(BACKDROP.r, BACKDROP.g, BACKDROP.b, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // A warm wash rising from the bottom edge. Cheap, and it is the whole difference between
        // "a screen with things on it" and "a place lit by something".
        beginShapes();
        shapes.rect(0f, 0f, widthPx, heightPx * 0.55f, BACKDROP_GLOW, BACKDROP_GLOW, BACKDROP, BACKDROP);
        shapes.end();
    }

    /**
     * Enables blending and opens the shape renderer.
     *
     * <p>Every shape draw goes through here, and the blend enable is <b>not</b> hoisted into
     * {@link #begin()}. {@code SpriteBatch.end()} disables blending as part of restoring GL state,
     * so any shape drawn after a line of text would otherwise come out fully opaque — which turned
     * a 16%-alpha selection wash into a solid cream bar the first time this screen was photographed.
     */
    private void beginShapes() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
    }

    /** Ends a frame. Nothing is drawn after this. */
    public void end() {
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /**
     * A filled panel with its top-right corner cut away.
     *
     * @param x left edge, design units from the left of the screen
     * @param y bottom edge, design units from the bottom
     */
    public void panel(float x, float y, float width, float height, Color fill) {
        float left = px(x);
        float bottom = px(y);
        float right = px(x + width);
        float top = px(y + height);
        float cut = px(CHAMFER_PX);

        beginShapes();
        shapes.setColor(fill);
        shapes.rect(left, bottom, right - left, top - bottom - cut);
        shapes.rect(left, top - cut, right - left - cut, cut);
        shapes.triangle(right - cut, top - cut, right, top - cut, right - cut, top);
        shapes.end();
    }

    /** A one-unit horizontal rule. */
    public void rule(float x, float y, float width, Color colour) {
        beginShapes();
        shapes.setColor(colour);
        shapes.rect(px(x), px(y), px(width), Math.max(1f, px(1f)));
        shapes.end();
    }

    /** A filled rectangle in design units — selection washes, bars, accent ticks. */
    public void fill(float x, float y, float width, float height, Color colour) {
        beginShapes();
        shapes.setColor(colour);
        shapes.rect(px(x), px(y), px(width), px(height));
        shapes.end();
    }

    /**
     * Draws text with its baseline at {@code y}.
     *
     * @param sizeUnits cap height in design units — 16 is a label, 22 is a row, 64 is the title
     * @return the width drawn, in design units, so a caller can right-align the next thing
     */
    public float text(String value, float x, float y, float sizeUnits, Color colour) {
        BitmapFont chosen = fonts.at(px(sizeUnits));
        chosen.setColor(colour);
        batch.begin();
        layout.setText(chosen, value);
        chosen.draw(batch, layout, px(x), px(y) + layout.height);
        batch.end();
        return layout.width / scale;
    }

    /** Measures what {@link #text} would draw, in design units. */
    public float measure(String value, float sizeUnits) {
        layout.setText(fonts.at(px(sizeUnits)), value);
        return layout.width / scale;
    }

    /** Draws text centred on {@code centreX}. */
    public float textCentred(String value, float centreX, float y, float sizeUnits, Color colour) {
        return text(value, centreX - measure(value, sizeUnits) * 0.5f, y, sizeUnits, colour);
    }

    /** Draws text ending at {@code rightX}. */
    public float textRight(String value, float rightX, float y, float sizeUnits, Color colour) {
        return text(value, rightX - measure(value, sizeUnits), y, sizeUnits, colour);
    }

    /**
     * A label / value pair on one line — the whole vocabulary of the garage's stat block.
     *
     * <p>Label left and dim, value right and bright, because a column of right-aligned numbers can
     * be compared down the page and a column of left-aligned ones cannot.
     */
    public void statRow(String label, String value, float x, float y, float width, float sizeUnits) {
        text(label.toUpperCase(java.util.Locale.ROOT), x, y, sizeUnits, TEXT_DIM);
        textRight(value, x + width, y, sizeUnits, TEXT);
    }

    /** A horizontal meter, for a stat that means more as a length than as a number. */
    public void meter(float x, float y, float width, float height, float fraction, Color colour) {
        fill(x, y, width, height, EDGE);
        float clamped = Math.max(0f, Math.min(1f, fraction));
        if (clamped > 0f) {
            fill(x, y, width * clamped, height, colour);
        }
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
        fonts.dispose();
    }
}
