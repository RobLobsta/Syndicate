/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import com.badlogic.gdx.math.Vector3;

/**
 * The vertex positions of one collision mesh (docs/08_asset_pipeline.md#D08-S4.2,
 * docs/06_physics_simulation.md#D06-S5.2).
 *
 * <p>Positions only, deliberately. Everything {@code game-core} does with a mesh is build a convex
 * hull from it, and a convex hull is defined by its point set — normals, UVs and indices contribute
 * nothing and would be several megabytes of memory the headless server has no use for (G17).
 *
 * <p>Immutable: the array is copied on construction and never handed back. Shapes built from a mesh
 * are cached and shared for the life of a match (D06-R8), so a caller that could mutate the source
 * afterwards would leave the cache holding a shape that no longer matches the art it names.
 */
public final class MeshData {

    private final float[] positions;

    /**
     * @param positions {@code x, y, z} triples in part-local metres; copied
     * @throws IllegalArgumentException if the length is not a multiple of 3, if there are fewer
     *     than four vertices (a convex hull needs a tetrahedron's worth of points to enclose any
     *     volume at all), or if any coordinate is non-finite (D00-R13)
     */
    public MeshData(float[] positions) {
        if (positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must be x,y,z triples, got " + positions.length + " floats");
        }
        if (positions.length < 12) {
            throw new IllegalArgumentException(
                    "a collision mesh needs at least 4 vertices to enclose a volume, got " + positions.length / 3);
        }
        for (int i = 0; i < positions.length; i++) {
            if (!Float.isFinite(positions[i])) {
                throw new IllegalArgumentException("non-finite coordinate at index " + i + " (D00-R13)");
            }
        }
        this.positions = positions.clone();
    }

    /** How many vertices the mesh has. */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** Writes vertex {@code index} into {@code out} and returns it. */
    public Vector3 vertex(int index, Vector3 out) {
        int base = index * 3;
        return out.set(positions[base], positions[base + 1], positions[base + 2]);
    }

    /** The axis-aligned extent of the mesh, written into {@code outMin} and {@code outMax}. */
    public void bounds(Vector3 outMin, Vector3 outMax) {
        outMin.set(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        outMax.set(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (int i = 0; i < positions.length; i += 3) {
            outMin.x = Math.min(outMin.x, positions[i]);
            outMin.y = Math.min(outMin.y, positions[i + 1]);
            outMin.z = Math.min(outMin.z, positions[i + 2]);
            outMax.x = Math.max(outMax.x, positions[i]);
            outMax.y = Math.max(outMax.y, positions[i + 1]);
            outMax.z = Math.max(outMax.z, positions[i + 2]);
        }
    }

    @Override
    public String toString() {
        return "MeshData[" + vertexCount() + " vertices]";
    }
}
