/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.asset;

import com.badlogic.gdx.math.Vector3;

/**
 * One mesh as raw geometry: a node name, interleaved positions, and triangle indices.
 *
 * <p>Deliberately not a libGDX {@code Mesh}. A {@code Mesh} is a GPU buffer and needs a GL context
 * to exist, which the headless runner of docs/14_test_environment.md#D14-S5.13 does not have — and
 * the physics checks are exactly the ones that must run in CI. Keeping the harness's mesh type on
 * the CPU is what lets one loader serve both modes (G17).
 */
public record MeshData(String name, float[] positions, int[] indices) {

    /** Vertex count. */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** Triangle count. */
    public int triangleCount() {
        return indices.length / 3;
    }

    /** Writes vertex {@code i} into {@code out}. */
    public Vector3 vertex(int i, Vector3 out) {
        return out.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
    }

    /**
     * Closed-mesh volume by the divergence theorem.
     *
     * <p>The same formula the Blender tool uses (D09-S6.2), implemented independently here rather
     * than shared. D09-S6.2 says agreement between two implementations is the evidence both are
     * right — an argument that only holds while they really are two.
     */
    public float volumeM3() {
        double v6 = 0.0;
        for (int t = 0; t < indices.length; t += 3) {
            int a = indices[t] * 3;
            int b = indices[t + 1] * 3;
            int c = indices[t + 2] * 3;
            double cx = (double) positions[b + 1] * positions[c + 2] - (double) positions[b + 2] * positions[c + 1];
            double cy = (double) positions[b + 2] * positions[c] - (double) positions[b] * positions[c + 2];
            double cz = (double) positions[b] * positions[c + 1] - (double) positions[b + 1] * positions[c];
            v6 += positions[a] * cx + positions[a + 1] * cy + positions[a + 2] * cz;
        }
        return (float) (Math.abs(v6) / 6.0);
    }

    /** Volume centroid, falling back to the vertex average on a degenerate mesh. */
    public Vector3 centroid(Vector3 out) {
        double nx = 0;
        double ny = 0;
        double nz = 0;
        double den = 0;
        for (int t = 0; t < indices.length; t += 3) {
            int a = indices[t] * 3;
            int b = indices[t + 1] * 3;
            int c = indices[t + 2] * 3;
            double cx = (double) positions[b + 1] * positions[c + 2] - (double) positions[b + 2] * positions[c + 1];
            double cy = (double) positions[b + 2] * positions[c] - (double) positions[b] * positions[c + 2];
            double cz = (double) positions[b] * positions[c + 1] - (double) positions[b + 1] * positions[c];
            double vol = (positions[a] * cx + positions[a + 1] * cy + positions[a + 2] * cz) / 6.0;
            nx += vol * 0.25 * ((double) positions[a] + positions[b] + positions[c]);
            ny += vol * 0.25 * ((double) positions[a + 1] + positions[b + 1] + positions[c + 1]);
            nz += vol * 0.25 * ((double) positions[a + 2] + positions[b + 2] + positions[c + 2]);
            den += vol;
        }
        if (Math.abs(den) < 1e-12) {
            return averageVertex(out);
        }
        return out.set((float) (nx / den), (float) (ny / den), (float) (nz / den));
    }

    /** Mean vertex position. */
    public Vector3 averageVertex(Vector3 out) {
        out.set(0f, 0f, 0f);
        int count = vertexCount();
        for (int i = 0; i < count; i++) {
            out.add(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
        }
        return count == 0 ? out : out.scl(1f / count);
    }

    /** True when no coordinate is NaN or infinite (PHYS-011, ASSET-005). */
    public boolean isFinite() {
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
