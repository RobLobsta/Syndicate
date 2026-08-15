/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Disposable;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Menu type, rasterised at the size it is actually drawn at (DEC-072).
 *
 * <p>libGDX's built-in {@code BitmapFont} is a 15-pixel raster. The shell draws a heading at five
 * times that, and a 5× magnified bitmap font is visibly mush — the single thing that makes a menu
 * read as a prototype rather than a product. FreeType rasterises each size from an outline instead,
 * so a 76-unit title is a 76-pixel glyph.
 *
 * <p><b>One generator, a font per size, cached.</b> Generating is not free and the shell asks for
 * the same handful of sizes every frame; without the cache this would rebuild an atlas per draw
 * call. The cache is keyed on the <em>pixel</em> size after the display scale is applied, so a
 * window resized from 720p to 4K gets crisp glyphs at the new size rather than the old ones
 * stretched.
 *
 * <p><b>Degrades rather than fails</b> (G18). If the FreeType native is missing or the font file is
 * not beside the executable, every request returns the built-in bitmap font: the game starts, the
 * menus work, and the text is ugly. A missing font is not a reason to refuse to run.
 *
 * <p><b>Owner of the generator and every font it produced</b> (G19).
 */
public final class MenuFonts implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(MenuFonts.class);

    /** Where the shipped typeface lives, under the asset root (D08-S4.6). */
    public static final String FONT_PATH = "fonts/oswald_variable.ttf";

    /**
     * Sizes are rounded to this many pixels before the cache is consulted.
     *
     * <p>Without it, a window being dragged to a new size would generate an atlas per pixel of
     * height crossed. Two pixels is finer than the eye can tell at these sizes and bounds the cache
     * at a few dozen entries for a lifetime of resizing.
     */
    public static final int SIZE_QUANTUM_PX = 2;

    /** Below this, FreeType produces nothing useful and the built-in font is no worse. */
    public static final int MIN_SIZE_PX = 6;

    /** Above this, an atlas costs more memory than any menu needs. */
    public static final int MAX_SIZE_PX = 220;

    private final FreeTypeFontGenerator generator;
    private final Map<Integer, BitmapFont> cache = new LinkedHashMap<>();
    private final BitmapFont fallback = new BitmapFont();

    public MenuFonts(Path assetRoot) {
        this.generator = createGenerator(assetRoot);
        if (generator == null) {
            LOG.warn("menu type falls back to the built-in bitmap font; headings will look soft");
        }
    }

    /** True when real outline rendering is available, which is what a capture should report. */
    public boolean isScalable() {
        return generator != null;
    }

    /**
     * A font whose glyphs are {@code sizePx} pixels tall.
     *
     * <p>The returned font is owned here and must not be disposed by the caller.
     */
    public BitmapFont at(float sizePx) {
        if (generator == null) {
            // The caller still scales the fallback, so text lands at roughly the right size — just
            // blurred. Returning a fixed-size font would break every layout instead of only its
            // sharpness.
            fallback.getData().setScale(Math.max(0.1f, sizePx / 15f));
            return fallback;
        }
        int quantised = Math.round(sizePx / SIZE_QUANTUM_PX) * SIZE_QUANTUM_PX;
        int clamped = Math.max(MIN_SIZE_PX, Math.min(MAX_SIZE_PX, quantised));
        return cache.computeIfAbsent(clamped, this::generate);
    }

    private BitmapFont generate(int sizePx) {
        FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
        parameter.size = sizePx;
        // Linear both ways: the shell draws at fractional positions and at a scale of exactly 1,
        // and nearest filtering makes a glyph shimmer as the layout moves under a resize.
        parameter.minFilter = Texture.TextureFilter.Linear;
        parameter.magFilter = Texture.TextureFilter.Linear;
        // A one-pixel dark border, not for style: the garage draws type over a 3D preview whose
        // brightness it does not control, and unbordered light text over a pale car is unreadable.
        parameter.borderWidth = Math.max(1f, sizePx * 0.035f);
        parameter.borderColor = new com.badlogic.gdx.graphics.Color(0f, 0f, 0f, 0.65f);
        parameter.borderStraight = false;
        return generator.generateFont(parameter);
    }

    private static FreeTypeFontGenerator createGenerator(Path assetRoot) {
        if (assetRoot == null) {
            return null;
        }
        try {
            FileHandle handle = Gdx.files.absolute(
                    assetRoot.resolve(FONT_PATH).toAbsolutePath().toString());
            if (!handle.exists()) {
                LOG.warn("no typeface at {}", handle.path());
                return null;
            }
            return new FreeTypeFontGenerator(handle);
        } catch (RuntimeException | LinkageError e) {
            // LinkageError as well as RuntimeException: a missing FreeType native fails when the
            // class is first touched, not when the file is read, and that is exactly the case this
            // fallback exists for.
            LOG.warn("FreeType is unavailable ({}); using the built-in font", e.toString());
            return null;
        }
    }

    @Override
    public void dispose() {
        cache.values().forEach(BitmapFont::dispose);
        cache.clear();
        if (generator != null) {
            generator.dispose();
        }
        fallback.dispose();
    }
}
