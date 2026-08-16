/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.shell;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.client.render.PartModels;
import dev.syndicate.client.render.RenderEnvironment;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.core.vehicle.VehicleFactory;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The selected vehicle, turning on a plinth (docs/08_asset_pipeline.md#D08-S4.2).
 *
 * <p>The point of a garage is that you look at the thing before you take it out, and a list of
 * names with a mass column does not tell you that the Stampede is a brick with a wing on it. This
 * draws the actual shipped art — the same {@code mesh.glb} the match draws — from the same slot
 * transforms the physics builds its compound from, so what is previewed is what spawns.
 *
 * <p><b>Assembled from the chassis' slot table, not from a simulation.</b> There is no world here,
 * no Bullet, and no entity: each part is placed at its slot's {@code localTransform} on the chassis
 * (D08-R6), which is exactly the placement {@code VehicleCompound} uses for collision. A preview
 * that ran the real spawn path would need a physics world and a tick to settle, for a picture.
 *
 * <p>The camera frames whatever it is given rather than sitting at a fixed distance, because the
 * roster is meant to grow and a distance that flatters a supercar buries a truck.
 *
 * <p><b>Owner of a model batch, a shader provider, the part meshes and the lighting</b> (G19).
 */
public final class GaragePreview implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(GaragePreview.class);

    /** Degrees per second the vehicle turns. Slow enough to read, fast enough to not look frozen. */
    public static final float SPIN_DEG_PER_SEC = 16f;

    /** Degrees above the horizon the camera sits. A three-quarter view, slightly high. */
    public static final float CAMERA_PITCH_DEG = 14f;

    /** Multiplier on the framing distance, so the vehicle does not touch the viewport edges. */
    public static final float FRAMING_MARGIN = 1.14f;

    private final PartModels partModels;
    private final RenderEnvironment environment = new RenderEnvironment();
    private final ModelBatch batch = new ModelBatch(PBRShaderProvider.createDefault(0));
    private final PerspectiveCamera camera = new PerspectiveCamera(38f, 1f, 1f);
    private final AssetIndex assets;

    private final List<Placed> placed = new ArrayList<>();
    private final Vector3 centre = new Vector3();
    private final Vector3 dimensions = new Vector3();

    private AssetId builtFor;
    private float spinDeg;
    private float radiusM = 3f;

    /** One drawable part and where it sits on the chassis. */
    private record Placed(ModelInstance instance, Matrix4 local) {}

    public GaragePreview(java.nio.file.Path assetRoot, AssetIndex assets) {
        this.partModels = new PartModels(assetRoot);
        this.assets = assets;
        camera.near = 0.1f;
        camera.far = 200f;
    }

    /**
     * Draws the vehicle into a viewport given in real back-buffer pixels.
     *
     * <p>A scissor rather than a full-screen draw: the preview occupies the right half of the
     * garage and the panels are drawn over the rest, and without clipping the car's wheels appear
     * behind the vehicle list.
     */
    public void render(AssetId assemblyId, float frameDeltaSeconds, int x, int y, int width, int height) {
        if (assemblyId == null || width <= 0 || height <= 0) {
            return;
        }
        if (!assemblyId.equals(builtFor)) {
            build(assemblyId);
        }
        if (placed.isEmpty()) {
            return;
        }

        spinDeg = (spinDeg + SPIN_DEG_PER_SEC * frameDeltaSeconds) % 360f;
        frame(width, height);

        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(x, y, width, height);
        Gdx.gl.glViewport(x, y, width, height);
        Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        batch.begin(camera);
        for (Placed part : placed) {
            // Spin applied to the whole vehicle, so the wheels turn with the body rather than
            // orbiting it: the local transform goes inside the rotation, not beside it.
            part.instance().transform.setToRotation(Vector3.Y, spinDeg).mul(part.local());
            batch.render(part.instance(), environment.environment());
        }
        batch.end();

        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    /**
     * Points the camera at the vehicle's centre from far enough away to see all of it.
     *
     * <p>Fits <b>both</b> axes and takes whichever is tighter. Framing on the bounding sphere alone
     * — the obvious version — pushes the camera back far enough to fit a 4.5 m car's <i>length</i>
     * vertically, and in the garage's wide, short viewport that leaves the car occupying a sixth of
     * the height with empty screen above and below it.
     *
     * <p>The horizontal extent is the footprint's radius rather than its width, because the vehicle
     * is turning: the framing has to hold at the angle where it presents its diagonal.
     */
    private void frame(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        float aspect = width / (float) height;

        float halfFovY = (float) Math.toRadians(camera.fieldOfView * 0.5);
        float halfFovX = (float) Math.atan(Math.tan(halfFovY) * aspect);

        float footprintRadius = 0.5f * (float) Math.hypot(dimensions.x, dimensions.z);
        float halfHeight = 0.5f * dimensions.y;

        // sin, not tan: the extent being fitted is the radius of a body that sticks out towards the
        // camera as well as across it, and tan solves for a flat card at the centre — which frames a
        // car short enough that its nose leaves the viewport as it turns.
        float forVertical = halfHeight / (float) Math.sin(halfFovY);
        float forHorizontal = footprintRadius / (float) Math.sin(halfFovX);
        float distance = Math.max(forVertical, forHorizontal) * FRAMING_MARGIN;

        float pitch = (float) Math.toRadians(CAMERA_PITCH_DEG);
        camera.position.set(0f, centre.y + distance * (float) Math.sin(pitch), distance * (float) Math.cos(pitch));
        camera.lookAt(0f, centre.y, 0f);
        camera.up.set(Vector3.Y);
        camera.update();
    }

    /** Builds the instances for one assembly, replacing whatever was there. */
    private void build(AssetId assemblyId) {
        placed.clear();
        builtFor = assemblyId;

        AssemblyDef assembly = assets.assembly(assemblyId);
        if (assembly == null) {
            LOG.warn("no assembly {} to preview", assemblyId.value());
            return;
        }

        PartType chassis = assets.partType(assembly.chassisPartTypeId());
        ModelInstance chassisInstance = partModels.instanceOf(assembly.chassisPartTypeId());
        if (chassisInstance != null) {
            placed.add(new Placed(chassisInstance, new Matrix4()));
        }

        int wheelCount = countWheels(assembly, assets);
        if (chassis == null) {
            measure();
            return;
        }

        // The slot chain, walked. This used to place only the parts bolted straight to the chassis,
        // on the grounds that nothing shipped had a sub-slot; a modular weapon is an assembly of its
        // own (D17-S5.8), so a machine gun's barrel hangs three deep and simply did not appear.
        //
        // Ascending slot-path order is topological (D08-R11), so one forward pass suffices: a
        // parent's transform is always resolved before any child asks for it.
        Map<String, Matrix4> transformByPath = new HashMap<>();
        Map<String, PartType> typeByPath = new HashMap<>();
        transformByPath.put(SlotChain.ROOT_SLOT_PATH, new Matrix4());
        typeByPath.put(SlotChain.ROOT_SLOT_PATH, chassis);

        List<AssemblyDef.PartPlacement> ordered = new ArrayList<>(assembly.parts());
        ordered.sort(Comparator.comparing(AssemblyDef.PartPlacement::slotPath));

        for (AssemblyDef.PartPlacement placement : ordered) {
            Matrix4 parentTransform = transformByPath.get(placement.parentSlotPath());
            PartType parentType = typeByPath.get(placement.parentSlotPath());
            if (parentTransform == null || parentType == null) {
                continue;
            }
            SlotDefinition slot = parentType.slots().get(placement.parentSlotId());
            PartType part = assets.partType(placement.partTypeId());
            if (slot == null || part == null) {
                continue;
            }
            Matrix4 local = new Matrix4();
            slot.localTransform().toMatrix(local);
            local.translate(0f, -suspensionDrop(part, wheelCount), 0f);

            Matrix4 world = new Matrix4(parentTransform).mul(local);
            transformByPath.put(placement.slotPath(), world);
            typeByPath.put(placement.slotPath(), part);

            ModelInstance instance = partModels.instanceOf(placement.partTypeId());
            if (instance != null) {
                placed.add(new Placed(instance, world));
            }
        }

        measure();
        LOG.info("garage preview: {} drew {} parts, radius {} m", assemblyId.value(), placed.size(), radiusM);
    }

    /**
     * How far below its slot a part hangs at rest, in metres. Zero for anything but a wheel.
     *
     * <p>A wheel's slot is the <b>suspension connection point</b>, not the axle: Bullet hangs the
     * wheel a rest length below the point {@code addWheel} is given, and the vehicle then settles
     * one static sag back up (D15-R45b). This screen has no Bullet world to do that in, so it does
     * the arithmetic itself — and without it every wheel is drawn a fifth of a metre high, tucked
     * up inside its own arch, and the car looks like it is sitting on its floor pan.
     *
     * <p>The two figures come from the same places the physics reads them: the rest length from the
     * part's handling block, the stiffness from its {@code SUSPENSION_STIFFNESS} stat.
     */
    private static float suspensionDrop(PartType part, int wheelCount) {
        if (part == null || part.category() != PartCategory.WHEEL) {
            return 0f;
        }
        float stiffness =
                part.stats().resolve(StatBlock.Stat.SUSPENSION_STIFFNESS, VehicleFactory.WHEEL_SUSPENSION_STIFFNESS);
        return part.handling().suspensionRestLengthM() - VehicleFactory.staticSagM(stiffness, wheelCount);
    }

    /** How many wheels this assembly stands on, which is what fixes the sag per corner. */
    private static int countWheels(AssemblyDef assembly, AssetIndex assets) {
        int wheels = 0;
        for (AssemblyDef.PartPlacement placement : assembly.parts()) {
            PartType part = assets.partType(placement.partTypeId());
            if (part != null && part.category() == PartCategory.WHEEL) {
                wheels++;
            }
        }
        return wheels;
    }

    /** The bounding sphere of everything placed, which is what the camera frames. */
    private void measure() {
        if (placed.isEmpty()) {
            radiusM = 3f;
            centre.set(0f, 0.7f, 0f);
            return;
        }
        BoundingBox total = new BoundingBox();
        BoundingBox one = new BoundingBox();
        for (Placed part : placed) {
            // calculateBoundingBox reports model space and ignores instance.transform, so the slot
            // placement has to be applied here rather than assumed to be baked in.
            part.instance().calculateBoundingBox(one).mul(part.local());
            total.ext(one);
        }
        total.getCenter(centre);
        // Half the diagonal rather than half the longest edge: the vehicle turns, so the framing
        // has to hold at the angle where it presents its diagonal to the camera.
        radiusM = Math.max(1f, total.getDimensions(dimensions).len() * 0.5f);
    }

    @Override
    public void dispose() {
        batch.dispose();
        partModels.dispose();
        environment.dispose();
    }
}
