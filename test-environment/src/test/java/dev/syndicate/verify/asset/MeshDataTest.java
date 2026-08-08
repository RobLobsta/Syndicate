/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The harness's independent mesh measurement (docs/14_test_environment.md#D14-S5.4).
 *
 * <p>Checked against closed-form answers, not against the Blender tool's numbers. D09-S6.2 argues
 * that agreement between the two implementations is evidence both are right — which only follows if
 * each was independently verified against the truth first.
 */
@Tag("unit")
class MeshDataTest {

    /** A closed axis-aligned box with outward-facing triangles, base on {@code y = 0}. */
    private static MeshData box(float sx, float sy, float sz) {
        float hx = sx / 2f;
        float hz = sz / 2f;
        float[] positions = {
            -hx, 0f, -hz, hx, 0f, -hz, hx, 0f, hz, -hx, 0f, hz, -hx, sy, -hz, hx, sy, -hz, hx, sy, hz, -hx, sy, hz,
        };
        int[] indices = {
            0, 2, 1, 0, 3, 2, // bottom, wound so the normal points down
            4, 5, 6, 4, 6, 7, // top
            0, 1, 5, 0, 5, 4,
            1, 2, 6, 1, 6, 5,
            2, 3, 7, 2, 7, 6,
            3, 0, 4, 3, 4, 7,
        };
        return new MeshData("box", positions, indices);
    }

    @Test
    void volumeOfAUnitCubeIsExactlyOne() {
        assertThat(box(1f, 1f, 1f).volumeM3()).isEqualTo(1f);
    }

    @Test
    void volumeScalesWithEveryDimension() {
        assertThat(box(2f, 1f, 0.1f).volumeM3()).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(1e-6f));
    }

    /**
     * The centroid of a box whose base sits on {@code y = 0} is at half its height. This is the
     * analytic value D14-R22 records for {@code test_cube_1m}, and the reason that fixture exists:
     * it validates the manifest generator against closed-form truth rather than against itself.
     */
    @Test
    void centroidOfABaseOriginBoxIsHalfItsHeight() {
        Vector3 centroid = box(1f, 1f, 1f).centroid(new Vector3());
        assertThat(centroid.x).isCloseTo(0f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(centroid.y).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(1e-5f));
        assertThat(centroid.z).isCloseTo(0f, org.assertj.core.data.Offset.offset(1e-5f));
    }

    @Test
    void reportsVertexAndTriangleCounts() {
        MeshData mesh = box(1f, 1f, 1f);
        assertThat(mesh.vertexCount()).isEqualTo(8);
        assertThat(mesh.triangleCount()).isEqualTo(12);
    }

    /** A NaN coordinate must be reported, not propagated into a mass (ASSET-005, PHYS-011). */
    @Test
    void detectsNonFiniteCoordinates() {
        MeshData good = box(1f, 1f, 1f);
        assertThat(good.isFinite()).isTrue();

        float[] broken = good.positions().clone();
        broken[4] = Float.NaN;
        assertThat(new MeshData("broken", broken, good.indices()).isFinite()).isFalse();
    }

    /**
     * Volume is reported positive even when the source was wound inward.
     *
     * <p>An inward-wound mesh is a content bug worth reporting as such; returning a negative volume
     * here would surface it much later as a negative mass, which reads as an arithmetic error
     * rather than an authoring one.
     */
    @Test
    void volumeIsPositiveRegardlessOfWinding() {
        MeshData mesh = box(1f, 1f, 1f);
        int[] flipped = mesh.indices().clone();
        for (int t = 0; t < flipped.length; t += 3) {
            int swap = flipped[t + 1];
            flipped[t + 1] = flipped[t + 2];
            flipped[t + 2] = swap;
        }
        assertThat(new MeshData("flipped", mesh.positions(), flipped).volumeM3())
                .isEqualTo(1f);
    }
}
