/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.ArenaDef;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.StaticCollisionComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts an arena's static geometry into the world (docs/04_entity_component_model.md#D04-S5.4).
 *
 * <p>A floor and four walls, built from the arena's own bounds. That is the whole of it, and it is
 * deliberately the whole of it: the point of an arena at this stage is that a vehicle has something
 * to drive on, something to crash into, and an edge it can be pushed over — the three things every
 * other system has been simulating against a test fixture's ground box.
 *
 * <p><b>The collision is generated rather than loaded</b> (DEV-014). D08-S4.7 gives an arena a
 * {@code collision.glb}, and loading one needs a concave triangle-mesh shape with its own native
 * ownership rules, which no shape in {@link ShapeCache} is. Generating a plane floor and box walls
 * from {@code boundsMin}/{@code boundsMax} produces a playable, exactly-specified arena from numbers
 * that are already in the file, and leaves the mesh path for the session that has an arena mesh to
 * load. The floor is a plane rather than a box for a reason that outlives the placeholder: a
 * ray-cast wheel finds the ground with a ray, and Bullet's convex ray test is imprecise on shapes
 * this large (DISC-017).
 *
 * <p><b>Native ownership (G19).</b> Each body belongs to the {@code RigidBodyComponent} of its own
 * entity and is disposed by {@code EntityDestroySystem} (27); the shapes belong to
 * {@link ShapeCache}. One entity per body rather than one entity with five, because
 * {@code RigidBodyComponent} holds exactly one body and a component that held a list of them would
 * need its own teardown path beside the one that already works.
 */
public final class ArenaFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ArenaFactory.class);

    /** Metres. How thick the generated floor and walls are. */
    public static final float SURFACE_THICKNESS_M = 1.0f;

    /**
     * Friction of the arena floor.
     *
     * <p>0.9 is what the test scenes' ground has used since the ray-cast wheel was written, and the
     * shipped vehicles' braking and cornering were calibrated against it (DEC-034). Changing it here
     * would silently invalidate every figure in {@code VEHICLES.md}.
     */
    public static final float SURFACE_FRICTION = 0.9f;

    /** Restitution of the arena floor. Zero: a car that lands should stay landed. */
    public static final float SURFACE_RESTITUTION = 0f;

    private ArenaFactory() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds an arena's static bodies and returns the entities holding them.
     *
     * @return the created entity ids, floor first, in the order the surfaces were built
     */
    public static List<Integer> load(World world, PhysicsWorld physics, ShapeCache shapes, ArenaDef arena) {
        if (arena == null) {
            LOG.warn("no arena to load; the world has no ground and nothing will stay in it");
            return List.of();
        }
        Vector3 min = arena.boundsMin();
        Vector3 max = arena.boundsMax();
        float halfX = Math.max(0.5f, (max.x - min.x) * 0.5f);
        float halfZ = Math.max(0.5f, (max.z - min.z) * 0.5f);
        float centreX = (min.x + max.x) * 0.5f;
        float centreZ = (min.z + max.z) * 0.5f;
        float wallHalfHeight = Math.max(1f, (max.y - arena.groundY()) * 0.5f);
        float wallCentreY = arena.groundY() + wallHalfHeight;

        List<Integer> entities = new ArrayList<>();
        entities.add(floor(world, physics, shapes, arena));

        // Four walls, inside the bounds by half their thickness so their inner faces are the bounds.
        entities.add(surface(
                world,
                physics,
                shapes,
                arena.arenaId(),
                1,
                new Vector3(SURFACE_THICKNESS_M, wallHalfHeight, halfZ),
                new Vector3(min.x - SURFACE_THICKNESS_M, wallCentreY, centreZ)));
        entities.add(surface(
                world,
                physics,
                shapes,
                arena.arenaId(),
                2,
                new Vector3(SURFACE_THICKNESS_M, wallHalfHeight, halfZ),
                new Vector3(max.x + SURFACE_THICKNESS_M, wallCentreY, centreZ)));
        entities.add(surface(
                world,
                physics,
                shapes,
                arena.arenaId(),
                3,
                new Vector3(halfX, wallHalfHeight, SURFACE_THICKNESS_M),
                new Vector3(centreX, wallCentreY, min.z - SURFACE_THICKNESS_M)));
        entities.add(surface(
                world,
                physics,
                shapes,
                arena.arenaId(),
                4,
                new Vector3(halfX, wallHalfHeight, SURFACE_THICKNESS_M),
                new Vector3(centreX, wallCentreY, max.z + SURFACE_THICKNESS_M)));

        LOG.info(
                "arena {} loaded: floor at y={}, bounds {}..{}, {} spawn points",
                arena.arenaId().value(),
                arena.groundY(),
                min,
                max,
                arena.spawnPoints().size());
        return List.copyOf(entities);
    }

    /**
     * The floor: an entity, a cached infinite plane at {@code groundY}, a zero-mass static body.
     *
     * <p>A plane rather than a box because the floor is the one static surface a ray-cast wheel
     * casts against sixty times a second, and Bullet's convex ray test is not accurate on a shape
     * hundreds of metres across (DISC-017). Infinite is not a limitation here — the walls contain
     * the arena in plan, and a vehicle that leaves through a gap in them was already outside the
     * bounds {@code ArenaDef} checks.
     */
    private static int floor(World world, PhysicsWorld physics, ShapeCache shapes, ArenaDef arena) {
        Entity entity = world.createEntity();
        int entityId = entity.id();

        ShapeCacheKey key = new ShapeCacheKey(arena.arenaId(), ShapeCacheKey.Variant.PRIMITIVE, 0);
        btCollisionShape shape = shapes.planeFor(key, new Vector3(0f, 1f, 0f), arena.groundY());
        addStaticBody(world, physics, entityId, shape, key, new Matrix4());
        return entityId;
    }

    /**
     * One static box: an entity, a cached hull, a zero-mass body on the {@code STATIC} layer.
     *
     * <p>A convex hull over a box's eight corners rather than a {@code btBoxShape}, so the shape goes
     * through {@link ShapeCache} and is disposed by the one thing that owns collision shapes. The two
     * are the same surface to within the collision margin both carry.
     */
    private static int surface(
            World world,
            PhysicsWorld physics,
            ShapeCache shapes,
            AssetId arenaId,
            int index,
            Vector3 halfExtents,
            Vector3 centreWorld) {

        Entity entity = world.createEntity();
        int entityId = entity.id();

        ShapeCacheKey key = new ShapeCacheKey(arenaId, ShapeCacheKey.Variant.PRIMITIVE, index);
        btCollisionShape shape = shapes.hullFor(key, boxMesh(halfExtents));
        addStaticBody(world, physics, entityId, shape, key, new Matrix4().setToTranslation(centreWorld));
        return entityId;
    }

    /** The body, components and physics-world membership every static surface gets. */
    private static void addStaticBody(
            World world,
            PhysicsWorld physics,
            int entityId,
            btCollisionShape shape,
            ShapeCacheKey key,
            Matrix4 transform) {

        btDefaultMotionState motionState = new btDefaultMotionState(transform);
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(0f, motionState, shape, Vector3.Zero);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();
        body.setFriction(SURFACE_FRICTION);
        body.setRestitution(SURFACE_RESTITUTION);
        physics.addBody(body, CollisionLayer.STATIC);

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.body = body;
        rigidBody.motionState = motionState;
        rigidBody.shapeKey = key;
        rigidBody.massKg = 0f;
        rigidBody.layer = CollisionLayer.STATIC;
        rigidBody.mask = CollisionLayer.STATIC.mask();
        world.addComponent(entityId, rigidBody);

        StaticCollisionComponent staticCollision = new StaticCollisionComponent();
        staticCollision.shapes.add(key);
        world.addComponent(entityId, staticCollision);

        TransformComponent transformComponent = new TransformComponent();
        transform.getTranslation(transformComponent.position);
        world.addComponent(entityId, transformComponent);
    }

    /** The eight corners of a box — the smallest mesh whose convex hull is exactly that box. */
    private static MeshData boxMesh(Vector3 halfExtents) {
        float x = halfExtents.x;
        float y = halfExtents.y;
        float z = halfExtents.z;
        return new MeshData(
                new float[] {-x, -y, -z, x, -y, -z, x, y, -z, -x, y, -z, -x, -y, z, x, -y, z, x, y, z, -x, y, z});
    }

    /** Whether a point has fallen below the arena's kill plane (D01-E3). */
    public static boolean isBelowKillPlane(ArenaDef arena, Vector3 pointWorld) {
        return arena != null && pointWorld.y < arena.killPlaneY();
    }

    /** A spawn point's world transform, or null when the arena declares none for that team. */
    public static Matrix4 spawnTransform(ArenaDef arena, int teamId, int index, Matrix4 out) {
        if (arena == null) {
            return null;
        }
        List<ArenaDef.SpawnPoint> points = arena.spawnPointsFor(teamId);
        if (points.isEmpty()) {
            return null;
        }
        ArenaDef.SpawnPoint point = points.get(Math.floorMod(index, points.size()));
        return out.setToRotation(Vector3.Y, point.yawDeg()).setTranslation(point.position());
    }
}
