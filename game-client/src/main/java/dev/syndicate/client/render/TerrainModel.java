/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ShortArray;
import dev.syndicate.core.arena.Surface;
import dev.syndicate.core.arena.TerrainField;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.mgsx.gltf.scene3d.attributes.PBRColorAttribute;
import net.mgsx.gltf.scene3d.attributes.PBRFloatAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The generated ground, drawn from the same height field the physics collides with
 * (docs/16_procedural_arena_generation.md#D16-S6).
 *
 * <p>The failure this class exists to prevent is the one that appeared the moment the scrapyard got
 * terrain: a car climbing a nine-metre spoil heap that nothing draws. Ground you can hit and cannot
 * see is worse than no ground at all, because it reads as the physics being broken.
 *
 * <p><b>One mesh, decimated by a stride, coloured per vertex.</b> That is the whole of it, and it is
 * deliberately not D16-S6: there is no chunking, no frustum culling, no level of detail and no
 * texturing. Those are what make a 600 m arena run at a frame rate, and they are a session's work
 * with a real GPU to measure against. This makes the ground <em>visible and correct</em> — the
 * surface under a wheel is the colour the classifier assigned it — so the terrain can be looked at,
 * driven on and judged before that work starts.
 *
 * <p>Colour comes from {@link Surface}, so what you see is what you grip: sand reads warm, gravel
 * grey, rock dark, tarmac darker still. When D16-S6's texture generator lands it replaces this and
 * nothing else changes, because the classifier is already the authority.
 *
 * <p><b>Owner of one {@link Model}</b> (G19), disposed by {@link RenderContext}.
 */
public final class TerrainModel implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(TerrainModel.class);

    /**
     * The most vertices this will build before it starts skipping samples.
     *
     * <p>A 601-sample desert is 361,201 vertices and 720,000 triangles in one mesh, which is past
     * what a single un-chunked draw should be asked to do and past the 65,536-index limit of a short
     * index buffer besides. Decimating to a stride keeps one draw call and a shape that is still
     * recognisably the ground; the alternative is chunking, which is stage 2 proper.
     */
    public static final int MAX_VERTICES = 60_000;

    /** Lifts the drawn surface slightly, so the ground does not z-fight the collision at grazing angles. */
    public static final float LIFT_M = 0.02f;

    /**
     * How much brighter {@link RenderEnvironment} renders a plain colour than the colour itself.
     *
     * <p>Measured, not derived: a base colour of 0.19 comes out of that environment at roughly 0.75.
     * One sun at intensity 3.2, an ambient term of 0.55 and an outdoor image-based light all add,
     * and the shipped cars look right through it only because their glTF materials were authored
     * against real photographs and are dark to begin with.
     *
     * <p>So {@link #colourOf} states the colour the ground should <em>appear</em>, and this divides
     * it on the way into the material. The alternative — writing 0.05 in the table and remembering
     * why — is how a surface palette becomes unreadable. Retune this if the environment changes;
     * the way to check is a capture, because the number came from one.
     */
    public static final float ENVIRONMENT_GAIN = 4.0f;

    private final Model model;
    private final ModelInstance instance;
    private final int stride;

    public TerrainModel(TerrainField field) {
        int grid = field.params().gridSize();
        this.stride = strideFor(grid);
        int samples = (grid - 1) / stride + 1;

        // Position and normal only. Per-vertex colour was the obvious way to carry the surface
        // classification and it does not work here: gdx-gltf's PBR shader takes its base colour from
        // the material unless the model declares a glTF COLOR_0 channel, so a mesh with a colour
        // attribute and a white base renders entirely white — which is exactly how the first
        // scrapyard capture came out looking like a snowfield.
        VertexAttribute[] attributes = {
            new VertexAttribute(Usage.Position, 3, "a_position"), new VertexAttribute(Usage.Normal, 3, "a_normal"),
        };
        int floatsPerVertex = 6;

        float[] vertices = new float[samples * samples * floatsPerVertex];
        int v = 0;
        Vector3 normal = new Vector3();
        for (int j = 0; j < samples; j++) {
            for (int i = 0; i < samples; i++) {
                int si = Math.min(i * stride, grid - 1);
                int sj = Math.min(j * stride, grid - 1);

                vertices[v++] = field.sampleX(si);
                // Heights are stored relative to the datum, so the world Y is the datum plus one.
                vertices[v++] = field.groundY() + field.heightAtSample(si, sj) + LIFT_M;
                vertices[v++] = field.sampleZ(sj);

                normalAt(field, si, sj, grid, normal);
                vertices[v++] = normal.x;
                vertices[v++] = normal.y;
                vertices[v++] = normal.z;
            }
        }

        // One index run per surface, laid end to end in one buffer, so each becomes a MeshPart with
        // its own material. Four draw calls instead of one, in exchange for the ground actually
        // reading as sand, gravel, rock and tarmac — which is the only reason to draw it at all
        // before the texture generator exists.
        Map<Surface, ShortArray> runs = new EnumMap<>(Surface.class);
        for (int j = 0; j < samples - 1; j++) {
            for (int i = 0; i < samples - 1; i++) {
                short a = (short) (j * samples + i);
                short b = (short) (j * samples + i + 1);
                short c = (short) ((j + 1) * samples + i);
                short d = (short) ((j + 1) * samples + i + 1);

                // The quad takes the surface of its own corner, so a boundary lands on a cell edge
                // rather than being averaged into a colour neither surface has.
                Surface surface = field.surfaceAtSample(Math.min(i * stride, grid - 1), Math.min(j * stride, grid - 1));
                ShortArray run = runs.computeIfAbsent(surface, key -> new ShortArray());
                // Counter-clockwise seen from above, so the ground faces the sky and is not culled.
                run.add(a);
                run.add(c);
                run.add(b);
                run.add(b);
                run.add(c);
                run.add(d);
            }
        }

        int totalIndices = runs.values().stream().mapToInt(run -> run.size).sum();
        short[] indices = new short[totalIndices];
        int offset = 0;
        Map<Surface, int[]> ranges = new EnumMap<>(Surface.class);
        for (Map.Entry<Surface, ShortArray> entry : runs.entrySet()) {
            ShortArray run = entry.getValue();
            System.arraycopy(run.items, 0, indices, offset, run.size);
            ranges.put(entry.getKey(), new int[] {offset, run.size});
            offset += run.size;
        }

        Mesh mesh = new Mesh(true, vertices.length / floatsPerVertex, indices.length, attributes);
        mesh.setVertices(vertices);
        mesh.setIndices(indices);

        // Assembled by hand rather than through ModelBuilder: the builder's shape helpers cannot
        // emit an index run per surface, which is what carries the classification here.
        model = new Model();
        Node node = new Node();
        node.id = "terrain";
        for (Map.Entry<Surface, int[]> entry : ranges.entrySet()) {
            Surface surface = entry.getKey();
            int[] range = entry.getValue();
            MeshPart part = new MeshPart(
                    "terrain_" + surface.name().toLowerCase(Locale.ROOT), mesh, range[0], range[1], GL20.GL_TRIANGLES);
            Material material = new Material(
                    PBRColorAttribute.createBaseColorFactor(exposed(colourOf(surface))),
                    PBRFloatAttribute.createRoughness(roughnessOf(surface)),
                    PBRFloatAttribute.createMetallic(0f));
            node.parts.add(new NodePart(part, material));
            model.meshParts.add(part);
            model.materials.add(material);
        }
        model.nodes.add(node);
        model.meshes.add(mesh);
        model.manageDisposable(mesh);
        instance = new ModelInstance(model);

        LOG.info(
                "terrain drawn: {}x{} samples at stride {} ({} triangles over {} surfaces), relief {}..{} m",
                samples,
                samples,
                stride,
                indices.length / 3,
                ranges.size(),
                String.format(Locale.ROOT, "%.1f", field.minHeight()),
                String.format(Locale.ROOT, "%.1f", field.maxHeight()));
    }

    /** The drawable ground. One instance exists; it never moves. */
    public ModelInstance instance() {
        return instance;
    }

    /** How many samples this skipped, so a capture can report what it actually drew. */
    public int stride() {
        return stride;
    }

    /** The smallest stride whose mesh fits inside {@link #MAX_VERTICES} and a short index buffer. */
    private static int strideFor(int grid) {
        for (int stride = 1; stride < grid; stride++) {
            int samples = (grid - 1) / stride + 1;
            if (samples * samples <= MAX_VERTICES) {
                return stride;
            }
        }
        return grid - 1;
    }

    /**
     * The surface normal at a sample, from its neighbours' heights.
     *
     * <p>Central differences, clamped at the edges. Taken from the field rather than from the
     * decimated mesh so that shading follows the ground the car is actually on, not the coarser
     * shape being drawn — a heap's flank stays lit as a flank even where the stride skipped over it.
     */
    private static void normalAt(TerrainField field, int i, int j, int grid, Vector3 out) {
        int left = Math.max(0, i - 1);
        int right = Math.min(grid - 1, i + 1);
        int down = Math.max(0, j - 1);
        int up = Math.min(grid - 1, j + 1);
        float cell = field.params().cellSizeM();
        float dx = (field.heightAtSample(right, j) - field.heightAtSample(left, j)) / ((right - left) * cell);
        float dz = (field.heightAtSample(i, up) - field.heightAtSample(i, down)) / ((up - down) * cell);
        out.set(-dx, 1f, -dz).nor();
    }

    /**
     * What a surface looks like.
     *
     * <p>These are the colours as <b>seen</b>, not the base colours written into the material —
     * {@link #ENVIRONMENT_GAIN} converts between them. Chosen so the four are distinguishable at a
     * glance from a chase camera, because the first job of this colouring is to let a person check
     * that the classifier put sand where sand should be. Photographic accuracy is D16-S6's texture
     * generator's problem.
     */
    private static Color colourOf(Surface surface) {
        return switch (surface) {
            case SAND -> new Color(0.76f, 0.63f, 0.41f, 1f);
            case GRAVEL -> new Color(0.42f, 0.40f, 0.37f, 1f);
            case ROCK -> new Color(0.29f, 0.26f, 0.24f, 1f);
            case TARMAC -> new Color(0.19f, 0.19f, 0.20f, 1f);
        };
    }

    /** Divides a colour by {@link #ENVIRONMENT_GAIN}, so what the table says is what is seen. */
    private static Color exposed(Color intended) {
        return new Color(
                intended.r / ENVIRONMENT_GAIN, intended.g / ENVIRONMENT_GAIN, intended.b / ENVIRONMENT_GAIN, 1f);
    }

    /**
     * How rough a surface is to the light.
     *
     * <p>Small, and worth having: loose material scatters almost perfectly while an old tarmac slab
     * keeps a little sheen, and that difference is most of what tells the eye a yard's floor apart
     * from its heaps at a distance where the colours are similar.
     */
    private static float roughnessOf(Surface surface) {
        return switch (surface) {
            case SAND, GRAVEL -> 0.98f;
            case ROCK -> 0.92f;
            case TARMAC -> 0.80f;
        };
    }

    @Override
    public void dispose() {
        model.dispose();
    }
}
