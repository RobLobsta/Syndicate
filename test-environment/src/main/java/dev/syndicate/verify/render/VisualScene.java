/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.render;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.utils.ScreenUtils;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.verify.asset.FractureManifest;
import dev.syndicate.verify.asset.MeshData;
import dev.syndicate.verify.physics.DestructionScene;
import dev.syndicate.verify.physics.TestWorld;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The visual mode of docs/14_test_environment.md#D14-S5.11: the destruction progression, rendered.
 *
 * <p>The simulation here is the same {@code DestructionScene} the headless checks run — the same
 * bodies, the same shapes, the same seeded scatter. That is what makes a capture evidence rather
 * than an illustration: what the image shows is what the checks measured.
 *
 * <p>Time advances by whole ticks accumulated from the frame delta, never by the frame delta itself
 * (G2, D14-S5.11). A slower machine therefore renders fewer frames of the same simulation rather
 * than a different simulation.
 */
public final class VisualScene extends ApplicationAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(VisualScene.class);

    private final FractureManifest manifest;
    private final MeshData intactMesh;
    private final List<MeshData> shardMeshes;
    private final long seed;
    private final float scatterStrength;

    /** Ticks after the fracture at which to capture, or {@code -1} to run interactively. */
    private final int captureTick;

    private final Path capturePath;
    private final List<Path> extraCaptures = new ArrayList<>();

    private TestWorld world;
    private DestructionScene scene;
    private SceneRenderer renderer;
    private Mesh intactRenderMesh;
    private Mesh grid;
    private final List<Mesh> shardRenderMeshes = new ArrayList<>();

    private final Vector3 focus = new Vector3(0f, 1.4f, 0f);
    private float accumulator;
    private long tick;
    private int capturesWritten;
    private boolean finished;
    private Map<String, Object> captureData = new LinkedHashMap<>();

    /** How many ticks the part falls before it is detonated, so it is in shot and settling. */
    private static final int FRACTURE_AT_TICK = 24;

    public VisualScene(
            FractureManifest manifest,
            MeshData intactMesh,
            List<MeshData> shardMeshes,
            long seed,
            float scatterStrength,
            int captureTick,
            Path capturePath) {
        this.manifest = manifest;
        this.intactMesh = intactMesh;
        this.shardMeshes = shardMeshes;
        this.seed = seed;
        this.scatterStrength = scatterStrength;
        this.captureTick = captureTick;
        this.capturePath = capturePath;
    }

    /** Paths of every frame written, in capture order. */
    public List<Path> captures() {
        return extraCaptures;
    }

    @Override
    public void create() {
        world = new TestWorld(true);
        scene = new DestructionScene(world, manifest, intactMesh, shardMeshes, new Vector3(0f, 2.2f, 0f));
        scene.spawnIntact();

        renderer = new SceneRenderer(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        intactRenderMesh = renderer.build(intactMesh);
        grid = renderer.buildGrid(14f, 1f);

        // Framed from slightly above the debris and back, far enough that a full scatter stays in
        // shot: the point of the capture is the spread, so a tighter shot that crops it says less.
        // Framed on where the debris *will* be, not where the part starts: it falls for
        // FRACTURE_AT_TICK before detonating and keeps falling after, so aiming at the spawn point
        // crops the scatter off the bottom of the frame.
        float radius = Math.max(3.2f, extent() * 3.6f);
        focus.set(0f, 1.05f, 0f);
        renderer.look(new Vector3(radius * 0.78f, radius * 0.52f, radius * 1.02f), focus);
        LOG.info(
                "visual scene: {} shards, part {} kg, fracture at tick {}, capture at +{} ticks",
                manifest.shardCount,
                manifest.partMassKg,
                FRACTURE_AT_TICK,
                captureTick);
    }

    @Override
    public void render() {
        if (!finished) {
            accumulator += Math.min(Gdx.graphics.getDeltaTime(), 0.25f);
            while (accumulator >= SimulationConstants.TICK_DT) {
                stepOnce();
                accumulator -= SimulationConstants.TICK_DT;
            }
        }
        drawScene();

        if (!finished && shouldCapture()) {
            writeCapture();
        }
        if (finished) {
            Gdx.app.exit();
        }
    }

    private void stepOnce() {
        if (tick == FRACTURE_AT_TICK) {
            scene.fracture(scatterStrength, seed);
            for (MeshData mesh : scene.shardMeshOrder()) {
                shardRenderMeshes.add(renderer.build(mesh));
            }
            LOG.info(
                    "fractured at tick {} into {} bodies",
                    tick,
                    scene.shardBodies().size());
        }
        world.step();
        tick++;
    }

    private boolean shouldCapture() {
        if (capturePath == null || !scene.hasFractured()) {
            return false;
        }
        long sinceFracture = tick - scene.fractureTick();
        return sinceFracture >= captureTick && capturesWritten == 0;
    }

    private void drawScene() {
        renderer.begin(new Color(0.086f, 0.094f, 0.118f, 1f));
        renderer.drawLines(grid, new Color(0.22f, 0.24f, 0.30f, 1f));

        Matrix4 transform = new Matrix4();
        if (!scene.hasFractured() && scene.intactBody() != null) {
            scene.intactBody().getWorldTransform(transform);
            renderer.draw(intactRenderMesh, transform, new Color(0.72f, 0.74f, 0.78f, 1f));
            return;
        }
        List<btRigidBody> bodies = scene.shardBodies();
        for (int i = 0; i < bodies.size() && i < shardRenderMeshes.size(); i++) {
            bodies.get(i).getWorldTransform(transform);
            renderer.draw(shardRenderMeshes.get(i), transform, SceneRenderer.shardColor(i));
        }
    }

    private void writeCapture() {
        // Read back after drawing this frame and before the buffer is swapped away.
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        Pixmap pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, width, height);
        Pixmap upright = flipVertically(pixmap);
        pixmap.dispose();

        PixmapIO.writePNG(Gdx.files.absolute(capturePath.toAbsolutePath().toString()), upright);
        upright.dispose();

        extraCaptures.add(capturePath);
        capturesWritten++;
        // Snapshotted here rather than read after the app loop returns: by then `dispose()` has
        // freed the Bullet bodies, and asking a freed body for its velocity is a segfault in
        // native code rather than a Java exception (G19).
        captureData = measure();
        LOG.info(
                "captured {} at tick {} (+{} after fracture), {} shard bodies, max shard speed {} m/s",
                capturePath,
                tick,
                tick - scene.fractureTick(),
                scene.shardBodies().size(),
                String.format("%.2f", scene.maxShardSpeedMps()));
        finished = true;
    }

    /**
     * OpenGL reads the framebuffer bottom-up; PNG stores top-down.
     *
     * <p>Without the flip the capture comes out mirrored, which on a symmetric scene looks merely
     * odd and on this one puts the ground plane above the debris.
     */
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

    /** Measurements of the captured moment, for the caller's report. */
    public Map<String, Object> captureData() {
        return captureData;
    }

    private Map<String, Object> measure() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capture_tick", tick);
        data.put("fracture_tick", scene == null ? -1 : scene.fractureTick());
        data.put("shard_bodies", scene == null ? 0 : scene.shardBodies().size());
        data.put("max_shard_speed_mps", scene == null ? 0.0 : scene.maxShardSpeedMps());
        data.put("live_shard_mass_kg", scene == null ? 0.0 : scene.liveShardMassKg());
        return data;
    }

    private float extent() {
        Vector3 min = new Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3 max = new Vector3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        Vector3 vertex = new Vector3();
        for (int i = 0; i < intactMesh.vertexCount(); i++) {
            intactMesh.vertex(i, vertex);
            min.x = Math.min(min.x, vertex.x);
            min.y = Math.min(min.y, vertex.y);
            min.z = Math.min(min.z, vertex.z);
            max.x = Math.max(max.x, vertex.x);
            max.y = Math.max(max.y, vertex.y);
            max.z = Math.max(max.z, vertex.z);
        }
        return Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z));
    }

    @Override
    public void dispose() {
        if (renderer != null) {
            renderer.dispose();
        }
        if (world != null) {
            world.dispose();
        }
    }
}
