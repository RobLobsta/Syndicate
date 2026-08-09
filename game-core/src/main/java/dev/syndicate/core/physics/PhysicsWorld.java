/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.CollisionConstants;
import com.badlogic.gdx.physics.bullet.collision.btBroadphaseInterface;
import com.badlogic.gdx.physics.bullet.collision.btCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.collision.btCollisionDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btDbvtBroadphase;
import com.badlogic.gdx.physics.bullet.collision.btDefaultCollisionConfiguration;
import com.badlogic.gdx.physics.bullet.dynamics.btConstraintSolver;
import com.badlogic.gdx.physics.bullet.dynamics.btContactSolverInfo;
import com.badlogic.gdx.physics.bullet.dynamics.btDefaultVehicleRaycaster;
import com.badlogic.gdx.physics.bullet.dynamics.btDiscreteDynamicsWorld;
import com.badlogic.gdx.physics.bullet.dynamics.btFixedConstraint;
import com.badlogic.gdx.physics.bullet.dynamics.btRaycastVehicle;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.dynamics.btSequentialImpulseConstraintSolver;
import com.badlogic.gdx.physics.bullet.dynamics.btSolverMode;
import com.badlogic.gdx.physics.bullet.dynamics.btTypedConstraint;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single Bullet world of one match, plus the state that has to survive between ticks
 * (docs/06_physics_simulation.md#D06-S4.1, #D06-S5.1).
 *
 * <p>Exactly one instance exists per {@code World} (D06-R1). {@code PhysicsSystem} (schedule slot 10)
 * is the only thing that steps it; nothing else may call {@link #step()}.
 *
 * <p><b>Native ownership (G19, D02-S5.7 rule 1).</b> This class owns the five natives it allocates —
 * collision configuration, dispatcher, broadphase, solver, dynamics world — and nothing else. Bodies
 * and motion states belong to the entity's {@code RigidBodyComponent} (rule 3) and shapes to the
 * shape cache (rule 2), so {@link #addBody} records membership without taking ownership and
 * {@link #dispose()} evicts stragglers rather than freeing them. Constraints
 * ({@link #attachBreakable}) are the exception in both directions: they are allocated here, and the
 * handle is held by a {@code SlotAttachmentComponent} that disposes it after
 * {@link #removeConstraint} — always before either endpoint body (rules 4 and 5).
 *
 * <p>{@code Bullet.init()} is deliberately not called here: D02-R3 puts it in each executable's
 * bootstrap, exactly once per process, so a library class cannot make the process-wide ref-counting
 * choice on the bootstrap's behalf.
 */
public final class PhysicsWorld implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PhysicsWorld.class);

    /** Solver iterations per step (D06-S4.1). */
    public static final int SOLVER_ITERATIONS = 10;

    /** Solver iterations for a breakable constraint, which is stiffer than the world (D06-S5.6). */
    public static final int BREAKABLE_SOLVER_ITERATIONS = 20;

    /** Metres. Split impulse only corrects penetration deeper than this (D06-S4.1). */
    public static final float SPLIT_IMPULSE_PENETRATION_THRESHOLD_M = -0.02f;

    /** Error reduction for the main solver pass (D06-S4.1). */
    public static final float ERP = 0.2f;

    /** Error reduction for the split-impulse pass (D06-S4.1). */
    public static final float ERP2 = 0.8f;

    /**
     * Collision margin for every convex shape, metres (D06-R13).
     *
     * <p>Bullet's default of 0.04 m is comparable to the thickness of a 10 cm armour plate, which
     * both floats parts visibly off the ground and makes contact geometry disagree with the mass the
     * plate was given. 0.005 m is the floor before solver stability degrades, so 0.01 m is the
     * working value. Declared here because the margin is a property of this world's configuration,
     * not of any one shape builder.
     */
    public static final float COLLISION_MARGIN_M = 0.01f;

    private final btCollisionConfiguration collisionConfig;
    private final btCollisionDispatcher dispatcher;
    private final btBroadphaseInterface broadphase;
    private final btConstraintSolver solver;
    private final btDiscreteDynamicsWorld dynamicsWorld;

    /**
     * Bodies currently in the world, in insertion order.
     *
     * <p>A list rather than a hash set on purpose: membership is checked at spawn, which is rare,
     * while iteration order must never depend on hashing (G3). It also gives E18 — a body added
     * twice — somewhere to be detected, which gdx-bullet itself does not guard.
     */
    private final List<btRigidBody> bodies = new ArrayList<>();

    /**
     * Constraints currently in the world, in insertion order (D06-S5.6).
     *
     * <p>Unlike {@link #bodies}, whose entries belong to a {@code RigidBodyComponent}, these were
     * allocated by {@link #attachBreakable} and have no component that owns them until a caller
     * stores the handle — so {@link #dispose()} frees the stragglers rather than merely evicting
     * them.
     */
    private final List<btTypedConstraint> constraints = new ArrayList<>();

    private final List<PendingImpulse> pendingImpulses = new ArrayList<>();

    private long impulseSequence;
    private int nanRemovalCount;
    private boolean disposed;

    private PhysicsWorld(
            btCollisionConfiguration collisionConfig,
            btCollisionDispatcher dispatcher,
            btBroadphaseInterface broadphase,
            btConstraintSolver solver,
            btDiscreteDynamicsWorld dynamicsWorld) {
        this.collisionConfig = collisionConfig;
        this.dispatcher = dispatcher;
        this.broadphase = broadphase;
        this.solver = solver;
        this.dynamicsWorld = dynamicsWorld;
    }

    /**
     * Builds the world of D06-S4.1.
     *
     * <p>The solver is the <em>sequential</em> impulse solver, not the parallel one, and that is a
     * determinism decision rather than a performance oversight: the parallel solver's constraint
     * ordering is not stable between runs, which is exactly what T-D06-18 documents by failing the
     * determinism test when it is substituted.
     *
     * <p>Requires {@code Bullet.init()} to have run (D02-R3).
     */
    public static PhysicsWorld create() {
        // OWNER: PhysicsWorld — every native allocated in this method (D02-S5.7 rule 1).
        btDefaultCollisionConfiguration collisionConfig = new btDefaultCollisionConfiguration();
        btCollisionDispatcher dispatcher = new btCollisionDispatcher(collisionConfig);
        btDbvtBroadphase broadphase = new btDbvtBroadphase();
        btSequentialImpulseConstraintSolver solver = new btSequentialImpulseConstraintSolver();
        btDiscreteDynamicsWorld dynamicsWorld =
                new btDiscreteDynamicsWorld(dispatcher, broadphase, solver, collisionConfig);

        dynamicsWorld.setGravity(new Vector3(
                SimulationConstants.WORLD_GRAVITY_X,
                SimulationConstants.WORLD_GRAVITY_Y,
                SimulationConstants.WORLD_GRAVITY_Z));

        btContactSolverInfo info = dynamicsWorld.getSolverInfo();
        info.setNumIterations(SOLVER_ITERATIONS);
        // Split impulse keeps penetration recovery out of the velocity solve, so a deep debris pile
        // pushes itself apart without gaining energy it never had.
        info.setSplitImpulse(1);
        info.setSplitImpulsePenetrationThreshold(SPLIT_IMPULSE_PENETRATION_THRESHOLD_M);
        info.setErp(ERP);
        info.setErp2(ERP2);
        info.setSolverMode(btSolverMode.SOLVER_USE_WARMSTARTING | btSolverMode.SOLVER_SIMD);

        NativeResourceTracker.register("btDefaultCollisionConfiguration");
        NativeResourceTracker.register("btCollisionDispatcher");
        NativeResourceTracker.register("btDbvtBroadphase");
        NativeResourceTracker.register("btSequentialImpulseConstraintSolver");
        NativeResourceTracker.register("btDiscreteDynamicsWorld");

        // Contact collection (D06-S5.1) is installed by CollisionEventSystem in slot 11, which owns
        // the sorted manifold snapshot the gameplay path reads. It is not wired here because a
        // callback with no consumer would collect contacts nobody sorts, and an unsorted manifold
        // list reaching gameplay is precisely the G3 violation the sort exists to prevent.
        return new PhysicsWorld(collisionConfig, dispatcher, broadphase, solver, dynamicsWorld);
    }

    /** The Bullet world itself, for ray tests and body membership. Never stepped by a caller. */
    public btDiscreteDynamicsWorld dynamicsWorld() {
        return dynamicsWorld;
    }

    /** How many bodies are in the world. */
    public int bodyCount() {
        return bodies.size();
    }

    /** How many bodies have been evicted for holding a non-finite transform (D06-E2, D00-R13). */
    public int nanRemovalCount() {
        return nanRemovalCount;
    }

    // ---- Membership (D06-E18) --------------------------------------------------------

    /**
     * Adds a body on the given layer, with that layer's mask from D06-S4.4.
     *
     * @throws IllegalStateException if the body is already in the world. gdx-bullet does not guard
     *     this (D06-E18) and a double-added body is stepped twice per tick, which reads as a body
     *     that falls at twice gravity — a symptom nobody traces back to the add.
     */
    public void addBody(btRigidBody body, CollisionLayer layer) {
        addBody(body, layer.bit(), layer.mask() | BULLET_DEFAULT_FILTER);
    }

    /**
     * {@code btBroadphaseProxy::DefaultFilter}, added to every body's mask (DEV-012).
     *
     * <p>Bullet's own ray tests — above all the one a ray-cast wheel casts to find the ground —
     * issue their query on this filter group and provide no way to change it
     * ({@code btDefaultVehicleRaycaster} constructs its callback internally). A body whose mask
     * excludes the bit is invisible to them, and D06-S4.4's {@code STATIC} mask excludes it: a
     * vehicle on arena geometry then finds no ground under any wheel, gets no suspension force, and
     * settles onto its own chassis hull with the engine turning nothing. It looks like a tuning
     * problem and is a filtering one (DISC-011).
     *
     * <p>No pair outcome changes. The bit is {@code STATIC}'s, so the only pairing it newly admits is
     * static against static, which Bullet's dispatcher rejects before it reaches a manifold — two
     * bodies with no mass have nothing to solve.
     */
    private static final int BULLET_DEFAULT_FILTER = 1;

    /** Adds a body with an explicit filter group and mask, for the rare body that is not on a stock layer. */
    public void addBody(btRigidBody body, int filterGroup, int filterMask) {
        if (indexOf(body) >= 0) {
            throw new IllegalStateException(
                    "body is already in the physics world (D06-E18); " + "adding it twice steps it twice per tick");
        }
        dynamicsWorld.addRigidBody(body, filterGroup, filterMask);
        bodies.add(body);
    }

    /**
     * Removes a body from the world without disposing it.
     *
     * <p>Disposal belongs to the owning {@code RigidBodyComponent} and happens in the deferred
     * destroy phase (D02-S5.7 rule 3), never here and never during a step.
     *
     * @return true if the body was in the world
     */
    public boolean removeBody(btRigidBody body) {
        int index = indexOf(body);
        if (index < 0) {
            return false;
        }
        dynamicsWorld.removeRigidBody(body);
        bodies.remove(index);
        return true;
    }

    /** True when the body is currently in the world. */
    public boolean contains(btRigidBody body) {
        return indexOf(body) >= 0;
    }

    private int indexOf(btRigidBody body) {
        for (int i = 0; i < bodies.size(); i++) {
            if (bodies.get(i) == body || bodies.get(i).equals(body)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Constraints (D06-S5.6) ------------------------------------------------------

    /**
     * Joins two bodies with a fixed constraint that breaks above {@code breakImpulseN}
     * (D06-S5.6 {@code attachBreakable}).
     *
     * <p>Used for the two situations D06-R21 allows a constraint at all: an authored articulated
     * part, and a destroyed part hanging by a thread before it falls. The common case has no
     * constraint — an attached part is geometry inside the vehicle's compound (D06-R20, DEC-004) —
     * which is deliberate, because constraints are the least stable part of any Bullet setup.
     *
     * <p>The threshold is an <b>impulse in N·s, not a force</b> (D06-R22). Confusing the two is a
     * factor of {@code TICK_DT}: a threshold given in newtons breaks at 1/60th of the load its
     * author intended, and the part falls off the moment the vehicle drives over a kerb.
     *
     * @param frameInParent the joint frame in {@code parentBody}'s local space
     * @param frameInChild the joint frame in {@code childBody}'s local space
     * @return the constraint, which the caller stores on {@code SlotAttachmentComponent} and
     *     releases through {@link #removeConstraint} before disposing it (G19)
     */
    public btFixedConstraint attachBreakable(
            btRigidBody parentBody,
            btRigidBody childBody,
            Matrix4 frameInParent,
            Matrix4 frameInChild,
            float breakImpulseN) {
        btFixedConstraint constraint = new btFixedConstraint(parentBody, childBody, frameInParent, frameInChild);
        NativeResourceTracker.register("btFixedConstraint");
        constraint.setBreakingImpulseThreshold(breakImpulseN);
        // Stiffer than the world default: a joint that visibly sags before it breaks reads as a
        // physics glitch rather than as drama.
        constraint.setOverrideNumSolverIterations(BREAKABLE_SOLVER_ITERATIONS);
        // Linked bodies do not collide: the child's hull overlaps the parent's by construction, and
        // letting them resolve that overlap would fling the part off the instant the joint is made.
        dynamicsWorld.addConstraint(constraint, true);
        constraints.add(constraint);
        return constraint;
    }

    /**
     * Removes a constraint from the world without disposing it.
     *
     * <p>Disposal belongs to whoever holds the handle, and happens in the deferred destroy phase
     * (D02-S5.7 rules 4 and 5) — always <em>before</em> either endpoint body, never during a step.
     *
     * <p>The Bullet-side removal is unconditional, including for a constraint that was added behind
     * this class's back. A caller only removes a constraint it is about to dispose, and a disposed
     * constraint still in the world is a freed pointer the solver dereferences on the next step —
     * whereas removing one Bullet does not have is a no-op.
     *
     * @return true if the constraint was one this world was tracking
     */
    public boolean removeConstraint(btTypedConstraint constraint) {
        int index = constraints.indexOf(constraint);
        if (index >= 0) {
            constraints.remove(index);
        }
        dynamicsWorld.removeConstraint(constraint);
        return index >= 0;
    }

    /** How many constraints are in the world. */
    public int constraintCount() {
        return constraints.size();
    }

    // ---- Ray-cast vehicles (D06-S5.5) ------------------------------------------------

    /**
     * The three natives a ray-cast vehicle needs, kept together so they are freed together.
     *
     * <p>The raycaster and the tuning are not optional extras: the controller holds pointers to
     * both, so freeing either while the controller is in the world is a use-after-free on the next
     * step, and freeing neither is a leak per vehicle spawned.
     */
    private record RaycastVehicleEntry(
            btRaycastVehicle controller,
            btDefaultVehicleRaycaster raycaster,
            btRaycastVehicle.btVehicleTuning tuning) {}

    private final List<RaycastVehicleEntry> raycastVehicles = new ArrayList<>();

    /**
     * Builds the ray-cast controller for a vehicle chassis and adds it to the world (D06-R18).
     *
     * <p>One body plus N ray casts, rather than rigid-body wheels on constraints: no wheel joint can
     * explode when the chassis mass and inertia change abruptly at detachment, which in this game
     * happens constantly. The wheels themselves are added by the spawn path, which knows the slot
     * transforms (D05-S5.2 step 3).
     *
     * <p><b>Native ownership (G19).</b> This world owns the controller, its raycaster and its tuning
     * — {@code VehicleChassisComponent.vehicleController} is a borrowed handle that disposes nothing.
     * The ordering is why: the controller must leave the world before the chassis body it wraps is
     * disposed, and only this class knows the body-to-controller relation at teardown time
     * (D02-S5.7 rule 5).
     *
     * @param chassisBody the vehicle's single rigid body, already added to the world
     * @return the controller, to be stored on the vehicle's {@code VehicleChassisComponent}
     */
    public btRaycastVehicle createRaycastVehicle(btRigidBody chassisBody) {
        btRaycastVehicle.btVehicleTuning tuning = new btRaycastVehicle.btVehicleTuning();
        NativeResourceTracker.register("btVehicleTuning");
        btDefaultVehicleRaycaster raycaster = new btDefaultVehicleRaycaster(dynamicsWorld);
        NativeResourceTracker.register("btDefaultVehicleRaycaster");
        btRaycastVehicle controller = new btRaycastVehicle(tuning, chassisBody, raycaster);
        NativeResourceTracker.register("btRaycastVehicle");

        // Right = +X, up = +Y, forward = +Z, matching the world axes of D00-R16. Bullet's default
        // is right/up/forward = 0/1/2 as well, but stating it makes the vehicle's idea of "forward"
        // a documented fact rather than a default that a library upgrade could change underneath.
        controller.setCoordinateSystem(0, 1, 2);
        // The chassis must never sleep (D06-R4): a sleeping body ignores the engine force the
        // controller applies, so the vehicle would refuse to move until something else hit it.
        chassisBody.setActivationState(CollisionConstants.DISABLE_DEACTIVATION);
        dynamicsWorld.addAction(controller);
        raycastVehicles.add(new RaycastVehicleEntry(controller, raycaster, tuning));
        return controller;
    }

    /**
     * Removes a ray-cast controller from the world and disposes it with its raycaster and tuning.
     *
     * <p>Unlike {@link #removeBody}, this <em>does</em> dispose, because this class is the owner
     * (D02-S5.7 rule 1). It must be called before the chassis body is disposed, which is why
     * {@code EntityDestroySystem} does it in the same pass, ahead of the body teardown.
     *
     * @return true if the controller belonged to this world
     */
    public boolean removeRaycastVehicle(btRaycastVehicle controller) {
        if (controller == null) {
            return false;
        }
        for (int i = 0; i < raycastVehicles.size(); i++) {
            if (raycastVehicles.get(i).controller() == controller) {
                disposeRaycastVehicle(raycastVehicles.remove(i));
                return true;
            }
        }
        return false;
    }

    private void disposeRaycastVehicle(RaycastVehicleEntry entry) {
        dynamicsWorld.removeAction(entry.controller());
        entry.controller().dispose();
        NativeResourceTracker.release("btRaycastVehicle");
        entry.raycaster().dispose();
        NativeResourceTracker.release("btDefaultVehicleRaycaster");
        entry.tuning().dispose();
        NativeResourceTracker.release("btVehicleTuning");
    }

    /** How many ray-cast vehicle controllers this world owns. */
    public int raycastVehicleCount() {
        return raycastVehicles.size();
    }

    // ---- Pending impulses (D06-S5.4 step 1) ------------------------------------------

    /**
     * Queues a central impulse, in N·s, to be applied at the start of the next step.
     *
     * <p>Impulses are queued rather than applied directly because a system that reached into Bullet
     * mid-tick would make the result depend on which system ran first for reasons the schedule does
     * not describe (D04-R13). Queued impulses are applied in ascending entity id order, so two runs
     * that queue the same set in a different order still produce the same step (G3).
     *
     * @throws IllegalArgumentException if any component is NaN or infinite (D00-R13)
     */
    public void queueImpulse(int entityId, Vector3 impulseNs) {
        enqueue(new PendingImpulse(entityId, impulseSequence++, PendingImpulse.Kind.CENTRAL, impulseNs, Vector3.Zero));
    }

    /**
     * Queues an impulse in N·s applied at {@code relativePositionM}, a body-space offset from the
     * body's centre of mass. An off-centre impulse imparts spin; that is the point of it.
     */
    public void queueImpulseAt(int entityId, Vector3 impulseNs, Vector3 relativePositionM) {
        enqueue(new PendingImpulse(
                entityId, impulseSequence++, PendingImpulse.Kind.AT_POINT, impulseNs, relativePositionM));
    }

    /** Queues an angular impulse, in N·m·s. */
    public void queueTorqueImpulse(int entityId, Vector3 torqueImpulseNms) {
        enqueue(new PendingImpulse(
                entityId, impulseSequence++, PendingImpulse.Kind.TORQUE, torqueImpulseNms, Vector3.Zero));
    }

    private void enqueue(PendingImpulse impulse) {
        pendingImpulses.add(impulse);
    }

    /**
     * Hands over the queued impulses in ascending {@code (entityId, queue order)} and empties the
     * queue. Called once per tick by {@code PhysicsSystem}, immediately before the step.
     */
    public List<PendingImpulse> drainQueuedImpulses() {
        if (pendingImpulses.isEmpty()) {
            return List.of();
        }
        List<PendingImpulse> drained = new ArrayList<>(pendingImpulses);
        pendingImpulses.clear();
        drained.sort(Comparator.comparingInt(PendingImpulse::entityId).thenComparingLong(PendingImpulse::sequence));
        return drained;
    }

    /** Discards queued impulses without applying them. Used when a scenario is reset mid-match. */
    public void clearQueuedImpulses() {
        pendingImpulses.clear();
    }

    // ---- Stepping (D06-S5.4) ---------------------------------------------------------

    /**
     * Advances exactly one {@code TICK_DT} (G2).
     *
     * <p>{@code maxSubSteps = 0} means "take one step of exactly this size, and interpolate
     * nothing". It is not {@code MAX_SUBSTEPS}: that constant is the catch-up cap the runtime loop's
     * accumulator applies to how many times this is called in one frame (D06-R15). Passing it here
     * would let Bullet run its own accumulator on top of ours, producing a substep count that varies
     * with frame timing — and with it, interpolated transforms in the motion states we read back
     * (D06-R16). Two runs of the same scenario would then disagree for no reason a test could name.
     */
    public void step() {
        dynamicsWorld.stepSimulation(SimulationConstants.TICK_DT, 0, SimulationConstants.TICK_DT);
    }

    /**
     * Evicts a body whose state has gone non-finite and counts it (D06-E2, D00-R13).
     *
     * <p>Separate from {@link #removeBody} so the count means what it says: bodies removed because
     * the solver produced a NaN, not bodies removed in the ordinary course of destruction.
     */
    public void removeNonFiniteBody(btRigidBody body) {
        removeBody(body);
        nanRemovalCount++;
    }

    // ---- Teardown (D02-S5.7 rule 5) --------------------------------------------------

    @Override
    public void close() {
        dispose();
    }

    /**
     * Releases the five natives this world owns, in the order D02-S5.7 rule 5 mandates.
     *
     * <p>By the time this runs, {@code world.disposeEntities()} has already destroyed every entity
     * and with it every body (D03-S5.6). A body still present here is therefore a leak somewhere
     * upstream: it is evicted so the disposed world does not keep a pointer to it, and reported at
     * WARN rather than disposed, because it belongs to a component that may still be holding it.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;

        // Ray-cast controllers go first: each holds a pointer to its chassis body, and unlike a
        // constraint it is owned here, so a straggler is disposed rather than merely evicted.
        if (!raycastVehicles.isEmpty()) {
            LOG.warn(
                    "{} ray-cast vehicle controllers were still in the physics world at teardown; a destroyed "
                            + "vehicle should have released its controller (D06-S5.5)",
                    raycastVehicles.size());
            for (int i = raycastVehicles.size() - 1; i >= 0; i--) {
                disposeRaycastVehicle(raycastVehicles.get(i));
            }
            raycastVehicles.clear();
        }

        // Constraints go before bodies, which is rule 4 of D02-S5.7: a constraint outliving an
        // endpoint body is a freed pointer the solver dereferences on the next step.
        if (!constraints.isEmpty()) {
            LOG.warn(
                    "{} constraints were still in the physics world at teardown; a broken or detached "
                            + "part should have released its constraint (D06-S5.6)",
                    constraints.size());
            for (int i = constraints.size() - 1; i >= 0; i--) {
                btTypedConstraint constraint = constraints.get(i);
                dynamicsWorld.removeConstraint(constraint);
                constraint.dispose();
                NativeResourceTracker.release("btFixedConstraint");
            }
            constraints.clear();
        }

        if (!bodies.isEmpty()) {
            LOG.warn(
                    "{} rigid bodies were still in the physics world at teardown; entities should be destroyed "
                            + "before the world is disposed (D03-S5.6)",
                    bodies.size());
            for (int i = bodies.size() - 1; i >= 0; i--) {
                dynamicsWorld.removeRigidBody(bodies.get(i));
            }
            bodies.clear();
        }
        pendingImpulses.clear();

        dynamicsWorld.dispose();
        solver.dispose();
        broadphase.dispose();
        dispatcher.dispose();
        collisionConfig.dispose();

        NativeResourceTracker.release("btDiscreteDynamicsWorld");
        NativeResourceTracker.release("btSequentialImpulseConstraintSolver");
        NativeResourceTracker.release("btDbvtBroadphase");
        NativeResourceTracker.release("btCollisionDispatcher");
        NativeResourceTracker.release("btDefaultCollisionConfiguration");
    }

    /** True once {@link #dispose()} has run. */
    public boolean isDisposed() {
        return disposed;
    }
}
