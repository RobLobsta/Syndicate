/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The vehicle compound shape and its child index map (docs/06_physics_simulation.md#D06-S5.3). */
@Tag("integration")
class VehicleCompoundTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_01");

    static {
        Bullet.init(false);
    }

    private ShapeCache cache;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        cache = new ShapeCache();
    }

    @AfterEach
    void tearDown() {
        cache.dispose();
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void childIndices_followSlotPathOrder_regardlessOfInsertionOrder() {
        // G3. Child indices are how a hit is attributed to a part and how a detach is replicated
        // (D07-S5.1), so two peers that attached the same parts in different orders must still agree
        // on which index means which part.
        List<VehicleCompound.Child> shuffled =
                new ArrayList<>(children("root", "root/armor_a", "root/turret", "root/turret/barrel"));
        Collections.shuffle(shuffled, new Random(9L));

        VehicleCompound compound = cache.buildVehicleCompound(1, ASSEMBLY, shuffled);

        assertThat(compound.slotPaths()).containsExactly("root", "root/armor_a", "root/turret", "root/turret/barrel");
    }

    @Test
    void removingAChild_rebuildsTheMapToMatchTheCompound() {
        // D06-R14 / AC-D06-7. removeChildShapeByIndex moves the LAST child into the removed slot
        // rather than shifting the tail down, so any index cached across a structural change now
        // addresses a different part — silently. The map is rebuilt inside removeChild so there is no
        // window in which it is stale.
        VehicleCompound compound =
                cache.buildVehicleCompound(1, ASSEMBLY, children("root", "root/a", "root/b", "root/c"));

        assertThat(compound.removeChild("root/a")).isTrue();

        assertThat(compound.childCount()).isEqualTo(3);
        assertThat(compound.compound().getNumChildShapes()).isEqualTo(3);
        assertThat(compound.slotPaths()).containsExactlyInAnyOrder("root", "root/b", "root/c");
        assertThat(compound.childIndexOf("root/a")).isEqualTo(-1);
        // The surviving paths still resolve to the child that actually holds their geometry.
        for (String path : compound.slotPaths()) {
            assertThat(compound.slotPathAt(compound.childIndexOf(path))).isEqualTo(path);
        }
    }

    @Test
    void removingEveryChildInEveryOrder_leavesTheMapConsistent() {
        // T-D06-7: detach every non-chassis part in many orders. The failure this catches is an
        // off-by-one in the swap-last-into-index mirror, which only shows up for some orders.
        List<String> paths = List.of("root", "root/a", "root/b", "root/c", "root/d", "root/e");
        Random random = new Random(1337L);

        for (int permutation = 0; permutation < 50; permutation++) {
            VehicleCompound compound = cache.buildVehicleCompound(100 + permutation, ASSEMBLY, children(paths));
            List<String> order = new ArrayList<>(paths.subList(1, paths.size()));
            Collections.shuffle(order, random);

            for (String path : order) {
                assertThat(compound.removeChild(path)).isTrue();
                assertThat(compound.compound().getNumChildShapes()).isEqualTo(compound.childCount());
                for (String remaining : compound.slotPaths()) {
                    assertThat(compound.slotPathAt(compound.childIndexOf(remaining)))
                            .isEqualTo(remaining);
                }
            }
            assertThat(compound.slotPaths()).containsExactly("root");
            cache.releaseVehicleCompound(100 + permutation);
        }
    }

    @Test
    void recentring_movesEveryChildByTheSameDelta() {
        // D06-S5.7 step 2. Bullet treats a compound's local origin as the centre of mass; a vehicle
        // whose compound is not recentred rotates about its mesh origin instead.
        VehicleCompound compound = cache.buildVehicleCompound(1, ASSEMBLY, children("root", "root/a"));
        Matrix4 before = new Matrix4(compound.compound().getChildTransform(1));

        compound.recentre(0.25f, -0.5f, 0.125f);

        Vector3 after = new Vector3();
        compound.compound().getChildTransform(1).getTranslation(after);
        Vector3 expected = new Vector3();
        before.getTranslation(expected).sub(0.25f, -0.5f, 0.125f);
        assertThat(after.epsilonEquals(expected, 1e-5f))
                .as("child moved from %s to %s, expected %s", before.getTranslation(new Vector3()), after, expected)
                .isTrue();
    }

    private static List<VehicleCompound.Child> children(String... slotPaths) {
        return children(List.of(slotPaths));
    }

    private static List<VehicleCompound.Child> children(List<String> slotPaths) {
        MeshData mesh = DestructionTestScene.boxMesh(new Vector3(0.25f, 0.25f, 0.25f));
        List<VehicleCompound.Child> children = new ArrayList<>(slotPaths.size());
        for (int i = 0; i < slotPaths.size(); i++) {
            children.add(new VehicleCompound.Child(
                    slotPaths.get(i),
                    ShapeCacheKey.of(AssetId.of("part_hull_" + i), ShapeCacheKey.Variant.PART_HULL),
                    mesh,
                    new Matrix4().setToTranslation(i * 0.6f, 0f, 0f)));
        }
        return children;
    }
}
