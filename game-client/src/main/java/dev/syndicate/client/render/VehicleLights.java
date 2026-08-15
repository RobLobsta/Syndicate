/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.mgsx.gltf.scene3d.attributes.PBRColorAttribute;
import net.mgsx.gltf.scene3d.lights.SpotLightEx;

/**
 * The lamps a vehicle carries, placed in the world each frame (D15-R51).
 *
 * <p>Two halves, and they are two because they solve different problems. A {@link SpotLightEx} in
 * the environment is what makes a headlight <b>light something</b> — a pool of road ahead of the
 * car, the flank of the vehicle in front. A translucent cone drawn from the lens is what makes it
 * <b>visible as a beam</b>, which no amount of surface lighting achieves: real headlights are seen
 * because the air between the lamp and the ground is full of dust, and a renderer with no
 * participating medium has to draw that shaft or the light appears from nowhere.
 *
 * <p><b>Bounded on purpose.</b> A forward shader compiles a fixed number of light slots, and eight
 * cars with two lamps apiece is sixteen — enough to cost real frame time and more than the shader is
 * configured for. So the nearest {@link #MAX_CASTING_LAMPS} to the camera cast, and the rest are
 * drawn as beams only: at the distance where a car loses its lights, its beams are what you see of
 * it anyway.
 *
 * <p>Entirely cosmetic in the sense of G6 — nothing here writes anything a simulation system reads,
 * and a lamp is extinguished by reading authoritative state, never the other way round.
 *
 * <p><b>Owner of one {@link Model}</b> (G19), disposed by {@link RenderContext}.
 */
public final class VehicleLights implements Disposable {

    /**
     * How many lamps may cast at once.
     *
     * <p>Eight is four cars' worth, and it is the number the shader is configured for in
     * {@link RenderContext}. Raising it costs a uniform array and a loop iteration in the fragment
     * shader for every pixel of every surface in the scene, whether or not a light is near it.
     */
    public static final int MAX_CASTING_LAMPS = 8;

    /** How many beam cones are drawn. Beams are cheap — one blended cone each — so this is looser. */
    public static final int MAX_BEAMS = 24;

    /**
     * Sides on the beam cone.
     *
     * <p>Twelve, because a beam is translucent and additive: its silhouette is the softest edge in
     * the frame and the facets a coarse cone would show are invisible under it.
     */
    private static final int CONE_SIDES = 12;

    /**
     * How much of the cone's length is drawn.
     *
     * <p>Short of the lamp's own range, and deliberately: the lit pool should reach further than the
     * visible shaft, because that is what a real beam looks like — the air stops scattering long
     * before the light stops arriving. A shaft drawn to full range reads as a solid object.
     */
    private static final float BEAM_LENGTH_FRACTION = 0.38f;

    /**
     * Peak opacity of a beam at full darkness.
     *
     * <p>Very low, and it has to be. A beam is a 30-metre cone seen against a dark scene and its
     * silhouette covers a large part of the frame; at 0.10 — the first value tried — eight cars
     * turned the arena into overlapping grey sheets brighter than anything they were lighting. The
     * shaft's job is to say *where the light is coming from*, and the lit ground does the rest.
     */
    private static final float BEAM_ALPHA = 0.022f;

    /** One lamp, resolved into world space for this frame. */
    private record Placed(Vector3 position, Vector3 direction, PartLamps.Lamp lamp, float distanceSq) {}

    private final List<Placed> placed = new ArrayList<>();
    private final List<SpotLightEx> pool = new ArrayList<>();
    private final Model cone;
    private final ModelInstance beam;
    private final BlendingAttribute beamBlend;
    private final ColorAttribute beamColour;

    private final Quaternion rotation = new Quaternion();
    private final Matrix4 beamTransform = new Matrix4();

    private int castingThisFrame;
    private int beamsThisFrame;

    public VehicleLights() {
        for (int i = 0; i < MAX_CASTING_LAMPS; i++) {
            pool.add(new SpotLightEx());
        }
        cone = buildCone();
        beam = new ModelInstance(cone);

        // Additive rather than alpha-blended: light adds to what is behind it and never darkens it,
        // so a beam crossing a wall brightens the wall instead of fogging it grey.
        beamBlend = new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE, BEAM_ALPHA);
        beamColour = PBRColorAttribute.createEmissive(Color.WHITE);
        Material material = beam.materials.first();
        material.set(beamBlend);
        material.set(beamColour);
        material.set(PBRColorAttribute.createBaseColorFactor(new Color(0f, 0f, 0f, 1f)));
        // Depth-tested so a beam is occluded by the car in front of it, but not depth-written, so
        // two beams crossing add rather than one of them winning.
        material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
    }

    /** Begins a frame: forget the last one's lamps. */
    public void begin() {
        placed.clear();
    }

    /**
     * Records one lit lamp, already in world space.
     *
     * @param cameraPosition used only to rank lamps for the casting budget
     */
    public void add(Vector3 position, Vector3 direction, PartLamps.Lamp lamp, Vector3 cameraPosition) {
        placed.add(
                new Placed(new Vector3(position), new Vector3(direction).nor(), lamp, position.dst2(cameraPosition)));
    }

    /**
     * Hands the nearest casting lamps to the environment and drops the rest.
     *
     * <p>Called once per frame, after every lamp has been added and before anything is drawn. Every
     * light is removed and re-added rather than mutated in place because a lamp that was destroyed
     * this frame must stop lighting, and the cheapest correct way to express that is to rebuild the
     * set.
     */
    public void commit(RenderEnvironment environment, float nightFraction) {
        environment.clearSpotLights();
        castingThisFrame = 0;
        if (nightFraction <= 0f) {
            return;
        }
        placed.sort(Comparator.comparingDouble(Placed::distanceSq));
        for (Placed entry : placed) {
            if (!entry.lamp().castsBeam() || castingThisFrame >= MAX_CASTING_LAMPS) {
                continue;
            }
            SpotLightEx light = pool.get(castingThisFrame++);
            light.setDeg(
                    entry.lamp().colour(),
                    entry.position(),
                    entry.direction(),
                    // A headlight is invisible at noon and the only thing you can see at midnight,
                    // so its intensity follows the darkness rather than being constant.
                    entry.lamp().intensity() * nightFraction,
                    entry.lamp().coneOuterDeg() * 0.5f,
                    entry.lamp().coneInnerDeg() * 0.5f,
                    entry.lamp().rangeM());
            environment.addSpotLight(light);
        }
    }

    /**
     * Draws the visible shafts, into a batch that is already begun.
     *
     * <p>Rendered inside the scene's own {@code begin}/{@code end} so that {@code ModelBatch}'s
     * sorter puts every blended cone after every opaque surface, which is the order additive
     * blending needs and the only order in which a beam looks like light rather than like a cone.
     */
    public void renderBeams(ModelBatch batch, RenderEnvironment environment, float nightFraction) {
        beamsThisFrame = 0;
        if (nightFraction <= 0f) {
            return;
        }
        beamBlend.opacity = BEAM_ALPHA * nightFraction;
        for (Placed entry : placed) {
            if (!entry.lamp().castsBeam() || beamsThisFrame >= MAX_BEAMS) {
                continue;
            }
            float length = entry.lamp().rangeM() * BEAM_LENGTH_FRACTION;
            // The **inner** cone, not the outer one. The outer angle is where the illumination has
            // fallen to nothing; the visible shaft is the bright core inside it, and drawing it at
            // the outer angle makes a 34-degree wedge where a real dipped beam is a narrow blade.
            float radius = length * (float) Math.tan(entry.lamp().coneInnerDeg() * 0.5f * MathUtils.degreesToRadians);
            rotation.setFromCross(Vector3.Z, entry.direction());
            beamTransform.idt().translate(entry.position()).rotate(rotation).scale(radius, radius, length);
            beam.transform.set(beamTransform);
            beamColour.color.set(entry.lamp().colour());
            batch.render(beam, environment.environment());
            beamsThisFrame++;
        }
    }

    /** How many lamps lit the world last frame, for a capture's log line. */
    public int castingThisFrame() {
        return castingThisFrame;
    }

    /** How many beams were drawn last frame. */
    public int beamsThisFrame() {
        return beamsThisFrame;
    }

    /**
     * A unit cone: apex at the origin, opening along {@code +Z}, one metre long and one across.
     *
     * <p>Built along {@code +Z} rather than libGDX's {@code +Y} so that a lamp's own direction — a
     * vector in the same frame the vehicle's forward axis is in — needs one rotation to place it
     * instead of a rotation and a correction nobody can read six months later.
     *
     * <p>The apex is at the origin so that scaling the instance scales the beam's <em>length and
     * spread</em> about the lens rather than about the middle of the shaft.
     */
    private static Model buildCone() {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder part = builder.part(
                "beam",
                GL20.GL_TRIANGLES,
                VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal,
                new Material());
        Vector3 apex = new Vector3(0f, 0f, 0f);
        Vector3 first = new Vector3();
        Vector3 previous = new Vector3();
        for (int i = 0; i <= CONE_SIDES; i++) {
            float angle = MathUtils.PI2 * i / CONE_SIDES;
            Vector3 rim = new Vector3(MathUtils.cos(angle), MathUtils.sin(angle), 1f);
            if (i == 0) {
                first.set(rim);
                previous.set(rim);
                continue;
            }
            // Wound so the outward normal faces away from the axis; the beam is drawn from both
            // sides in practice, but a consistent winding keeps the normal meaningful if it is ever
            // lit rather than emissive.
            part.triangle(vertex(apex), vertex(previous), vertex(rim));
            previous.set(rim);
        }
        part.triangle(vertex(apex), vertex(previous), vertex(first));
        return builder.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 position) {
        MeshPartBuilder.VertexInfo info = new MeshPartBuilder.VertexInfo();
        info.setPos(position);
        info.setNor(new Vector3(position).nor());
        return info;
    }

    @Override
    public void dispose() {
        cone.dispose();
    }
}
