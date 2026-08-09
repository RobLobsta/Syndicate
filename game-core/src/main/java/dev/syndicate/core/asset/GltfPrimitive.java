/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;

/**
 * One drawable piece of a mesh: triangles over one material
 * (docs/08_asset_pipeline.md#D08-S4.5).
 *
 * <p><b>Positions and normals are in scene space, not mesh space.</b> {@link GltfReader} composes
 * every node's transform down from the scene root and applies it here, because a glTF node
 * hierarchy is where a Blender export puts its Y-up conversion (D00-R16) and where an FBX-derived
 * export puts its centimetre-to-metre scale. A reader that returned raw mesh coordinates would hand
 * back a car lying on its side at a hundredth of its size, and — worse — would do it silently, since
 * every derived quantity from a hull to a volume stays self-consistently wrong.
 *
 * <p>Triangles only. A {@code TRIANGLE_STRIP} or {@code TRIANGLE_FAN} primitive is expanded into
 * independent triangles at read time so every consumer sees one topology; points and lines carry no
 * volume and are dropped.
 *
 * @param positions {@code x, y, z} triples in scene-space metres; never null, never empty
 * @param normals {@code x, y, z} triples, unit length, or null when not read or not authored
 * @param texCoords {@code u, v} pairs from {@code TEXCOORD_0}, or null
 * @param indices triangle indices into the vertex arrays; length is a multiple of 3
 * @param materialIndex index into {@link GltfModel#materials()}, or -1 for the default material
 */
public record GltfPrimitive(float[] positions, float[] normals, float[] texCoords, int[] indices, int materialIndex) {

    /** Vertex count. */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** Triangle count. */
    public int triangleCount() {
        return indices.length / 3;
    }

    /** Writes vertex {@code index} into {@code out} and returns it. */
    public Vector3 vertex(int index, Vector3 out) {
        int base = index * 3;
        return out.set(positions[base], positions[base + 1], positions[base + 2]);
    }
}
