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
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.util.RandomVectors;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates debris bodies and holds the global debris budget
 * (docs/06_physics_simulation.md#D06-S5.10, docs/07_damage_destruction_model.md#D07-S5.8).
 *
 * <p>Shared by every system that turns something into scrap — {@code FractureSystem} (13),
 * {@code DetachSystem} (14) and, later, the wreck path — because the budget is global. A per-system
 * cap would let two systems each stay under 256 and put 512 bodies in the world between them.
 *
 * <p><b>The cap is hard, and it recycles rather than refuses</b> (D06-R28). A fracture that hit the
 * cap and dropped its remaining shards would leave a visibly destroyed part with only half its
 * pieces on the ground, contradicting what the player just watched (D07-R20). Evicting the oldest
 * debris instead keeps the newest, most gameplay-relevant destruction complete.
 *
 * <p><b>Native ownership (G19).</b> Bodies and motion states created here are owned by the
 * {@code RigidBodyComponent} they are attached to and released by {@code EntityDestroySystem}
 * (slot 27). Shapes belong to {@link ShapeCache} and are never disposed from a debris path.
 */
public final class DebrisFactory {

    private static final Logger LOG = LoggerFactory.getLogger(DebrisFactory.class);

    /**
     * Metres per second. Debris velocity is clamped to this at spawn (D06-R30, D14-S6.4).
     *
     * <p>Above roughly this speed a shard leaves the play space inside a frame and reads as a
     * glitch; it is also the classic signature of a solver blow-up from shards spawned
     * interpenetrating.
     */
    public static final float MAX_SCATTER_SPEED_MPS = 50f;

    /** Radians per second. Angular velocity is clamped to this at spawn (D06-S5.10). */
    public static final float MAX_ANGULAR_SPEED_RADPS = 30f;

    /** Seconds a debris body may sleep before it is despawned early (D06-R29). */
    public static final float SLEEP_DESPAWN_S = 3.0f;

    private static final float DEBRIS_FRICTION = 0.5f;
    private static final float DEBRIS_RESTITUTION = 0.15f;
    private static final float DEBRIS_LINEAR_DAMPING = 0.10f;
    private static final float DEBRIS_ANGULAR_DAMPING = 0.20f;

    private final PhysicsWorld physics;

    private Family debris;

    private final Vector3 scratchInertia = new Vector3();
    private final Vector3 scratchLinear = new Vector3();
    private final Vector3 scratchAngular = new Vector3();
    private final Vector3 scratchPosition = new Vector3();

    public DebrisFactory(PhysicsWorld physics) {
        this.physics = Objects.requireNonNull(physics, "physics");
    }

    /**
     * Caches the debris family. Called from the owning system's {@code initialize}, because
     * {@code World.family} is a registration and must not run per tick.
     */
    public void initialize(World world) {
        if (debris == null) {
            debris = world.family(ComponentQuery.all(DebrisTagComponent.class));
        }
    }

    /** How many debris entities are live right now. */
    public int debrisCount() {
        return debris == null ? 0 : debris.size();
    }

    /**
     * Spawns one debris body (D06-S5.10).
     *
     * @param shapeKey which cached shape the body uses; the cache keeps owning it
     * @param shape the shape itself, already in the cache
     * @param massKg the body's mass; must be at least {@code MIN_BODY_MASS_KG}
     * @param worldTransform where it starts
     * @param linearVelocity inherited plus scatter, clamped to {@link #MAX_SCATTER_SPEED_MPS}
     * @param angularVelocity clamped to {@link #MAX_ANGULAR_SPEED_RADPS}
     * @param lifetimeSeconds how long before {@code LifetimeSystem} despawns it
     * @param sourcePartEntity the part it came from, for telemetry; usually already destroyed
     * @return the new entity id
     * @throws IllegalArgumentException if the mass is below {@code MIN_BODY_MASS_KG} — never
     *     clamped, because a clamped mass silently violates G7 (D06-E1)
     */
    public int spawnDebris(
            World world,
            ShapeCacheKey shapeKey,
            btCollisionShape shape,
            float massKg,
            Matrix4 worldTransform,
            Vector3 linearVelocity,
            Vector3 angularVelocity,
            float lifetimeSeconds,
            int sourcePartEntity) {

        if (massKg < SimulationConstants.MIN_BODY_MASS_KG) {
            throw new IllegalArgumentException("debris mass " + massKg + " kg is below MIN_BODY_MASS_KG; clamping it "
                    + "would silently violate mass conservation (D06-E1, G7)");
        }
        enforceBudget(world);

        shape.calculateLocalInertia(massKg, scratchInertia);
        btDefaultMotionState motionState = new btDefaultMotionState(worldTransform);
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(massKg, motionState, shape, scratchInertia);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();

        body.setFriction(DEBRIS_FRICTION);
        body.setRestitution(DEBRIS_RESTITUTION);
        body.setDamping(DEBRIS_LINEAR_DAMPING, DEBRIS_ANGULAR_DAMPING);
        // Debris sleeps, unlike a vehicle chassis (D06-R4): a settled pile of scrap costs broadphase
        // work and contributes nothing, and R29 despawns it once it has slept.
        body.setActivationState(1 /* ACTIVE_TAG */);

        scratchLinear.set(linearVelocity);
        RandomVectors.clampMagnitude(scratchLinear, MAX_SCATTER_SPEED_MPS);
        scratchAngular.set(angularVelocity);
        RandomVectors.clampMagnitude(scratchAngular, MAX_ANGULAR_SPEED_RADPS);
        body.setLinearVelocity(scratchLinear);
        body.setAngularVelocity(scratchAngular);

        // CCD is deliberately off (D06-R5). Hundreds of CCD bodies is a large cost and a shard that
        // tunnels through the ground is cosmetically irrelevant — the despawn budget catches it.
        physics.addBody(body, CollisionLayer.DEBRIS);

        Entity entity = world.createEntity();
        int entityId = entity.id();

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.body = body;
        rigidBody.motionState = motionState;
        rigidBody.shapeKey = shapeKey;
        rigidBody.massKg = massKg;
        rigidBody.localInertia.set(scratchInertia);
        rigidBody.layer = CollisionLayer.DEBRIS;
        rigidBody.mask = CollisionLayer.DEBRIS.mask();
        world.addComponent(entityId, rigidBody);

        TransformComponent transform = new TransformComponent();
        worldTransform.getTranslation(scratchPosition);
        transform.position.set(scratchPosition);
        worldTransform.getRotation(transform.rotation, true);
        world.addComponent(entityId, transform);

        VelocityComponent velocity = new VelocityComponent();
        velocity.linear.set(scratchLinear);
        velocity.angular.set(scratchAngular);
        world.addComponent(entityId, velocity);

        LifetimeComponent lifetime = new LifetimeComponent();
        lifetime.remainingS = lifetimeSeconds;
        lifetime.despawnPolicy = LifetimeComponent.DespawnPolicy.SLEEP_THEN_DESTROY;
        world.addComponent(entityId, lifetime);

        DebrisTagComponent tag = new DebrisTagComponent();
        tag.sourcePartEntity = sourcePartEntity;
        tag.spawnTick = world.currentTick();
        world.addComponent(entityId, tag);

        return entityId;
    }

    /**
     * Recycles the oldest debris until there is room for one more (D06-R28).
     *
     * <p>{@code destroyEntity} deactivates immediately and defers teardown to slot 27, and a
     * deactivated entity leaves its family in the same call — so the family size this reads is
     * already the post-eviction count and the loop terminates. The native body survives to the end
     * of the tick, which is correct: disposing it here would be disposal during a system's update,
     * which D04-E5 exists to prevent.
     */
    private void enforceBudget(World world) {
        if (debris == null) {
            return;
        }
        int guard = 0;
        while (debris.size() >= SimulationConstants.MAX_DEBRIS_BODIES) {
            int oldest = oldestDebris(world);
            if (oldest == EntityId.NULL || ++guard > SimulationConstants.MAX_DEBRIS_BODIES) {
                LOG.error(
                        "debris budget is full at {} bodies but no entity could be recycled; "
                                + "spawning anyway rather than dropping a shard (D07-R20)",
                        debris.size());
                return;
            }
            world.destroyEntity(oldest);
        }
    }

    /**
     * The debris entity with the lowest spawn tick, ties broken by ascending entity id.
     *
     * <p>The tie-break matters: many shards of one fracture share a spawn tick, and "whichever the
     * iteration happened to reach first" would make eviction depend on family order history rather
     * than on the world's state (G3).
     */
    private int oldestDebris(World world) {
        int[] ids = debris.snapshot();
        int count = debris.size();
        int oldest = EntityId.NULL;
        long oldestTick = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            DebrisTagComponent tag = world.getComponent(ids[i], DebrisTagComponent.class);
            if (tag != null && tag.spawnTick < oldestTick) {
                oldestTick = tag.spawnTick;
                oldest = ids[i];
            }
        }
        return oldest;
    }
}
