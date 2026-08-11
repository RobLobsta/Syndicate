/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.client.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.client.component.ParticleRefComponent;

/**
 * Draws effect bursts as camera-facing quads (docs/04_entity_component_model.md#D04-S4.4 row 24).
 *
 * <p>One dynamic mesh, rebuilt from scratch each frame and drawn in a single call. A particle system
 * with per-emitter meshes or per-particle draw calls would cost more to write and more to run for a
 * scene whose entire particle budget is a few hundred quads.
 *
 * <p>Additive, depth-tested but not depth-written: sparks brighten what is behind them and are
 * occluded by the car they came off, which is the pair of properties that makes a burst read as
 * light rather than as paper. Smoke uses the same path deliberately — a separately sorted alpha pass
 * is a real renderer's problem, and this one has four kinds of particle.
 *
 * <p><b>Owner of one {@link Mesh} and one {@link ShaderProgram}</b> (G19).
 */
public final class ParticleRenderer implements Disposable {

    /** How many quads the buffer holds. Bursts past this in a frame are not drawn. */
    public static final int MAX_QUADS = ParticleRefComponent.MAX_PARTICLES * 96;

    private static final int FLOATS_PER_VERTEX = 3 + 4;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;

    private static final String VERTEX_SHADER =
            """
            attribute vec3 a_position;
            attribute vec4 a_color;
            uniform mat4 u_projView;
            varying vec4 v_color;
            void main() {
                v_color = a_color;
                gl_Position = u_projView * vec4(a_position, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER =
            """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            void main() {
                gl_FragColor = v_color;
            }
            """;

    private final ShaderProgram shader;
    private final Mesh mesh;
    private final float[] vertices = new float[MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];

    private final Vector3 right = new Vector3();
    private final Vector3 up = new Vector3();
    private final Vector3 centre = new Vector3();

    private int quadCount;
    private int peakQuadCount;

    public ParticleRenderer() {
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            throw new IllegalStateException("particle shader failed to compile: " + shader.getLog());
        }
        mesh = new Mesh(
                false,
                MAX_QUADS * VERTICES_PER_QUAD,
                MAX_QUADS * INDICES_PER_QUAD,
                new VertexAttribute(Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
                new VertexAttribute(Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE));
        mesh.setIndices(buildIndices());
    }

    /** Starts a frame. Call once, before any {@link #add}. */
    public void begin(Camera camera) {
        quadCount = 0;
        // The quad basis comes from the camera, not from the particle: a billboard that faces the
        // camera position rather than its plane skews as it approaches the edge of the frame.
        right.set(camera.direction).crs(camera.up).nor();
        up.set(right).crs(camera.direction).nor();
    }

    /**
     * Adds one burst.
     *
     * @param originX world position of the burst's entity
     * @param fade brightness in {@code [0,1]}, from the burst's age
     */
    public void add(ParticleRefComponent particles, float originX, float originY, float originZ, float fade) {
        if (fade <= 0f) {
            return;
        }
        float r = particles.colour.r;
        float g = particles.colour.g;
        float b = particles.colour.b;
        for (int p = 0; p < particles.count && quadCount < MAX_QUADS; p++) {
            centre.set(originX + particles.offsetX[p], originY + particles.offsetY[p], originZ + particles.offsetZ[p]);
            float half = particles.sizeM[p];
            int base = quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
            corner(base, -half, -half, r, g, b, fade);
            corner(base + FLOATS_PER_VERTEX, half, -half, r, g, b, fade);
            corner(base + FLOATS_PER_VERTEX * 2, half, half, r, g, b, fade);
            corner(base + FLOATS_PER_VERTEX * 3, -half, half, r, g, b, fade);
            quadCount++;
        }
    }

    /** Draws everything added since {@link #begin}. */
    public void flush(Camera camera) {
        peakQuadCount = Math.max(peakQuadCount, quadCount);
        if (quadCount == 0) {
            return;
        }
        mesh.setVertices(vertices, 0, quadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        shader.bind();
        shader.setUniformMatrix("u_projView", camera.combined);
        mesh.render(shader, GL20.GL_TRIANGLES, 0, quadCount * INDICES_PER_QUAD);

        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Quads drawn in the last frame, for a capture's log line. */
    public int quadCount() {
        return quadCount;
    }

    /**
     * The most quads any frame has drawn since the process started.
     *
     * <p>A single capture is one instant, and a spark burst lasts under half a second — so "0 quads
     * in the captured frame" says nothing about whether effects work. This does: it is the evidence
     * that something was drawn at some point during a run.
     */
    public int peakQuadCount() {
        return peakQuadCount;
    }

    private void corner(int offset, float dx, float dy, float r, float g, float b, float alpha) {
        vertices[offset] = centre.x + right.x * dx + up.x * dy;
        vertices[offset + 1] = centre.y + right.y * dx + up.y * dy;
        vertices[offset + 2] = centre.z + right.z * dx + up.z * dy;
        vertices[offset + 3] = r;
        vertices[offset + 4] = g;
        vertices[offset + 5] = b;
        vertices[offset + 6] = alpha;
    }

    private static short[] buildIndices() {
        short[] indices = new short[MAX_QUADS * INDICES_PER_QUAD];
        short vertex = 0;
        for (int i = 0; i < indices.length; i += INDICES_PER_QUAD) {
            indices[i] = vertex;
            indices[i + 1] = (short) (vertex + 1);
            indices[i + 2] = (short) (vertex + 2);
            indices[i + 3] = (short) (vertex + 2);
            indices[i + 4] = (short) (vertex + 3);
            indices[i + 5] = vertex;
            vertex += VERTICES_PER_QUAD;
        }
        return indices;
    }

    @Override
    public void dispose() {
        mesh.dispose();
        shader.dispose();
    }
}
