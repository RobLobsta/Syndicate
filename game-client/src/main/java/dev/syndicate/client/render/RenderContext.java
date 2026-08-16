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
    private final PartLamps partLamps;
    private final PartArticulation partArticulation;
    private final VehicleLights vehicleLights;
    private final ArenaModel arenaModel;
    private TerrainModel terrainModel;
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
        partLamps = new PartLamps(assetRoot);
        partArticulation = new PartArticulation(assetRoot);
        vehicleLights = new VehicleLights();
        arenaModel = arena == null ? null : new ArenaModel(arena);
        batch = new ModelBatch(PBRShaderProvider.createDefault(shaderConfig()));
        particles = new ParticleRenderer();
        camera = new ChaseCamera(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        hud = new Hud();
    }

    /**
     * The shader the whole scene is drawn with.
     *
     * <p>Zero bones: nothing shipped is skinned, and asking for skinning support the shader will not
     * use costs a uniform array on every draw (DISC-016 is the related trap on the reader side).
     *
     * <p><b>Spot lights have to be asked for.</b> libGDX's shader config defaults
     * {@code numSpotLights} to zero, and gdx-gltf inherits that — so a {@code SpotLightEx} added to
     * the environment is compiled out of the shader and a headlight lights nothing at all, silently.
     * That is the whole of why {@link VehicleLights#MAX_CASTING_LAMPS} is a number here rather than
     * a matter of taste.
     */
    private static net.mgsx.gltf.scene3d.shaders.PBRShaderConfig shaderConfig() {
        net.mgsx.gltf.scene3d.shaders.PBRShaderConfig config = PBRShaderProvider.createDefaultConfig();
        config.numBones = 0;
        config.numSpotLights = VehicleLights.MAX_CASTING_LAMPS;
        return config;
    }

    public RenderEnvironment environment() {
        return environment;
    }

    /** The {@code light} block of every part that carries one (D08-R6). */
    public PartLamps partLamps() {
        return partLamps;
    }

    /** The {@code articulation} block of every part that moves (D17-S4.4). */
    public PartArticulation partArticulation() {
        return partArticulation;
    }

    /** The lamps placed in the world this frame, and the beams drawn from them. */
    public VehicleLights vehicleLights() {
        return vehicleLights;
    }

    public PartModels partModels() {
        return partModels;
    }

    /** The arena's drawable box, or null when no arena is loaded or the arena has terrain. */
    public ArenaModel arenaModel() {
        return terrainModel == null ? arenaModel : null;
    }

    /** The generated ground, or null on a flat arena. */
    public TerrainModel terrainModel() {
        return terrainModel;
    }

    /**
     * Hands over the generated ground once the arena has been loaded.
     *
     * <p>Set afterwards rather than passed to the constructor because the field does not exist yet
     * when this is built: generating it needs the physics world, and this needs a GL context. From
     * here the box floor and walls stop being drawn — an arena has either generated ground or the
     * flat box, never both, and drawing both would put a plane through every heap.
     */
    public void useTerrain(dev.syndicate.core.arena.TerrainField field) {
        if (terrainModel != null) {
            terrainModel.dispose();
        }
        terrainModel = field == null ? null : new TerrainModel(field);
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
        vehicleLights.dispose();
        particles.dispose();
        hud.dispose();
        if (terrainModel != null) {
            terrainModel.dispose();
        }
        if (arenaModel != null) {
            arenaModel.dispose();
        }
        partModels.dispose();
        environment.dispose();
    }
}
