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
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import dev.syndicate.core.asset.GltfImage;
import dev.syndicate.core.asset.GltfMaterial;
import dev.syndicate.core.asset.GltfMeshNode;
import dev.syndicate.core.asset.GltfModel;
import dev.syndicate.core.asset.GltfPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws a whole glTF model — smooth-shaded, textured, with transparency
 * (docs/14_test_environment.md#D14-S5.11).
 *
 * <p>Separate from {@link SceneRenderer} rather than an option on it. That one draws the destruction
 * scene: flat-shaded faceted shards in a colour per index, because telling shards apart is its whole
 * job. This one draws a car, where the questions are the opposite ones — is the paint the right
 * colour, is the glass in front of the interior, is anything inside out — and answering them needs
 * authored normals, base colour textures and a blended pass. Folding both into one renderer would
 * give a shader with two unrelated modes and neither doing its job well.
 *
 * <p>Geometry comes from the same {@link dev.syndicate.core.asset.GltfReader} parse the checks
 * measure, which is what makes a capture evidence about the model rather than a second opinion about
 * it (DEC-008's reasoning, applied to whole models).
 */
public final class ModelRenderer implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(ModelRenderer.class);

    /**
     * libGDX indexes a {@link Mesh} with shorts, so a primitive with more vertices than this is
     * split. Nothing in the two shipped cars needs it — their largest primitive is 53k vertices —
     * but the failure mode if it did would be silently wrapped indices drawing garbage triangles,
     * which is not something to find out from a screenshot.
     */
    private static final int MAX_VERTICES_PER_MESH = 65535;

    private static final String VERTEX_SHADER =
            """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projView;
            varying vec3 v_normal;
            varying vec2 v_uv;
            void main() {
                v_normal = a_normal;
                v_uv = a_texCoord0;
                gl_Position = u_projView * vec4(a_position, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER =
            """
            #ifdef GL_ES
            precision mediump float;
            #endif
            uniform vec4 u_baseColor;
            uniform vec3 u_lightDir;
            uniform float u_hasTexture;
            uniform sampler2D u_texture;
            varying vec3 v_normal;
            varying vec2 v_uv;
            void main() {
                vec4 albedo = u_baseColor;
                if (u_hasTexture > 0.5) {
                    albedo *= texture2D(u_texture, v_uv);
                }
                vec3 n = normalize(v_normal);
                // Two-sided shading: a car model has single-sided panels seen from inside through
                // the glass, and lighting them by the back of their normal makes them read as holes.
                vec3 lit = -normalize(u_lightDir);
                float key = abs(dot(n, lit)) * 0.85;
                float sky = (dot(n, vec3(0.0, 1.0, 0.0)) * 0.5 + 0.5) * 0.35;
                float rim = pow(1.0 - abs(n.z), 3.0) * 0.12;
                gl_FragColor = vec4(albedo.rgb * (0.22 + key + sky) + rim, albedo.a);
            }
            """;

    /** One drawable: a mesh, the material it draws with, and the texture that material names. */
    private record Piece(Mesh mesh, GltfMaterial material, Texture texture, Vector3 centroid) {}

    private final ShaderProgram shader;
    private final PerspectiveCamera camera;
    private final Vector3 lightDirection = new Vector3(-0.5f, -0.8f, -0.32f).nor();
    private final List<Piece> opaque = new ArrayList<>();
    private final List<Piece> blended = new ArrayList<>();
    private final List<Mesh> ownedMeshes = new ArrayList<>();
    private final Map<Integer, Texture> textures = new HashMap<>();
    private Mesh grid;

    public ModelRenderer(int width, int height) {
        ShaderProgram.pedantic = false;
        shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            throw new IllegalStateException("model shader failed to compile: " + shader.getLog());
        }
        camera = new PerspectiveCamera(38f, width, height);
        camera.near = 0.05f;
        camera.far = 400f;
    }

    /** Uploads every primitive in the model, loading the textures its materials name. */
    public void load(GltfModel model) {
        Path directory = model.source().toAbsolutePath().getParent();
        int skippedTextures = 0;
        for (GltfMeshNode node : model.meshNodes()) {
            for (GltfPrimitive primitive : node.primitives()) {
                GltfMaterial material = model.materialFor(primitive);
                Texture texture = textureFor(model, material, directory);
                if (material.hasBaseColorTexture() && texture == null) {
                    skippedTextures++;
                }
                for (Mesh mesh : build(primitive)) {
                    Piece piece = new Piece(mesh, material, texture, centroidOf(primitive));
                    if ("BLEND".equals(material.alphaMode()) || material.baseColorFactor()[3] < 0.999f) {
                        blended.add(piece);
                    } else {
                        opaque.add(piece);
                    }
                }
            }
        }
        LOG.info(
                "uploaded {} opaque and {} blended pieces, {} textures{}",
                opaque.size(),
                blended.size(),
                textures.size(),
                skippedTextures == 0 ? "" : " (" + skippedTextures + " unreadable)");
    }

    private Texture textureFor(GltfModel model, GltfMaterial material, Path directory) {
        if (!material.hasBaseColorTexture()) {
            return null;
        }
        int index = material.baseColorImageIndex();
        if (textures.containsKey(index)) {
            return textures.get(index);
        }
        Texture texture = null;
        try {
            GltfImage image = model.images().get(index);
            byte[] bytes = image.isEmbedded() ? image.embedded() : Files.readAllBytes(directory.resolve(image.uri()));
            Pixmap pixmap = new Pixmap(bytes, 0, bytes.length);
            texture = new Texture(pixmap, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            pixmap.dispose();
        } catch (IOException | RuntimeException e) {
            // An unreadable texture is a flat-coloured panel, not a failed run: the capture still
            // answers the shape-and-orientation questions it exists to answer.
            LOG.warn("texture {} could not be loaded: {}", index, e.toString());
        }
        textures.put(index, texture);
        return texture;
    }

    private static Vector3 centroidOf(GltfPrimitive primitive) {
        Vector3 out = new Vector3();
        float[] positions = primitive.positions();
        for (int i = 0; i < positions.length; i += 3) {
            out.add(positions[i], positions[i + 1], positions[i + 2]);
        }
        int count = primitive.vertexCount();
        return count == 0 ? out : out.scl(1f / count);
    }

    /**
     * Uploads one primitive, splitting it when it addresses more vertices than a short index can.
     *
     * <p>The split re-indexes each chunk against only the vertices it uses, so a chunk boundary
     * never falls inside a triangle.
     */
    private List<Mesh> build(GltfPrimitive primitive) {
        List<Mesh> out = new ArrayList<>();
        float[] positions = primitive.positions();
        float[] normals = primitive.normals();
        float[] uvs = primitive.texCoords();
        int[] indices = primitive.indices();

        int cursor = 0;
        while (cursor < indices.length) {
            Map<Integer, Short> remap = new HashMap<>();
            List<Float> vertices = new ArrayList<>();
            List<Short> chunkIndices = new ArrayList<>();
            while (cursor + 2 < indices.length && remap.size() + 3 <= MAX_VERTICES_PER_MESH) {
                for (int corner = 0; corner < 3; corner++) {
                    int source = indices[cursor + corner];
                    Short local = remap.get(source);
                    if (local == null) {
                        local = (short) remap.size();
                        remap.put(source, local);
                        vertices.add(positions[source * 3]);
                        vertices.add(positions[source * 3 + 1]);
                        vertices.add(positions[source * 3 + 2]);
                        appendNormal(vertices, normals, source);
                        vertices.add(uvs == null ? 0f : uvs[source * 2]);
                        vertices.add(uvs == null ? 0f : uvs[source * 2 + 1]);
                    }
                    chunkIndices.add(local);
                }
                cursor += 3;
            }
            if (chunkIndices.isEmpty()) {
                break;
            }
            out.add(upload(vertices, chunkIndices));
        }
        return out;
    }

    private static void appendNormal(List<Float> vertices, float[] normals, int source) {
        if (normals == null) {
            // No authored normal: point it up, which is wrong but flat rather than black.
            vertices.add(0f);
            vertices.add(1f);
            vertices.add(0f);
            return;
        }
        vertices.add(normals[source * 3]);
        vertices.add(normals[source * 3 + 1]);
        vertices.add(normals[source * 3 + 2]);
    }

    private Mesh upload(List<Float> vertices, List<Short> indices) {
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertexArray.length; i++) {
            vertexArray[i] = vertices.get(i);
        }
        short[] indexArray = new short[indices.size()];
        for (int i = 0; i < indexArray.length; i++) {
            indexArray[i] = indices.get(i);
        }
        Mesh mesh = new Mesh(
                true,
                vertexArray.length / 8,
                indexArray.length,
                new VertexAttribute(Usage.Position, 3, "a_position"),
                new VertexAttribute(Usage.Normal, 3, "a_normal"),
                new VertexAttribute(Usage.TextureCoordinates, 2, "a_texCoord0"));
        mesh.setVertices(vertexArray);
        mesh.setIndices(indexArray);
        ownedMeshes.add(mesh);
        return mesh;
    }

    /** Builds the ground grid as a set of lines. */
    public void buildGrid(float halfExtent, float spacing) {
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
        grid = new Mesh(
                true,
                array.length / 8,
                0,
                new VertexAttribute(Usage.Position, 3, "a_position"),
                new VertexAttribute(Usage.Normal, 3, "a_normal"),
                new VertexAttribute(Usage.TextureCoordinates, 2, "a_texCoord0"));
        grid.setVertices(array);
        ownedMeshes.add(grid);
    }

    private static void addLine(List<Float> out, float x1, float z1, float x2, float z2) {
        for (float[] point : new float[][] {{x1, z1}, {x2, z2}}) {
            out.add(point[0]);
            out.add(0f);
            out.add(point[1]);
            out.add(0f);
            out.add(1f);
            out.add(0f);
            out.add(0f);
            out.add(0f);
        }
    }

    /** Points the camera at a target from an eye position. */
    public void look(Vector3 eye, Vector3 target) {
        camera.position.set(eye);
        camera.up.set(0f, 1f, 0f);
        camera.lookAt(target);
        camera.update();
    }

    /** Draws the grid and then the model: opaque first, then transparency back to front. */
    public void render(Color background) {
        Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        Gdx.gl.glClearColor(background.r, background.g, background.b, background.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        // Culling off: downloaded art is not reliably wound, and a car with its roof culled away is
        // a worse diagnostic than one with a few interior faces drawn twice.
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);

        shader.bind();
        shader.setUniformMatrix("u_projView", camera.combined);
        shader.setUniformf("u_lightDir", lightDirection);

        if (grid != null) {
            drawFlat(grid, new Color(0.20f, 0.22f, 0.27f, 1f), GL20.GL_LINES);
        }
        for (Piece piece : opaque) {
            draw(piece);
        }

        // Transparency after everything opaque, sorted far to near, with depth writes off — the
        // minimum that makes glass look like glass rather than like a hole in the roof.
        blended.sort(Comparator.comparingDouble((Piece p) -> -camera.position.dst2(p.centroid())));
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        for (Piece piece : blended) {
            draw(piece);
        }
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void draw(Piece piece) {
        float[] color = piece.material().baseColorFactor();
        shader.setUniformf("u_baseColor", color[0], color[1], color[2], color[3]);
        if (piece.texture() != null) {
            piece.texture().bind(0);
            shader.setUniformi("u_texture", 0);
            shader.setUniformf("u_hasTexture", 1f);
        } else {
            shader.setUniformf("u_hasTexture", 0f);
        }
        piece.mesh().render(shader, GL20.GL_TRIANGLES);
    }

    private void drawFlat(Mesh mesh, Color color, int primitiveType) {
        shader.setUniformf("u_baseColor", color);
        shader.setUniformf("u_hasTexture", 0f);
        mesh.render(shader, primitiveType);
    }

    @Override
    public void dispose() {
        for (Mesh mesh : ownedMeshes) {
            mesh.dispose();
        }
        ownedMeshes.clear();
        for (Texture texture : textures.values()) {
            if (texture != null) {
                texture.dispose();
            }
        }
        textures.clear();
        shader.dispose();
    }
}
