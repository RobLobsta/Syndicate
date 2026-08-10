/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btCompoundShape;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import com.badlogic.gdx.physics.bullet.collision.btShapeHull;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sole owner of every collision shape in a match
 * (docs/06_physics_simulation.md#D06-S5.2, #D06-R8, docs/02_technical_architecture.md#D02-S5.7
 * rule 2).
 *
 * <p>Shapes are immutable, cached and shared by reference. A body never disposes one: a hundred
 * shards of the same part type point at one {@code btConvexHullShape}, so the first body destroyed
 * would free it out from under the other ninety-nine. That is why {@code RigidBodyComponent} holds a
 * {@link ShapeCacheKey} rather than a native pointer — the type system makes the mistake harder to
 * express.
 *
 * <p><b>Margins (D06-R13, R13a).</b> Every convex shape leaves this class with a margin of exactly
 * {@link PhysicsWorld#COLLISION_MARGIN_M}, and every source shape has its margin zeroed
 * <em>before</em> {@code btShapeHull} runs. {@code btShapeHull} samples support points from the
 * shape it is handed, and those points already include that shape's margin — its own
 * {@code buildHull(margin)} argument is ignored. A source that still carried a margin would produce
 * hull points displaced one margin outward, and the finished shape would add its own on top, so a
 * simplified hull would sit two margins outside its mesh while an unsimplified one sat at exactly
 * one. That asymmetry is invisible on a box and obvious on a sphere, which is how it reached the
 * fixture set once already (DISC-008).
 *
 * <p><b>Compounds are the exception to sharing.</b> A vehicle's {@code btCompoundShape} is
 * per-instance and mutable — parts leave it as they detach — so two vehicles of the same assembly
 * cannot share one. They are still owned here, under an instance-scoped key, because ownership is
 * what this class is for; {@link #releaseVehicleCompound} frees one when its vehicle dies. The part hulls a
 * compound references are shared and outlive it.
 *
 * <p>Entries are held in a {@code TreeMap}: nothing in the simulation iterates the cache today, but
 * a cache that could only be iterated in hash order would be a G3 violation waiting for the first
 * caller that does.
 */
public final class ShapeCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ShapeCache.class);

    /**
     * Vertex budget for a part's collision hull (D06-R6).
     *
     * <p>{@code btConvexHullShape}'s support-point search is linear in vertex count and it runs
     * several times per contact pair, so the budget is a frame-time decision rather than a memory
     * one. It sits above {@link #BT_SHAPE_HULL_VERTICES}, so simplification always satisfies it.
     */
    public static final int MAX_HULL_VERTICES = 64;

    /**
     * Vertex budget for a shard's collision hull (D06-R6).
     *
     * <p>Half a part's, because there are up to {@code MAX_DEBRIS_BODIES} of them at once and a
     * shard's silhouette is never inspected the way a vehicle's is.
     */
    public static final int MAX_SHARD_HULL_VERTICES = 32;

    /**
     * The vertex count {@code btShapeHull} actually reduces to, whatever budget it is asked for.
     *
     * <p>Bullet samples support points along a fixed set of 42 unit-sphere directions and keeps the
     * unique ones; there is no target-count parameter, and {@code buildHull}'s only argument is the
     * (ignored) margin. So the runtime's simplification is a <em>ceiling</em> at this value, not a
     * mechanism for hitting an arbitrary budget: a part hull is comfortably under
     * {@link #MAX_HULL_VERTICES}, and a shard hull over {@link #MAX_SHARD_HULL_VERTICES} cannot be
     * brought under it here.
     *
     * <p>That is not a hole, because meeting the budget is the asset pipeline's job: the Blender tool
     * decimates shard hulls further than part hulls (D09-S5.5, D06-R6) and the harness's ASSET-011 is
     * a blocking check on the result. This class warns when a mesh reaches it out of budget, so a
     * pipeline regression is visible at load rather than as a frame-time drift.
     */
    public static final int BT_SHAPE_HULL_VERTICES = 42;

    private static final Comparator<ShapeCacheKey> KEY_ORDER = Comparator.comparing(ShapeCacheKey::assetId)
            .thenComparing(ShapeCacheKey::variant)
            .thenComparingInt(ShapeCacheKey::index);

    private final Map<ShapeCacheKey, btCollisionShape> shapes = new TreeMap<>(KEY_ORDER);

    /**
     * Live vehicle compounds by owning entity id. Sorted rather than hashed so teardown visits them
     * in a fixed order (G3), and keyed by entity rather than by assembly because two vehicles of the
     * same assembly have different compounds the moment either loses a part.
     */
    private final Map<Integer, VehicleCompound> vehicleCompounds = new TreeMap<>();

    private final Vector3 scratchVertex = new Vector3();
    private final Vector3 scratchMin = new Vector3();
    private final Vector3 scratchMax = new Vector3();

    private boolean disposed;

    /**
     * The hull for this key, building it from {@code mesh} on first request.
     *
     * <p>The key carries the variant as well as the asset id, which is finer than the
     * {@code (assetId, maxVertices)} of the D06-S5.2 pseudocode and strictly stronger: the vertex
     * budget is a function of the variant (D06-R6), so two keys that differ only in budget cannot
     * exist, and a part hull and a shard hull of the same asset can.
     *
     * @param mesh ignored when the key is already cached, so a caller may pass the mesh it has
     *     without checking first
     * @throws IllegalArgumentException if the key names a variant that is not a convex hull, or if
     *     the mesh is degenerate (zero extent on some axis) and would produce a shape with no volume
     */
    public btConvexHullShape hullFor(ShapeCacheKey key, MeshData mesh) {
        checkOpen();
        btCollisionShape cached = shapes.get(key);
        if (cached != null) {
            return (btConvexHullShape) cached;
        }
        int maxVertices =
                switch (key.variant()) {
                    case PART_HULL -> MAX_HULL_VERTICES;
                    case SHARD_HULL -> MAX_SHARD_HULL_VERTICES;
                        // A primitive is a box or a slab built from parameters rather than art —
                        // arena floors and walls (D04-S5.4). It goes through the hull path so that
                        // one thing owns every collision shape (G19), and it carries the part
                        // budget because a primitive with more than 64 hull vertices is not a
                        // primitive.
                    case PRIMITIVE -> MAX_HULL_VERTICES;
                    default -> throw new IllegalArgumentException(
                            "variant " + key.variant() + " is not a convex hull (D06-R6)");
                };

        btConvexHullShape shape = buildHull(key, mesh, maxVertices);
        shapes.put(key, shape);
        return shape;
    }

    /**
     * Builds the compound shape of one vehicle (D06-S5.3) and registers it against that vehicle.
     *
     * @param vehicleEntity the owning entity; also the disambiguator in the compound's cache key,
     *     because two vehicles of the same assembly diverge the moment either loses a part
     * @param assemblyId which assembly was spawned
     * @param children one entry per non-wheel part, in any order — {@code VehicleCompound} sorts
     *     them by slot path so child indices are a function of the assembly alone (G3)
     * @throws IllegalStateException if this entity already owns a compound
     */
    public VehicleCompound buildVehicleCompound(
            int vehicleEntity, AssetId assemblyId, List<VehicleCompound.Child> children) {
        checkOpen();
        if (vehicleCompounds.containsKey(vehicleEntity)) {
            throw new IllegalStateException("entity " + EntityId.toString(vehicleEntity)
                    + " already owns a vehicle compound; a rebuild must release the old one first");
        }
        VehicleCompound built = VehicleCompound.build(this, compoundKey(assemblyId, vehicleEntity), children);
        vehicleCompounds.put(vehicleEntity, built);
        return built;
    }

    /** The compound owned by a vehicle entity, or null if it has none. */
    public VehicleCompound vehicleCompound(int vehicleEntity) {
        return vehicleCompounds.get(vehicleEntity);
    }

    /**
     * Disposes a vehicle's compound, called when the vehicle is destroyed.
     *
     * <p>Compounds are the only entries that can be released individually. A part hull is shared by
     * every instance of that part type in the match, so "the last user of this hull is gone" is not
     * a question any caller can answer; hulls live until {@link #dispose()}.
     *
     * @return true if a compound was disposed
     */
    public boolean releaseVehicleCompound(int vehicleEntity) {
        if (disposed) {
            return false;
        }
        VehicleCompound compound = vehicleCompounds.remove(vehicleEntity);
        if (compound == null) {
            return false;
        }
        shapes.remove(compound.key());
        compound.compound().dispose();
        NativeResourceTracker.release("btCompoundShape");
        return true;
    }

    /**
     * Creates the empty {@code btCompoundShape} behind a {@link VehicleCompound}.
     *
     * <p>The dynamic AABB tree is enabled: a vehicle's children move relative to one another only at
     * a structural change, but a compound without the tree does a linear scan of its children for
     * every broadphase query, which is the wrong trade at 64 of them.
     */
    btCompoundShape newCompound(ShapeCacheKey key) {
        checkOpen();
        if (key.variant() != ShapeCacheKey.Variant.COMPOUND) {
            throw new IllegalArgumentException("compound key must use variant COMPOUND, got " + key.variant());
        }
        if (shapes.containsKey(key)) {
            throw new IllegalStateException(
                    "compound " + key + " already exists; per-vehicle compound keys must be " + "unique (D06-S5.3)");
        }
        btCompoundShape compound = new btCompoundShape(true);
        NativeResourceTracker.register("btCompoundShape");
        shapes.put(key, compound);
        return compound;
    }

    /** The cached shape for a key, or null. Does not build anything. */
    public btCollisionShape get(ShapeCacheKey key) {
        return shapes.get(key);
    }

    /** True when a shape is already cached under this key. */
    public boolean contains(ShapeCacheKey key) {
        return shapes.containsKey(key);
    }

    /** How many shapes the cache owns. */
    public int size() {
        return shapes.size();
    }

    // ---- Construction (D06-S5.2) -----------------------------------------------------

    private btConvexHullShape buildHull(ShapeCacheKey key, MeshData mesh, int maxVertices) {
        mesh.bounds(scratchMin, scratchMax);
        // The pseudocode's `assert shapeVolume(shape) > 0`. A hull whose points are coplanar has no
        // volume, gets a zero inertia component from calculateLocalInertia (D06-E13) and becomes
        // infinitely easy to spin. It is rejected here, at the one place every shape is built,
        // rather than diagnosed later from a body that behaves impossibly.
        if (scratchMax.x <= scratchMin.x || scratchMax.y <= scratchMin.y || scratchMax.z <= scratchMin.z) {
            throw new IllegalArgumentException("mesh for " + key + " is degenerate: extent ("
                    + (scratchMax.x - scratchMin.x) + ", " + (scratchMax.y - scratchMin.y) + ", "
                    + (scratchMax.z - scratchMin.z) + ") has no volume, so its hull would have none either "
                    + "(D06-S5.2)");
        }

        btConvexHullShape raw = new btConvexHullShape();
        NativeResourceTracker.register("btConvexHullShape");
        int vertexCount = mesh.vertexCount();
        for (int i = 0; i < vertexCount; i++) {
            mesh.vertex(i, scratchVertex);
            raw.addPoint(scratchVertex, i == vertexCount - 1);
        }
        // MANDATORY before simplification (D06-R13a). Also correct for the unsimplified path, which
        // sets the real margin below.
        raw.setMargin(0f);

        if (raw.getNumPoints() <= maxVertices) {
            raw.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
            return raw;
        }

        btShapeHull simplifier = new btShapeHull(raw);
        // The argument is ignored by Bullet (2.8x marks the parameter unused); zeroing the source's
        // margin above is what actually prevents the double-count (D06-R13a, DISC-008).
        simplifier.buildHull(0f);
        btConvexHullShape simplified = new btConvexHullShape(simplifier);
        NativeResourceTracker.register("btConvexHullShape");
        simplified.setMargin(PhysicsWorld.COLLISION_MARGIN_M);

        simplifier.dispose();
        // The intermediate is not cached: it is the full-resolution point set, which is precisely
        // what the budget exists to keep out of the broadphase.
        raw.dispose();
        NativeResourceTracker.release("btConvexHullShape");

        if (simplified.getNumPoints() > maxVertices) {
            LOG.warn(
                    "hull for {} has {} vertices after simplification, above its budget of {}. btShapeHull "
                            + "reduces to a fixed {} directions and no further, so meeting the budget is the asset "
                            + "pipeline's job (D09-S5.5, ASSET-011)",
                    key,
                    simplified.getNumPoints(),
                    maxVertices,
                    BT_SHAPE_HULL_VERTICES);
        }
        return simplified;
    }

    // ---- Teardown (D02-S5.7 rule 5) --------------------------------------------------

    @Override
    public void close() {
        dispose();
    }

    /**
     * Disposes every shape.
     *
     * <p>Runs <em>after</em> every body has been destroyed (D03-S5.6, D02-S5.7 rule 5). Disposing a
     * shape a live body still references is a use-after-free that Bullet reports as a segfault
     * several steps later, in a stack trace that names neither this class nor the body.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        vehicleCompounds.clear();
        for (Map.Entry<ShapeCacheKey, btCollisionShape> entry : shapes.entrySet()) {
            entry.getValue().dispose();
            NativeResourceTracker.release(
                    entry.getKey().variant() == ShapeCacheKey.Variant.COMPOUND
                            ? "btCompoundShape"
                            : "btConvexHullShape");
        }
        shapes.clear();
    }

    /** True once {@link #dispose()} has run. */
    public boolean isDisposed() {
        return disposed;
    }

    private void checkOpen() {
        if (disposed) {
            throw new IllegalStateException("shape cache is disposed");
        }
    }

    /** The instance-scoped compound key for one vehicle (D06-S5.3). */
    public static ShapeCacheKey compoundKey(AssetId assemblyId, int vehicleEntity) {
        return new ShapeCacheKey(assemblyId, ShapeCacheKey.Variant.COMPOUND, EntityId.index(vehicleEntity));
    }
}
