/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.verify.asset.MeshData;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the harness's scene: a ground grid and one flat-shaded mesh per body
 * (docs/14_test_environment.md#D14-S5.11).
 *
 * <p>A hand-written shader and hand-built meshes rather than {@code ModelBatch} and gdx-gltf. The
 * harness needs three things a full renderer does not help with: it must run on software GL in CI,
 * it must colour each shard distinctly (the {@code shardcolor} overlay of D14-S5.11), and it must
 * draw geometry that came from the physics loader rather than a second import path — because the
 * whole point of a capture is to show what the *simulation* is doing, and a render fed by a
 * different loader could disagree with it.
 *
 * <p>Normals are computed per-face at build time and duplicated per vertex, which gives the flat
 * faceting that makes individual shards readable. Smooth normals would blur a fractured object into
 * one lumpy mass.
 */
public final class SceneRenderer implements Disposable {

    private static final String VERTEX_SHADER =
            """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            uniform mat4 u_projView;
            uniform mat4 u_model;
            varying vec3 v_normal;
            varying vec3 v_world;
            void main() {
                vec4 world = u_model * vec4(a_position, 1.0);
                v_world = world.xyz;
                v_normal = normalize((u_model * vec4(a_normal, 0.0)).xyz);
                gl_Position = u_projView * world;
            }
            """;

    private static final String FRAGMENT_SHADER =
            """
            #ifdef GL_ES
            precision mediump float;
            #endif
            uniform vec4 u_color;
            uniform vec3 u_lightDir;
            varying vec3 v_normal;
            varying vec3 v_world;
            void main() {
                vec3 n = normalize(v_normal);
                float key = max(dot(n, -normalize(u_lightDir)), 0.0);
                // A dim fill from below keeps unlit faces readable instead of solid black, which
                // matters when the subject is a cloud of shards seen from one side.
                float fill = max(dot(n, vec3(0.0, 1.0, 0.0)), 0.0) * 0.25;
                float ambient = 0.34;
                vec3 lit = u_color.rgb * (ambient + key * 0.95 + fill);
                gl_FragColor = vec4(lit, u_color.a);
            }
            """;

    private final ShaderProgram shader;
    private final PerspectiveCamera camera;
    private final Vector3 lightDirection = new Vector3(-0.55f, -0.75f, -0.35f).nor();
    private final List<Mesh> owned = new ArrayList<>();
    private final Matrix4 identity = new Matrix4();

    public SceneRenderer(int width, int height) {
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            throw new IllegalStateException("scene shader failed to compile: " + shader.getLog());
        }
        camera = new PerspectiveCamera(52f, width, height);
        camera.near = 0.05f;
        camera.far = 200f;
    }

    /** Points the camera at a target from a given offset. */
    public void look(Vector3 eye, Vector3 target) {
        camera.position.set(eye);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(target);
        camera.update();
    }

    public PerspectiveCamera camera() {
        return camera;
    }

    /**
     * Builds a renderable mesh from harness geometry.
     *
     * <p>Vertices are expanded per triangle so each face carries its own normal — 3x the vertices,
     * which is irrelevant at these counts and is what makes a shard read as a faceted solid.
     */
    public Mesh build(MeshData data) {
        int triangles = data.triangleCount();
        float[] vertices = new float[triangles * 3 * 6];
        Vector3 a = new Vector3();
        Vector3 b = new Vector3();
        Vector3 c = new Vector3();
        Vector3 normal = new Vector3();
        Vector3 edge = new Vector3();

        int cursor = 0;
        for (int t = 0; t < triangles; t++) {
            data.vertex(data.indices()[t * 3], a);
            data.vertex(data.indices()[t * 3 + 1], b);
            data.vertex(data.indices()[t * 3 + 2], c);
            normal.set(b).sub(a).crs(edge.set(c).sub(a)).nor();
            for (Vector3 vertex : new Vector3[] {a, b, c}) {
                vertices[cursor++] = vertex.x;
                vertices[cursor++] = vertex.y;
                vertices[cursor++] = vertex.z;
                vertices[cursor++] = normal.x;
                vertices[cursor++] = normal.y;
                vertices[cursor++] = normal.z;
            }
        }

        Mesh mesh = new Mesh(
                true,
                triangles * 3,
                0,
                new VertexAttribute(Usage.Position, 3, "a_position"),
                new VertexAttribute(Usage.Normal, 3, "a_normal"));
        mesh.setVertices(vertices);
        owned.add(mesh);
        return mesh;
    }

    /** Builds the ground grid as a set of lines. */
    public Mesh buildGrid(float halfExtent, float spacing) {
        List<Float> vertices = new ArrayList<>();
        for (float x = -halfExtent; x <= halfExtent + 1e-4f; x += spacing) {
            addLine(vertices, x, -halfExtent, x, halfExtent);
        }
        for (float z = -halfExtent; z <= halfExtent + 1e-4f; z += spacing) {
            addLine(vertices, -halfExtent, z, halfExtent, z);
        }
        float[] array = new float[vertices.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = vertices.get(i);
        }
        Mesh mesh = new Mesh(
                true,
                array.length / 6,
                0,
                new VertexAttribute(Usage.Position, 3, "a_position"),
                new VertexAttribute(Usage.Normal, 3, "a_normal"));
        mesh.setVertices(array);
        owned.add(mesh);
        return mesh;
    }

    /** Clears to the background colour and prepares depth state. */
    public void begin(Color background) {
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(background.r, background.g, background.b, background.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        // Back-face culling off: shard interiors are genuinely visible mid-explosion, and culling
        // them would leave holes exactly where the fracture is most interesting to look at.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        shader.bind();
        shader.setUniformMatrix("u_projView", camera.combined);
        shader.setUniformf("u_lightDir", lightDirection);
    }

    /** Draws a mesh with a model transform and a flat colour. */
    public void draw(Mesh mesh, Matrix4 transform, Color color) {
        shader.setUniformMatrix("u_model", transform);
        shader.setUniformf("u_color", color);
        mesh.render(shader, GL20.GL_TRIANGLES);
    }

    /** Draws a line mesh (the grid) at the origin. */
    public void drawLines(Mesh mesh, Color color) {
        shader.setUniformMatrix("u_model", identity);
        shader.setUniformf("u_color", color);
        mesh.render(shader, GL20.GL_LINES);
    }

    /**
     * A distinct hue per shard, spaced by the golden angle (D14-S5.11 {@code shardcolor}).
     *
     * <p>137.507 degrees is the golden angle: successive indices land far apart on the hue circle
     * for any count, so neighbouring shards never share a colour regardless of how many there are.
     * A fixed palette would repeat once the shard count passed its size, exactly when telling
     * shards apart matters most.
     */
    public static Color shardColor(int index) {
        float hue = (index * 137.507f) % 360f;
        return hsv(hue, 0.62f, 0.95f);
    }

    private static Color hsv(float hue, float saturation, float value) {
        float c = value * saturation;
        float x = c * (1 - Math.abs((hue / 60f) % 2 - 1));
        float m = value - c;
        float r;
        float g;
        float b;
        if (hue < 60) {
            r = c;
            g = x;
            b = 0;
        } else if (hue < 120) {
            r = x;
            g = c;
            b = 0;
        } else if (hue < 180) {
            r = 0;
            g = c;
            b = x;
        } else if (hue < 240) {
            r = 0;
            g = x;
            b = c;
        } else if (hue < 300) {
            r = x;
            g = 0;
            b = c;
        } else {
            r = c;
            g = 0;
            b = x;
        }
        return new Color(r + m, g + m, b + m, 1f);
    }

    private static void addLine(List<Float> out, float x1, float z1, float x2, float z2) {
        for (float[] point : new float[][] {{x1, z1}, {x2, z2}}) {
            out.add(point[0]);
            out.add(0f);
            out.add(point[1]);
            out.add(0f);
            out.add(1f);
            out.add(0f);
        }
    }

    @Override
    public void dispose() {
        for (Mesh mesh : owned) {
            mesh.dispose();
        }
        owned.clear();
        shader.dispose();
    }
}
