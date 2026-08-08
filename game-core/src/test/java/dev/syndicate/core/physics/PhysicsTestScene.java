/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.system.PhysicsSystem;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * A world, a physics world, and a {@code PhysicsSystem} wired together, for tests that need a real
 * Bullet step (docs/12_testing_validation_ci.md#D12-S4.1 level L2/L3).
 *
 * <p>It stands in for the spawn path that does not exist yet: {@code SpawnSystem} (slot 5) and the
 * shape cache will eventually build bodies, and when they do this class shrinks to a call into them.
 * Until then it allocates bodies the way D06-S4.2 says to and disposes them in the D02-S5.7 rule 5
 * order, so a test that leaks is a test failure rather than a native crash three tests later.
 */
public final class PhysicsTestScene implements AutoCloseable {

    static {
        // D02-R3 puts Bullet.init() in each executable's bootstrap. A test process has no bootstrap,
        // so it initialises here — with the same useRefCounting=false the bootstrap uses, because
        // ownership is manual and explicit (G19).
        Bullet.init(false);
    }

    private final World world;
    private final PhysicsWorld physics;
    private final PhysicsSystem physicsSystem;

    private final List<btRigidBody> bodies = new ArrayList<>();
    private final List<btDefaultMotionState> motionStates = new ArrayList<>();
    private final List<btCollisionShape> shapes = new ArrayList<>();

    private long tick;

    public PhysicsTestScene(long matchSeed) {
        this.world = new World(matchSeed, true);
        this.physics = PhysicsWorld.create();
        this.physicsSystem = new PhysicsSystem(physics);
        world.registerSystems(List.<EntitySystem>of(physicsSystem));
    }

    public World world() {
        return world;
    }

    public PhysicsWorld physics() {
        return physics;
    }

    public PhysicsSystem physicsSystem() {
        return physicsSystem;
    }

    /** Ticks stepped so far. */
    public long tick() {
        return tick;
    }

    /** Advances the whole schedule one tick, which is one {@code TICK_DT} of simulation (G2). */
    public void step() {
        world.tick(tick++);
    }

    /** Advances {@code count} ticks. */
    public void step(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    /**
     * A static ground box whose top face is at {@code y = 0}.
     *
     * <p>A box rather than {@code btStaticPlaneShape}: a plane has no thickness, so anything that
     * outruns a step passes through it and falls forever, which turns a physics assertion into a
     * timeout.
     */
    public void addGround() {
        btBoxShape shape = new btBoxShape(new Vector3(60f, 1f, 60f));
        shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        shapes.add(shape);
        NativeResourceTracker.register("btBoxShape");

        btRigidBody body = newBody(shape, 0f, new Matrix4().setToTranslation(0f, -1f, 0f));
        body.setFriction(0.8f);
        body.setRestitution(0f);
        physics.addBody(body, CollisionLayer.STATIC);
    }

    /**
     * Spawns a dynamic box entity carrying {@code Transform}, {@code Velocity} and
     * {@code RigidBody}, on the {@code DEBRIS} layer.
     *
     * @param halfExtentsM half the box's size on each axis
     * @param massKg must exceed {@code MIN_BODY_MASS_KG} (G13, D06-R3)
     * @return the entity id
     */
    public int spawnBox(Vector3 halfExtentsM, float massKg, Vector3 positionM) {
        if (massKg <= SimulationConstants.MIN_BODY_MASS_KG) {
            throw new IllegalArgumentException("a dynamic body needs mass > MIN_BODY_MASS_KG (D06-R3)");
        }
        btBoxShape shape = new btBoxShape(halfExtentsM);
        shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        shapes.add(shape);
        NativeResourceTracker.register("btBoxShape");

        btRigidBody body = newBody(shape, massKg, new Matrix4().setToTranslation(positionM));
        body.setFriction(0.5f);
        body.setRestitution(0.15f);
        body.setDamping(0f, 0f);

        Entity entity = world.createEntity();
        TransformComponent transform = new TransformComponent();
        transform.position.set(positionM);
        world.addComponent(entity.id(), transform);
        world.addComponent(entity.id(), new VelocityComponent());

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.body = body;
        rigidBody.motionState = motionStates.get(motionStates.size() - 1);
        rigidBody.massKg = massKg;
        rigidBody.layer = CollisionLayer.DEBRIS;
        rigidBody.mask = CollisionLayer.DEBRIS.mask();
        shape.calculateLocalInertia(massKg, rigidBody.localInertia);
        world.addComponent(entity.id(), rigidBody);

        physics.addBody(body, rigidBody.layer);
        return entity.id();
    }

    /** The body of an entity spawned into this scene. */
    public btRigidBody bodyOf(int entityId) {
        return world.getComponent(entityId, RigidBodyComponent.class).body;
    }

    private btRigidBody newBody(btCollisionShape shape, float massKg, Matrix4 transform) {
        Vector3 inertia = new Vector3();
        if (massKg > 0f) {
            shape.calculateLocalInertia(massKg, inertia);
        }
        btDefaultMotionState motionState = new btDefaultMotionState(transform);
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(massKg, motionState, shape, inertia);
        btRigidBody body = new btRigidBody(info);
        info.dispose();

        bodies.add(body);
        motionStates.add(motionState);
        NativeResourceTracker.register("btRigidBody");
        NativeResourceTracker.register("btDefaultMotionState");
        return body;
    }

    /**
     * Tears the scene down in the D02-S5.7 rule 5 order: systems, entities, bodies, motion states,
     * shapes, then the physics world. {@code EntityDestroySystem} (slot 27) will take over the body
     * and motion state disposal when it exists; until then this class stands in for it, which is why
     * the order is spelled out here rather than left to the destroy queue.
     */
    @Override
    public void close() {
        world.dispose();

        for (int i = bodies.size() - 1; i >= 0; i--) {
            physics.removeBody(bodies.get(i));
            bodies.get(i).dispose();
            NativeResourceTracker.release("btRigidBody");
        }
        bodies.clear();
        for (int i = motionStates.size() - 1; i >= 0; i--) {
            motionStates.get(i).dispose();
            NativeResourceTracker.release("btDefaultMotionState");
        }
        motionStates.clear();
        for (int i = shapes.size() - 1; i >= 0; i--) {
            shapes.get(i).dispose();
            NativeResourceTracker.release("btBoxShape");
        }
        shapes.clear();

        physics.dispose();
    }
}
