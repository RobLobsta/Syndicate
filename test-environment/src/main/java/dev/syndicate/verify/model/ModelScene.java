/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.model;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.verify.render.ModelRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a model and captures it (docs/14_test_environment.md#D14-S5.11).
 *
 * <p>Two views, not one: a front three-quarter and a rear three-quarter. Which end of a car is the
 * front is the one thing the geometric checks cannot decide — {@code MODEL-006} can tell that the
 * long axis is Z and not that +Z is the nose — and two views settle it in the time it takes to look
 * at them. They also catch the failures that a single angle hides: geometry missing from one side,
 * glass rendered opaque, a wheel left behind by a transform.
 *
 * <p>The GL context lives only here. The checks in {@link ModelInspector} run with none, which is
 * what keeps them available to CI (D14-S5.13, G17).
 */
public final class ModelScene extends ApplicationAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ModelScene.class);

    /** Ignored frames before the first capture: the software GL path needs one to warm up. */
    private static final int SETTLE_FRAMES = 2;

    private final GltfModel model;
    private final Path capturePath;
    private final List<Path> captures = new ArrayList<>();

    private ModelRenderer renderer;
    private final Vector3 centre = new Vector3();
    private final Vector3 focus = new Vector3();
    private float distance;
    private int frame;
    private int viewIndex;

    /** Eye offsets, as multiples of the framing distance, in the order they are captured. */
    private static final Vector3[] VIEWS = {
        new Vector3(0.62f, 0.30f, 0.72f).nor(), // front three-quarter, from +Z
        new Vector3(-0.62f, 0.30f, -0.72f).nor() // rear three-quarter, from −Z
    };

    private static final String[] VIEW_SUFFIX = {"", "_rear"};

    public ModelScene(GltfModel model, Path capturePath) {
        this.model = model;
        this.capturePath = capturePath;
    }

    /** Paths of every frame written, in capture order. */
    public List<Path> captures() {
        return captures;
    }

    @Override
    public void create() {
        renderer = new ModelRenderer(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        renderer.load(model);

        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        if (!model.bounds(min, max)) {
            throw new IllegalStateException("nothing to render: " + model.source());
        }
        centre.set(min).add(max).scl(0.5f);
        // Framed on the model's own size so a 4.7 m car and a 0.5 m part both fill the frame, and
        // lifted a little because a car photographed from its own mid-height looks like a diagram.
        distance = new Vector3(max).sub(min).len() * 1.25f;
        focus.set(centre).add(0f, new Vector3(max).sub(min).y * 0.05f, 0f);
        renderer.buildGrid(Math.max(6f, distance), 1f);

        LOG.info(
                "model scene: {} mesh nodes, {} triangles, bounds {} .. {}",
                model.meshNodes().size(),
                model.triangleCount(),
                min,
                max);
    }

    @Override
    public void render() {
        aim();
        renderer.render(new Color(0.086f, 0.094f, 0.118f, 1f));
        frame++;
        if (frame <= SETTLE_FRAMES) {
            return;
        }
        if (capturePath != null && viewIndex < VIEWS.length) {
            write(pathFor(viewIndex));
            viewIndex++;
            if (viewIndex < VIEWS.length) {
                return; // one more view to draw before the app can end
            }
        }
        if (capturePath != null) {
            Gdx.app.exit();
        }
    }

    private void aim() {
        Vector3 view = VIEWS[Math.min(viewIndex, VIEWS.length - 1)];
        renderer.look(new Vector3(view).scl(distance).add(focus), focus);
    }

    private Path pathFor(int index) {
        if (index == 0) {
            return capturePath;
        }
        String name = capturePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        Path directory = capturePath.toAbsolutePath().getParent();
        return directory.resolve(stem + VIEW_SUFFIX[index] + extension);
    }

    private void write(Path target) {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        Pixmap pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, width, height);
        Pixmap upright = flipVertically(pixmap);
        pixmap.dispose();
        PixmapIO.writePNG(Gdx.files.absolute(target.toAbsolutePath().toString()), upright);
        upright.dispose();
        captures.add(target);
        LOG.info("captured {} ({}x{})", target, width, height);
    }

    /** OpenGL reads the framebuffer bottom-up; PNG stores top-down. */
    private static Pixmap flipVertically(Pixmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        Pixmap out = new Pixmap(width, height, source.getFormat());
        out.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            out.drawPixmap(source, 0, y, 0, height - 1 - y, width, 1);
        }
        return out;
    }

    @Override
    public void dispose() {
        if (renderer != null) {
            renderer.dispose();
        }
    }
}
