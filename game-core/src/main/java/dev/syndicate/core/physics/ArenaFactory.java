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
import dev.syndicate.core.arena.TerrainField;
import dev.syndicate.core.arena.TerrainGenerator;
import dev.syndicate.core.arena.TerrainParams;
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
 * <p><b>Two kinds of arena.</b> An arena that declares a {@code terrain} block gets generated ground:
 * one height field body, and no walls at all, because the border rise is the boundary (D16-S5.5).
 * One that does not gets the floor and four box walls it always got — which stays a legal arena
 * rather than a deprecated one (D16-R4), and is what every physics regression fixture is.
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
    public static LoadedArena load(World world, PhysicsWorld physics, ShapeCache shapes, ArenaDef arena) {
        if (arena == null) {
            LOG.warn("no arena to load; the world has no ground and nothing will stay in it");
            return new LoadedArena(List.of(), null);
        }
        if (arena.hasTerrain()) {
            return loadTerrain(world, physics, shapes, arena);
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
        return new LoadedArena(List.copyOf(entities), null);
    }

    /**
     * What loading an arena produced.
     *
     * <p>The terrain comes back rather than being stashed on a component, because at this stage the
     * only things that want it are the caller's own — the renderer, which builds its mesh from the
     * same grids the collision was built from (D16-R63). When per-surface grip lands and the tick
     * loop needs it too, that is the moment to decide between a component and a field on the world,
     * and not before.
     *
     * @param entities the static bodies created, floor or terrain first
     * @param terrain the generated ground, or null for a flat arena
     */
    public record LoadedArena(List<Integer> entities, TerrainField terrain) {

        public LoadedArena {
            entities = List.copyOf(entities);
        }
    }

    /**
     * How many seeds a match-seeded arena may try before giving up.
     *
     * <p>A rejection costs one generation — 187 ms on the 600 m desert — so a handful of attempts is
     * imperceptible at load and a run of eight failures means the theme's own numbers are wrong
     * rather than the seed being unlucky.
     */
    public static final int MAX_SEED_ATTEMPTS = 8;

    /**
     * Generates ground that is actually playable, rejecting seeds that are not (D16-R58, A411).
     *
     * <p>D16-R27 says an arena a player cannot get around is broken rather than difficult, and that
     * it must be caught at load rather than by whoever spawns in the sealed bowl. That was a hard
     * failure while every arena declared a fixed, hand-checked seed. It cannot stay one now that a
     * theme generates a <b>new landscape every match</b>: some fraction of seeds partition the map,
     * and the first player to draw one would get a crash on the loading screen.
     *
     * <p>So an unplayable field is <em>discarded and re-seeded</em> rather than thrown. The check
     * itself does not soften — nothing unplayable ever reaches a player — but the response to it
     * becomes "try another one", which is the only response compatible with generating maps rather
     * than authoring them.
     *
     * <p>An arena with a declared seed still fails hard on the first attempt, because there is no
     * other seed to move to and silently substituting one would mean an authored map quietly
     * becoming a different map.
     */
    private static TerrainField generatePlayable(
            World world, ArenaDef arena, List<TerrainGenerator.Pad> pads, List<Vector3> spawnPositions) {

        boolean matchSeeded = arena.terrain().seed() == 0L;
        int attempts = matchSeeded ? MAX_SEED_ATTEMPTS : 1;
        String lastFinding = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            TerrainParams params = seedFor(arena.terrain(), world, arena.arenaId(), attempt);
            TerrainField field =
                    TerrainGenerator.generate(arena.boundsMin(), arena.boundsMax(), arena.groundY(), params, pads);
            lastFinding = TerrainGenerator.playabilityFinding(field, spawnPositions);
            if (lastFinding == null) {
                if (attempt > 0) {
                    LOG.info(
                            "arena {} accepted seed {} on attempt {} of {}",
                            arena.arenaId().value(),
                            params.seed(),
                            attempt + 1,
                            attempts);
                }
                return field;
            }
            LOG.info("arena {} rejected seed {}: {}", arena.arenaId().value(), params.seed(), lastFinding);
        }

        throw new IllegalStateException("arena " + arena.arenaId().value() + ": " + lastFinding + " — after " + attempts
                + " seed" + (attempts == 1 ? "" : "s") + " (D16-R58, A411)");
    }

    /**
     * The terrain seed this match generates from.
     *
     * <p>A declared non-zero seed is honoured exactly: that arena is the same landscape every time,
     * which is what a regression fixture and a hand-tuned map both need.
     *
     * <p><b>Zero means "a new one every match."</b> The seed is then derived from the match seed and
     * the arena's id, so a theme becomes a generator of maps rather than one map — you fight in a
     * scrapyard, not in <em>the</em> scrapyard. Deriving from the match seed rather than from the
     * clock is what keeps it legal under G4: the match seed is shared by every peer and replayed by
     * the offline runner, so authority and client generate the identical ground, and a match can be
     * reproduced from its seed alone.
     *
     * <p>The arena id is mixed in so that two arenas in one match — a future best-of-three — do not
     * come out as the same landscape wearing two names, and the attempt number so that a rejected
     * seed's replacement is a different landscape rather than the same one again.
     */
    private static TerrainParams seedFor(TerrainParams declared, World world, AssetId arenaId, int attempt) {
        if (declared.seed() != 0L) {
            return declared;
        }
        long mixed = world.random().matchSeed() * 0x9E3779B97F4A7C15L
                + arenaId.value().hashCode()
                + attempt * 0x632BE59BD9B4E019L;
        // A final avalanche so neighbouring match seeds do not produce visibly related landscapes.
        mixed ^= mixed >>> 29;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 32;
        // Never zero, or "derive it" would be indistinguishable from the derived value next time.
        long seed = mixed == 0L ? 1L : mixed;
        LOG.info("arena {} generates from match-derived seed {}", arenaId.value(), seed);
        return new TerrainParams(
                seed,
                declared.cellSizeM(),
                declared.gridSize(),
                declared.theme(),
                declared.reliefM(),
                declared.baseFrequency(),
                declared.octaves(),
                declared.featureBearingDeg(),
                declared.featureWavelengthM(),
                declared.featureHeightM(),
                declared.borderWidthM(),
                declared.borderRiseM(),
                declared.maxDrivableSlopeDeg());
    }

    /**
     * Generates an arena's ground and puts one height field body in the world (D16-S5.8).
     *
     * <p>No walls. The border rise (D16-S5.5) is the boundary, and it is a soft one on purpose: a
     * car at speed gets part way up the rim and slides back, rather than stopping dead against
     * something it cannot see. The kill plane stays as the hard backstop for anything that leaves
     * anyway.
     */
    private static LoadedArena loadTerrain(World world, PhysicsWorld physics, ShapeCache shapes, ArenaDef arena) {
        // Every spawn point gets a levelled pad (D16-S9 E2). Without them a spawn on a dune's slip
        // face is a car dropped onto a wall it cannot climb, and which of the twelve points that
        // happens to is a property of the seed — so it would be found by playing rather than by
        // loading.
        List<TerrainGenerator.Pad> pads = new ArrayList<>();
        List<Vector3> spawnPositions = new ArrayList<>();
        for (ArenaDef.SpawnPoint point : arena.spawnPoints()) {
            spawnPositions.add(point.position());
            pads.add(new TerrainGenerator.Pad(point.position().x, point.position().z, point.clearanceRadiusM()));
        }

        TerrainField field = generatePlayable(world, arena, pads, spawnPositions);

        Entity entity = world.createEntity();
        int entityId = entity.id();
        ShapeCacheKey key = new ShapeCacheKey(arena.arenaId(), ShapeCacheKey.Variant.HEIGHTFIELD, 0);
        btCollisionShape shape = shapes.heightFieldFor(key, field);

        // Not groundY: Bullet centres a height field on the midpoint of its own height range
        // (D16-R48). Placing it at the datum offsets the collision from the drawn surface by half
        // the arena's relief, which looks exactly like a rendering bug and is not one.
        Matrix4 transform = new Matrix4()
                .setToTranslation(
                        (arena.boundsMin().x + arena.boundsMax().x) * 0.5f,
                        ShapeCache.heightFieldOriginY(field),
                        (arena.boundsMin().z + arena.boundsMax().z) * 0.5f);
        addStaticBody(world, physics, entityId, shape, key, transform);

        LOG.info(
                "arena {} loaded: {}x{} terrain, relief {}..{} m above y={}, {}% drivable, {} spawn points",
                arena.arenaId().value(),
                field.params().gridSize(),
                field.params().gridSize(),
                String.format("%.1f", field.minHeight()),
                String.format("%.1f", field.maxHeight()),
                arena.groundY(),
                Math.round(field.drivableFraction() * 100f),
                arena.spawnPoints().size());
        return new LoadedArena(List.of(entityId), field);
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
