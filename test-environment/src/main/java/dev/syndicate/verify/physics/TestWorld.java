/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.collision.btBroadphaseInterface;
import com.badlogic.gdx.physics.bullet.collision.btCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btCollisionDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.collision.btConvexHullShape;
import com.badlogic.gdx.physics.bullet.collision.btDbvtBroadphase;
import com.badlogic.gdx.physics.bullet.collision.btDefaultCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btShapeHull;
import com.badlogic.gdx.physics.bullet.dynamics.btConstraintSolver;
import com.badlogic.gdx.physics.bullet.dynamics.btDiscreteDynamicsWorld;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.dynamics.btSequentialImpulseConstraintSolver;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import com.badlogic.gdx.physics.bullet.linearmath.btMotionState;
import dev.syndicate.model.SimulationConstants;
import dev.syndicate.verify.asset.MeshData;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal Bullet world for the harness, with a ground plane and the game's gravity
 * (docs/14_test_environment.md#D14-S5.5, docs/06_physics_simulation.md#D06-S5.1).
 *
 * <p>Stepped only in whole {@code TICK_DT} increments (G2), so a check's result never depends on
 * how fast the machine running it happens to be. The visual mode's slow motion changes how many
 * ticks a frame advances, never the size of a tick (D14-S5.11).
 *
 * <p><b>Native ownership (G19, D02-S5.7).</b> This class owns every native object it creates, and
 * {@link #dispose()} tears them down in the mandated order: bodies, then motion states, then
 * shapes, then the world and its collaborators. Disposing a shape while a body still references it
 * is a use-after-free that Bullet reports as a segfault several steps later.
 */
public final class TestWorld implements AutoCloseable {

    /**
     * Collision margin applied to every convex shape, metres.
     *
     * <p>Bullet's default is 0.04 m, which is a quarter of the smallest shard this project ships
     * and would hold a 1 m part visibly off the ground. Shrinking it costs a little contact
     * stability and buys collision geometry that matches what is drawn — which the harness needs,
     * since a capture is meant to be evidence about the simulation rather than an approximation
     * of it.
     */
    public static final float COLLISION_MARGIN_M = 0.005f;

    static {
        // Loads the native library. Idempotent, and calling it twice is the documented way to
        // make sure it happened before any Bullet type is touched.
        Bullet.init();
    }

    private final btCollisionConfiguration collisionConfig;
    private final btCollisionDispatcher dispatcher;
    private final btBroadphaseInterface broadphase;
    private final btConstraintSolver solver;
    private final btDiscreteDynamicsWorld world;

    private final List<btRigidBody> bodies = new ArrayList<>();
    private final List<btMotionState> motionStates = new ArrayList<>();
    private final List<btCollisionShape> shapes = new ArrayList<>();

    private long tick;

    public TestWorld(boolean withGroundPlane) {
        collisionConfig = new btDefaultCollisionConfiguration();
        dispatcher = new btCollisionDispatcher(collisionConfig);
        broadphase = new btDbvtBroadphase();
        solver = new btSequentialImpulseConstraintSolver();
        world = new btDiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);
        world.setGravity(new Vector3(
                SimulationConstants.WORLD_GRAVITY_X,
                SimulationConstants.WORLD_GRAVITY_Y,
                SimulationConstants.WORLD_GRAVITY_Z));

        if (withGroundPlane) {
            // A thick box rather than a static plane: an infinite plane has no thickness for a
            // fast shard to be caught by, so a shard that outruns one substep tunnels through it
            // and falls forever. The box's depth is the tunnelling margin.
            btBoxShape ground = new btBoxShape(new Vector3(60f, 1f, 60f));
            shapes.add(ground);
            addBody(ground, 0f, new Matrix4().setToTranslation(0f, -1f, 0f), 0.9f, 0.1f);
        }
    }

    /** Ticks elapsed since construction. The harness's only clock (G5). */
    public long tick() {
        return tick;
    }

    /** All live bodies, in creation order. The ground plane, if present, is index 0. */
    public List<btRigidBody> bodies() {
        return bodies;
    }

    public btDiscreteDynamicsWorld world() {
        return world;
    }

    /**
     * Advances exactly one {@code TICK_DT} (G2).
     *
     * <p>The {@code maxSubSteps} of 0 tells Bullet to take a single step of exactly the size given,
     * with no internal interpolation. That is what makes two runs of the same seeded scenario
     * produce the same trajectory (PHYS-012) — Bullet's own accumulator would otherwise vary the
     * substep pattern with the wall-clock intervals it was called at.
     */
    public void step() {
        world.stepSimulation(SimulationConstants.TICK_DT, 0, SimulationConstants.TICK_DT);
        tick++;
    }

    /** Advances {@code count} ticks. */
    public void step(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    /**
     * Builds a convex hull shape from a mesh, simplified to Bullet's own hull budget.
     *
     * <p>The harness rebuilds hulls from the shard meshes rather than reading them from the
     * manifest, because D09-R14 deliberately does not store hull geometry: the game rebuilds them
     * too, so this is the shape the game will actually use. The manifest's vertex *counts* are
     * compared against these (ASSET-011).
     */
    public btConvexHullShape buildHull(MeshData mesh, int maxVertices) {
        btConvexHullShape raw = new btConvexHullShape();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            raw.addPoint(
                    new Vector3(mesh.positions()[i * 3], mesh.positions()[i * 3 + 1], mesh.positions()[i * 3 + 2]),
                    i == mesh.vertexCount() - 1);
        }
        raw.setMargin(COLLISION_MARGIN_M);

        if (raw.getNumPoints() <= maxVertices) {
            shapes.add(raw);
            return raw;
        }

        // btShapeHull is Bullet's own simplifier, so the budget is enforced by the same code that
        // will consume the shape at runtime rather than by a second implementation that could
        // disagree about what counts as a vertex.
        //
        // The margin argument is 0, not the shape's margin. `buildHull(m)` offsets the generated
        // points outward by `m`, and the resulting shape then adds its *own* margin on top — so
        // passing the margin here puts a simplified shape's collision surface two margins outside
        // its mesh while an unsimplified one sits at exactly one. The sphere fixture rested 8 cm
        // above the ground and the cube 4 cm, from the same code. See DISC-004.
        btShapeHull simplifier = new btShapeHull(raw);
        simplifier.buildHull(0f);
        btConvexHullShape simplified = new btConvexHullShape(simplifier);
        simplified.setMargin(COLLISION_MARGIN_M);
        simplifier.dispose();
        raw.dispose();
        shapes.add(simplified);
        return simplified;
    }

    /**
     * Adds a rigid body.
     *
     * @param shape the collision shape; this world takes ownership only if it created it
     * @param massKg 0 for a static body
     */
    public btRigidBody addBody(
            btCollisionShape shape, float massKg, Matrix4 transform, float friction, float restitution) {
        Vector3 inertia = new Vector3();
        if (massKg > 0f) {
            shape.calculateLocalInertia(massKg, inertia);
        }
        btDefaultMotionState motionState = new btDefaultMotionState(transform);
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(massKg, motionState, shape, inertia);
        btRigidBody body = new btRigidBody(info);
        info.dispose();

        body.setFriction(friction);
        body.setRestitution(restitution);
        // Damping keeps debris from sliding forever on a frictionless-looking floor, and matches
        // what the game's debris bodies use (D07-S5.8).
        body.setDamping(0.02f, 0.08f);
        world.addRigidBody(body);

        bodies.add(body);
        motionStates.add(motionState);
        return body;
    }

    /** Registers a shape this world should dispose. */
    public void ownShape(btCollisionShape shape) {
        shapes.add(shape);
    }

    /** Removes and disposes a body, leaving its shape alone (the world owns shapes). */
    public void removeBody(btRigidBody body) {
        int index = bodies.indexOf(body);
        if (index < 0) {
            return;
        }
        world.removeRigidBody(body);
        bodies.remove(index);
        btMotionState motionState = motionStates.remove(index);
        body.dispose();
        motionState.dispose();
    }

    /**
     * Tears the world down in the order D02-S5.7 rule 5 mandates: bodies, motion states, shapes,
     * then the world and its collaborators. Any other order frees memory something still points at.
     */
    @Override
    public void close() {
        dispose();
    }

    /** See {@link #close()}. */
    public void dispose() {
        for (int i = bodies.size() - 1; i >= 0; i--) {
            world.removeRigidBody(bodies.get(i));
            bodies.get(i).dispose();
        }
        bodies.clear();
        for (int i = motionStates.size() - 1; i >= 0; i--) {
            motionStates.get(i).dispose();
        }
        motionStates.clear();
        for (int i = shapes.size() - 1; i >= 0; i--) {
            shapes.get(i).dispose();
        }
        shapes.clear();

        world.dispose();
        solver.dispose();
        broadphase.dispose();
        dispatcher.dispose();
        collisionConfig.dispose();
    }
}
