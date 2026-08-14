/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btCompoundShape;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import com.badlogic.gdx.physics.bullet.collision.btHeightfieldTerrainShape;
import com.badlogic.gdx.physics.bullet.collision.btShapeHull;
import com.badlogic.gdx.physics.bullet.collision.btStaticPlaneShape;
import dev.syndicate.core.arena.TerrainField;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
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

    /** Bullet's up-axis index for Y, which is the game's up (D00-R16). */
    private static final int UP_AXIS_Y = 1;

    private static final Comparator<ShapeCacheKey> KEY_ORDER = Comparator.comparing(ShapeCacheKey::assetId)
            .thenComparing(ShapeCacheKey::variant)
            .thenComparingInt(ShapeCacheKey::index);

    private final Map<ShapeCacheKey, btCollisionShape> shapes = new TreeMap<>(KEY_ORDER);

    /**
     * The Bullet class name each entry was registered under, so {@link #dispose()} releases the same
     * name {@link NativeResourceTracker} was told about. Derived from the variant until a variant
     * could hold more than one native type — {@code PRIMITIVE} is a hull or a plane depending on
     * which factory built it, and a tracker that guessed would report a leak of one type and a
     * double-free of another.
     */
    private final Map<ShapeCacheKey, String> nativeKinds = new TreeMap<>(KEY_ORDER);

    /**
     * The direct buffers height field shapes are reading through (D16-R47).
     *
     * <p>Held for one reason only: to keep them reachable. Bullet borrows the pointer rather than
     * copying the data, so dropping the last Java reference to one of these while its shape is alive
     * is a use-after-free. They are cleared in {@link #dispose()}, after the shapes are gone.
     */
    private final Map<ShapeCacheKey, FloatBuffer> heightBuffers = new TreeMap<>(KEY_ORDER);

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
        nativeKinds.put(key, "btConvexHullShape");
        return shape;
    }

    /**
     * The infinite plane for this key, building it on first request.
     *
     * <p>Reserved for ground: a {@code btStaticPlaneShape} has no extent to be wrong about, and
     * Bullet ray-tests it by intersecting the ray with the plane rather than by iterating a convex
     * cast to a tolerance. That distinction is the whole reason this method exists. A ray-cast
     * wheel finds the road by casting a 0.65 m ray straight down; against a convex box 800 m long
     * the subsimplex cast that {@code btCollisionWorld::rayTest} uses returns a hit point up to
     * 14 cm off, per wheel, per tick, at random — the car's body stays put and its wheels strobe
     * through the arches (DISC-017). Against a plane the same cast is exact to the last decimal.
     *
     * @param normal the plane's upward normal in world space; need not be normalised
     * @param constantM the plane's signed distance from the origin along {@code normal} — the
     *     ground's Y for a level floor
     * @throws IllegalArgumentException if the key does not name a {@code PRIMITIVE}
     */
    public btStaticPlaneShape planeFor(ShapeCacheKey key, Vector3 normal, float constantM) {
        checkOpen();
        btCollisionShape cached = shapes.get(key);
        if (cached != null) {
            return (btStaticPlaneShape) cached;
        }
        if (key.variant() != ShapeCacheKey.Variant.PRIMITIVE) {
            throw new IllegalArgumentException("a plane is a primitive, not " + key.variant() + " (D06-R6)");
        }
        btStaticPlaneShape plane = new btStaticPlaneShape(normal, constantM);
        NativeResourceTracker.register("btStaticPlaneShape");
        // A plane's margin is a skin the solver pushes contacts out by. It carries the same one as
        // every other shape here (D06-R13) so a body resting on the ground sits at the same height
        // whether the ground is a plane or a hull.
        plane.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        shapes.put(key, plane);
        nativeKinds.put(key, "btStaticPlaneShape");
        return plane;
    }

    /**
     * The height field shape for an arena's generated ground (D16-S5.8), building it on first request.
     *
     * <p><b>This shape borrows its height data; it does not copy it</b> (D16-R47). Bullet keeps a raw
     * pointer to the buffer it is handed and reads through it on every collision and every ray for the
     * rest of the shape's life. A heap {@code float[]}, or a non-direct buffer, or a direct buffer
     * nothing holds a reference to, will be moved or collected and Bullet will then be reading freed
     * memory — a crash with no frame of ours in the stack, at an arbitrary later tick. So the buffer is
     * allocated direct here, held in {@link #heightBuffers} for exactly as long as the shape, and
     * released with it. It is the first native object in this project whose <em>input</em> has to
     * outlive the call that created it.
     *
     * <p><b>Bullet spaces samples one unit apart</b>, so the cell size arrives as a local scaling
     * rather than as a constructor argument. Nothing in the height field's own API takes a spacing.
     *
     * @param key must name a {@link ShapeCacheKey.Variant#HEIGHTFIELD}
     * @param field the generated ground; its {@code heights()} are copied once into native-visible
     *     memory, after which the two are independent
     * @return the shape, whose local origin is the <em>midpoint</em> of the field's height range —
     *     see {@link #heightFieldOriginY} for the offset a caller must place it at
     */
    public btHeightfieldTerrainShape heightFieldFor(ShapeCacheKey key, TerrainField field) {
        checkOpen();
        btCollisionShape cached = shapes.get(key);
        if (cached != null) {
            return (btHeightfieldTerrainShape) cached;
        }
        if (key.variant() != ShapeCacheKey.Variant.HEIGHTFIELD) {
            throw new IllegalArgumentException("a height field is not a " + key.variant() + " (D16-R46)");
        }

        float[] heights = field.heights();
        FloatBuffer buffer = ByteBuffer.allocateDirect(heights.length * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(heights);
        buffer.rewind();

        int grid = field.params().gridSize();
        btHeightfieldTerrainShape shape = new btHeightfieldTerrainShape(
                grid,
                grid,
                buffer,
                // Ignored for float data — Bullet applies it only when decoding integer heights —
                // but it must still be 1 rather than 0, because a zero scale collapses the AABB.
                1f,
                field.minHeight(),
                field.maxHeight(),
                UP_AXIS_Y,
                false);
        NativeResourceTracker.register("btHeightfieldTerrainShape");

        // Bullet's default triangulation splits every quad on the same diagonal, which gives the
        // surface a directional bias: a vehicle crossing the grid one way rides differently from one
        // crossing it the other, on ground that is supposed to be isotropic (D16-R49).
        shape.setUseDiamondSubdivision(true);
        shape.setLocalScaling(
                new Vector3(field.params().cellSizeM(), 1f, field.params().cellSizeM()));
        shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);

        shapes.put(key, shape);
        nativeKinds.put(key, "btHeightfieldTerrainShape");
        heightBuffers.put(key, buffer);
        return shape;
    }

    /**
     * The world Y a height field body must be placed at, for its ground to be where the field says.
     *
     * <p><b>Bullet centres a height field on its own bounding box</b> (D16-R48): the shape's local
     * origin sits at the midpoint of {@code (minHeight, maxHeight)}, not at zero and not at
     * {@code minHeight}. Placing the body at {@code groundY} instead of here offsets the collision
     * from the drawn surface by half the arena's relief — which looks exactly like a rendering bug,
     * and is not one. This method exists so that the offset is computed in one place and cited from
     * the one caller rather than rederived.
     */
    public static float heightFieldOriginY(TerrainField field) {
        return field.groundY() + (field.minHeight() + field.maxHeight()) * 0.5f;
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
        nativeKinds.remove(compound.key());
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
        nativeKinds.put(key, "btCompoundShape");
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
            NativeResourceTracker.release(nativeKinds.getOrDefault(entry.getKey(), "btConvexHullShape"));
        }
        shapes.clear();
        nativeKinds.clear();
        // After the shapes, never before: while a height field shape is alive it is reading through
        // one of these, and releasing the buffer first is exactly the use-after-free the map exists
        // to prevent (D16-R47).
        heightBuffers.clear();
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
