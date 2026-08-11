/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.core.asset.ArenaDef;
import java.nio.file.Path;
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider;

/**
 * Everything the client owns that needs a GL context (docs/03_runtime_modes.md#D03-S5.1 step 8).
 *
 * <p>It exists so that D03-S5.6's teardown has one thing to close and one order to close it in.
 * Every field here is a native or GPU resource with exactly one owner (G19), and the order below is
 * the order they must go in: the batch releases its shaders first, then the geometry those shaders
 * were drawing, then the environment's cubemaps that the geometry's materials referenced.
 *
 * <p>Constructed only in a rendering mode. A dedicated server never reaches this class, because the
 * systems that hold it are absent from its schedule (D03-S5.2, G17).
 */
public final class RenderContext implements Disposable {

    private final RenderEnvironment environment;
    private final PartModels partModels;
    private final ArenaModel arenaModel;
    private final ModelBatch batch;
    private final ParticleRenderer particles;
    private final ChaseCamera camera;
    private final Hud hud;

    /**
     * @param assetRoot where {@code parts/<id>/mesh.glb} is found (D08-S4.6)
     * @param arena the arena to draw, or null when none loaded — in which case the client shows the
     *     vehicles against empty space rather than refusing to start (G18)
     */
    public RenderContext(Path assetRoot, ArenaDef arena) {
        environment = new RenderEnvironment();
        partModels = new PartModels(assetRoot);
        arenaModel = arena == null ? null : new ArenaModel(arena);
        // Zero bones: nothing shipped is skinned, and asking the shader provider for skinning
        // support it will not use costs a uniform array on every draw (DISC-016 is the related trap
        // on the reader side).
        batch = new ModelBatch(PBRShaderProvider.createDefault(0));
        particles = new ParticleRenderer();
        camera = new ChaseCamera(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        hud = new Hud();
    }

    public RenderEnvironment environment() {
        return environment;
    }

    public PartModels partModels() {
        return partModels;
    }

    /** The arena's drawable geometry, or null when no arena is loaded. */
    public ArenaModel arenaModel() {
        return arenaModel;
    }

    public ModelBatch batch() {
        return batch;
    }

    public ParticleRenderer particles() {
        return particles;
    }

    public ChaseCamera camera() {
        return camera;
    }

    public Hud hud() {
        return hud;
    }

    /** Propagates a window resize to everything that projects. */
    public void resize(int width, int height) {
        camera.resize(width, height);
        hud.resize(width, height);
    }

    @Override
    public void dispose() {
        batch.dispose();
        particles.dispose();
        hud.dispose();
        if (arenaModel != null) {
            arenaModel.dispose();
        }
        partModels.dispose();
        environment.dispose();
    }
}
