/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.vehicle;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import dev.syndicate.core.asset.GltfOptions;
import dev.syndicate.core.asset.GltfReader;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.AssetPaths;
import dev.syndicate.verify.render.ModelRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives a shipped vehicle and photographs it (docs/14_test_environment.md#D14-S5.11).
 *
 * <p>The first mode in this harness that draws a vehicle as the game assembles it: a chassis and
 * four wheels, each its own glTF, each placed by the world matrix {@code TransformSystem} (21)
 * computed for it. Which is the point. A single-model capture proves the file is right; this proves
 * the <em>simulation</em> is, because everything visible in the frame — the wheel a car's length
 * behind the car, the three that are left, the angle each of them has rolled to — is read from
 * component state rather than posed for the camera.
 *
 * <p>Four moments, chosen for what each can falsify:
 *
 * <ol>
 *   <li><b>settled</b> — the car parked. Wheels in the arches or they are not.
 *   <li><b>rolling</b> — at speed. The tyres have turned since the first frame, or the wheels are
 *       sliding.
 *   <li><b>detach</b> — a few ticks after the front-left wheel is destroyed, while it is still
 *       beside the car.
 *   <li><b>threewheel</b> — a second later, the car driving on and the wheel behind it.
 * </ol>
 *
 * <p>The GL context lives only here; {@link VehicleRun} runs the same simulation with none.
 */
public final class VehicleScene extends ApplicationAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleScene.class);

    /** Ignored frames before the first capture: the software GL path needs one to warm up. */
    private static final int SETTLE_FRAMES = 2;

    /**
     * One moment in the script: when to photograph, what to call the file, and how close to stand.
     *
     * <p>{@code closeUp} frames the front-left wheel rather than the car. One frame in the set is
     * there to answer a question about the tyre — is its texture on it, does the rim turn — and at
     * the distance that keeps a detached wheel in shot, a 0.7 m wheel is forty pixels across.
     */
    private record Moment(int tick, String suffix, boolean closeUp) {}

    private final VehicleRun run;
    private final AssetId vehicleTypeId;
    private final Path capturePath;
    private final List<Path> captures = new ArrayList<>();
    private final Map<String, Object> measurements = new LinkedHashMap<>();

    /**
     * One uploaded glTF per part type — three files for a four-wheeled car, because both front
     * wheels are the same part.
     */
    private final Map<String, Integer> modelByPartType = new TreeMap<>();

    /**
     * Instances available per uploaded model, grown as the frame needs them.
     *
     * <p>A vehicle draws its front-wheel model twice, and after a detach three times: two on the car
     * and one bouncing down the road. Each needs its own matrix, so each needs its own instance, and
     * the count is not known until the frame is composed.
     */
    private final Map<Integer, List<Integer>> instancePool = new TreeMap<>();

    private final List<Moment> script = new ArrayList<>();
    private int nextMoment;
    private int detachTick;
    private int frame;

    private ModelRenderer renderer;
    private final Vector3 focus = new Vector3();
    private final Vector3 eye = new Vector3();

    /**
     * @param settleSeconds how long the car stands still before the throttle goes down
     * @param driveSeconds how long it accelerates before the wheel is destroyed
     */
    public VehicleScene(
            VehicleRun run, AssetId vehicleTypeId, Path capturePath, float settleSeconds, float driveSeconds) {
        this.run = run;
        this.vehicleTypeId = vehicleTypeId;
        this.capturePath = capturePath;

        int settled = VehicleRun.ticks(settleSeconds);
        detachTick = settled + VehicleRun.ticks(driveSeconds);
        script.add(new Moment(settled, "_settled", false));
        script.add(new Moment(settled + VehicleRun.ticks(0.05f), "_wheel_parked", true));
        script.add(new Moment(detachTick - 4, "_rolling", false));
        script.add(new Moment(detachTick - 2, "_wheel_rolling", true));
        script.add(new Moment(detachTick + VehicleRun.ticks(0.25f), "_detach", false));
        script.add(new Moment(detachTick + VehicleRun.ticks(0.9f), "_threewheel", false));
    }

    /** Paths of every frame written, in capture order. */
    public List<Path> captures() {
        return captures;
    }

    /** What the run measured, for the report. */
    public Map<String, Object> measurements() {
        return measurements;
    }

    @Override
    public void create() {
        renderer = new ModelRenderer(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        // Big enough that a car doing 40 m/s does not drive off the end of the world before the
        // last frame, and coarse enough that it stays a ground plane rather than a moiré.
        renderer.buildGrid(400f, 5f);
        run.spawn(vehicleTypeId);
        for (VehicleRun.Drawable drawable : run.drawables()) {
            modelFor(drawable.partTypeId());
        }
        measurements.put("vehicleTypeId", vehicleTypeId.value());
        measurements.put("partModels", new ArrayList<>(modelByPartType.keySet()));
    }

    /**
     * Uploads one part type's visual mesh, once.
     *
     * <p>The part's own {@code mesh.glb} — the file the preparation pipeline wrote, not the source
     * art, so what is drawn is the content the game ships rather than the model it was cut from. The
     * directory is resolved rather than assumed, because a part lives either in the shared library
     * or under the vehicle that owns it (D08-R14b).
     *
     * @return the model handle, or -1 if the file could not be read
     */
    private int modelFor(AssetId partTypeId) {
        Integer existing = modelByPartType.get(partTypeId.value());
        if (existing != null) {
            return existing;
        }
        Path partDirectory = AssetPaths.partDirectory(run.assetRoot(), partTypeId.value());
        Path file = partDirectory == null
                ? run.assetRoot()
                        .resolve(AssetPaths.SHARED_PARTS_DIR)
                        .resolve(partTypeId.value())
                        .resolve("mesh.glb")
                : partDirectory.resolve("mesh.glb");
        int modelId;
        try {
            // Everything but the collision node: the hull is in the same file (D08-R3), carries no
            // material, and drawn would be a white shell around the car.
            String collisionNode = partTypeId.value() + "_col";
            modelId = renderer.upload(GltfReader.read(file, GltfOptions.FULL), name -> !collisionNode.equals(name));
        } catch (RuntimeException e) {
            LOG.error("part {} has no drawable mesh at {}: {}", partTypeId.value(), file, e.toString());
            modelId = -1;
        }
        modelByPartType.put(partTypeId.value(), modelId);
        return modelId;
    }

    @Override
    public void render() {
        frame++;
        if (frame <= SETTLE_FRAMES) {
            draw();
            return;
        }
        // One tick per frame. The capture is not real-time and does not need to be: what matters is
        // that the frame shown is the state of a given tick, not of whatever the renderer caught up
        // to (G2).
        advanceToNextMoment();
        draw();

        if (nextMoment < script.size()) {
            write(script.get(nextMoment));
            nextMoment++;
            if (nextMoment < script.size()) {
                return;
            }
        }
        Gdx.app.exit();
    }

    /** Steps the simulation to the next moment, running the script's inputs on the way. */
    private void advanceToNextMoment() {
        if (nextMoment >= script.size()) {
            return;
        }
        int target = script.get(nextMoment).tick();
        while (run.tick() < target) {
            if (run.tick() == script.get(0).tick()) {
                run.throttle(1f);
            }
            if (run.tick() == detachTick) {
                run.destroyFrontLeftWheel();
            }
            run.step();
        }
        place();
    }

    /**
     * Points one instance at each thing the simulation says is in the world, and hides the rest.
     *
     * <p>Hide-everything-then-show is what makes a frame a statement about this tick rather than
     * about every tick so far: a wheel that has despawned leaves no instance behind showing where it
     * used to be.
     */
    private void place() {
        for (List<Integer> pooled : instancePool.values()) {
            for (int instance : pooled) {
                renderer.setVisible(instance, false);
            }
        }
        Map<Integer, Integer> used = new TreeMap<>();
        for (VehicleRun.Drawable drawable : run.drawables()) {
            int modelId = modelFor(drawable.partTypeId());
            if (modelId < 0) {
                continue;
            }
            int nth = used.merge(modelId, 1, Integer::sum) - 1;
            int instance = instanceOf(modelId, nth);
            renderer.setTransform(instance, drawable.worldMatrix());
            renderer.setVisible(instance, true);
        }
    }

    /** The {@code nth} instance of a model, creating it if this frame is the first to need it. */
    private int instanceOf(int modelId, int nth) {
        List<Integer> pooled = instancePool.computeIfAbsent(modelId, key -> new ArrayList<>());
        while (pooled.size() <= nth) {
            pooled.add(renderer.instantiate(modelId));
        }
        return pooled.get(nth);
    }

    private void draw() {
        aim();
        renderer.render(new Color(0.086f, 0.094f, 0.118f, 1f));
    }

    /**
     * Follows the car from ahead and to its left, which is the side the script takes a wheel off.
     *
     * <p>Framing is the difference between a capture that is evidence and one that is a picture of a
     * car: from the other side the missing corner is behind the bodywork and the frame proves
     * nothing.
     */
    private void aim() {
        Moment moment = script.get(Math.min(nextMoment, script.size() - 1));
        if (moment.closeUp()) {
            focus.set(run.wheelPosition(VehicleRun.DETACHED_SLOT_PATH));
            eye.set(focus).add(-1.05f, 0.42f, 1.15f);
        } else {
            Vector3 centre = run.vehiclePosition();
            focus.set(centre.x, 0.7f, centre.z);
            eye.set(focus).add(-6.2f, 2.5f, 8.0f);
        }
        renderer.look(eye, focus);
    }

    private void write(Moment moment) {
        if (capturePath == null) {
            return;
        }
        Path target = pathFor(moment);
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        Pixmap pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, width, height);
        try {
            java.nio.file.Files.createDirectories(target.toAbsolutePath().getParent());
            PixmapIO.writePNG(new com.badlogic.gdx.files.FileHandle(target.toFile()), pixmap, -1, true);
            captures.add(target);
            LOG.info(
                    "captured {} at tick {} ({} s): {} m/s, {} wheels, {} in contact",
                    target,
                    run.tick(),
                    String.format("%.2f", VehicleRun.seconds((int) run.tick())),
                    String.format("%.1f", run.speedMps()),
                    run.wheelCount(),
                    run.wheelsInContact());
            measurements.put(
                    "at" + moment.suffix(),
                    Map.of(
                            "tick", run.tick(),
                            "speedMps", round(run.speedMps()),
                            "wheelCount", run.wheelCount(),
                            "wheelsInContact", run.wheelsInContact()));
        } catch (java.io.IOException e) {
            LOG.error("could not write {}", target, e);
        } finally {
            pixmap.dispose();
        }
    }

    private Path pathFor(Moment moment) {
        String name = capturePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? ".png" : name.substring(dot);
        Path directory = capturePath.toAbsolutePath().getParent();
        return directory.resolve(stem + moment.suffix() + extension);
    }

    private static float round(float value) {
        return Math.round(value * 100f) / 100f;
    }

    @Override
    public void dispose() {
        if (renderer != null) {
            renderer.dispose();
        }
    }
}
