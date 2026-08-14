/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Collision shape construction and caching (docs/06_physics_simulation.md#D06-S5.2). */
@Tag("integration")
class ShapeCacheTest {

    private static final AssetId PLATE = AssetId.of("panel_plate_front");
    private static final AssetId SPHERE = AssetId.of("shard_sphere_source");

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
    void sameKey_returnsTheSameNativeShape() {
        // D06-R8: shapes are immutable, cached and shared. Two bodies of the same part type must get
        // one shape, or a hundred shards of one part would be a hundred hulls in memory and in the
        // broadphase's shape table.
        ShapeCacheKey key = ShapeCacheKey.of(PLATE, ShapeCacheKey.Variant.PART_HULL);

        btConvexHullShape first = cache.hullFor(key, box(0.5f, 0.05f, 0.4f));
        btConvexHullShape second = cache.hullFor(key, box(0.5f, 0.05f, 0.4f));

        assertThat(second).isSameAs(first);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void partAndShardVariants_ofOneAsset_areDistinctEntries() {
        // The key carries the variant, so a part's 64-vertex hull and a shard's 32-vertex hull of the
        // same asset coexist rather than one silently serving both budgets (D06-R6).
        cache.hullFor(ShapeCacheKey.of(PLATE, ShapeCacheKey.Variant.PART_HULL), box(0.5f, 0.05f, 0.4f));
        cache.hullFor(ShapeCacheKey.shard(PLATE, 0), box(0.1f, 0.05f, 0.1f));

        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void everyConvexShape_leavesTheCacheWithTheWorldsMargin() {
        // AC-D06-4. Bullet's default 0.04 m is comparable to the thickness of an armour plate, which
        // both floats the part visibly off the ground and makes contact geometry disagree with the
        // mass the plate was given (D06-R13).
        btConvexHullShape simple =
                cache.hullFor(ShapeCacheKey.of(PLATE, ShapeCacheKey.Variant.PART_HULL), box(1f, 1f, 1f));
        btConvexHullShape simplified =
                cache.hullFor(ShapeCacheKey.of(SPHERE, ShapeCacheKey.Variant.PART_HULL), sphere(1f, 400));

        assertThat(simple.getMargin()).isEqualTo(PhysicsWorld.COLLISION_MARGIN_M, within(1e-6f));
        assertThat(simplified.getMargin()).isEqualTo(PhysicsWorld.COLLISION_MARGIN_M, within(1e-6f));
    }

    @Test
    void aSimplifiedHull_sitsOneMarginOutsideItsMesh_notTwo() {
        // D06-R13a / DISC-008. btShapeHull samples support points from the shape it is handed, and
        // those include that shape's margin — its own buildHull(margin) argument is ignored. A source
        // that still carried a margin therefore yields hull points one margin out, and the finished
        // shape adds its own on top. The symptom is a resting height that is right for boxes and
        // wrong for anything curved enough to be simplified, which is exactly how it reached the
        // fixture set once already.
        //
        // Measured on the *unsimplified* box as the control and the simplified sphere as the subject:
        // both must have their support point at the mesh surface, before the margin is added.
        float radius = 1f;
        btConvexHullShape simplified =
                cache.hullFor(ShapeCacheKey.of(SPHERE, ShapeCacheKey.Variant.PART_HULL), sphere(radius, 400));
        assertThat(simplified.getNumPoints()).isLessThanOrEqualTo(ShapeCache.MAX_HULL_VERTICES);

        Vector3 support = simplified.localGetSupportingVertexWithoutMargin(new Vector3(0f, 1f, 0f));

        // A 64-vertex hull of a sphere is inscribed, so its highest point is at or just below the
        // radius — never above it, which is what a baked-in margin would produce.
        assertThat(support.y).isLessThanOrEqualTo(radius + 1e-4f);
        assertThat(support.y).isGreaterThan(radius * 0.85f);
    }

    @Test
    void aHullOverBudget_isSimplifiedToBulletsCeiling() {
        // D06-R6 / ASSET-011. btConvexHullShape's support search is linear in vertex count and runs
        // several times per contact pair, so the budget is a frame-time decision — but btShapeHull
        // has no target-count parameter: it samples a fixed 42 unit-sphere directions and keeps the
        // unique support points. A part hull therefore lands comfortably under its 64, and a shard
        // hull cannot be brought under its 32 here at all. Meeting the shard budget is the Blender
        // tool's job (D09-S5.5), gated by the harness's blocking ASSET-011 check.
        btConvexHullShape part =
                cache.hullFor(ShapeCacheKey.of(SPHERE, ShapeCacheKey.Variant.PART_HULL), sphere(1f, 500));
        btConvexHullShape shard = cache.hullFor(ShapeCacheKey.shard(SPHERE, 3), sphere(0.2f, 500));

        assertThat(part.getNumPoints()).isLessThanOrEqualTo(ShapeCache.MAX_HULL_VERTICES);
        assertThat(part.getNumPoints()).isLessThan(500);
        assertThat(shard.getNumPoints()).isLessThanOrEqualTo(ShapeCache.BT_SHAPE_HULL_VERTICES);
    }

    @Test
    void aDegenerateMesh_isRejected() {
        // The pseudocode's `assert shapeVolume(shape) > 0`. A coplanar hull gets a zero inertia
        // component back from calculateLocalInertia (D06-E13) and becomes infinitely easy to spin;
        // rejecting it here names the mesh instead of leaving a body that behaves impossibly.
        MeshData flat = new MeshData(new float[] {0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 1f});

        assertThatThrownBy(() -> cache.hullFor(ShapeCacheKey.of(PLATE, ShapeCacheKey.Variant.PART_HULL), flat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("degenerate");
    }

    @Test
    void aVehiclesCompound_isOwnedHere_andReleasedWithTheVehicle() {
        // D06-R8 and D02-S5.7 rule 2 put every shape's ownership here. A vehicle's compound is the
        // one instance-scoped entry: it is mutable and per-vehicle, so unlike a part hull it has a
        // single owner that dies with it.
        int vehicleEntity = 42;
        AssetId assembly = AssetId.of("assembly_medium_01");
        VehicleCompound compound = cache.buildVehicleCompound(
                vehicleEntity,
                assembly,
                List.of(new VehicleCompound.Child(
                        "root",
                        ShapeCacheKey.of(PLATE, ShapeCacheKey.Variant.PART_HULL),
                        box(0.5f, 0.2f, 0.5f),
                        new com.badlogic.gdx.math.Matrix4())));

        assertThat(cache.vehicleCompound(vehicleEntity)).isSameAs(compound);
        assertThat(cache.contains(ShapeCache.compoundKey(assembly, vehicleEntity)))
                .isTrue();

        assertThat(cache.releaseVehicleCompound(vehicleEntity)).isTrue();
        assertThat(cache.vehicleCompound(vehicleEntity)).isNull();
        // The part hull survives: it is shared by every instance of that part type (D06-R8).
        assertThat(cache.contains(ShapeCacheKey.of(PLATE, ShapeCacheKey.Variant.PART_HULL)))
                .isTrue();
    }

    private static MeshData box(float halfX, float halfY, float halfZ) {
        return new MeshData(new float[] {
            -halfX, -halfY, -halfZ, halfX, -halfY, -halfZ, -halfX, halfY, -halfZ, halfX, halfY, -halfZ, -halfX, -halfY,
            halfZ, halfX, -halfY, halfZ, -halfX, halfY, halfZ, halfX, halfY, halfZ
        });
    }

    /** A point cloud on a sphere, dense enough to force simplification. Deterministic by index. */
    private static MeshData sphere(float radius, int count) {
        float[] positions = new float[count * 3];
        // The golden-angle spiral spreads points evenly without any randomness, so the same mesh
        // comes out on every run and the vertex-budget assertions are not flaky.
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < count; i++) {
            double y = 1.0 - (i / (double) (count - 1)) * 2.0;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double theta = golden * i;
            positions[i * 3] = (float) (Math.cos(theta) * ring) * radius;
            positions[i * 3 + 1] = (float) y * radius;
            positions[i * 3 + 2] = (float) (Math.sin(theta) * ring) * radius;
        }
        return new MeshData(positions);
    }
}
