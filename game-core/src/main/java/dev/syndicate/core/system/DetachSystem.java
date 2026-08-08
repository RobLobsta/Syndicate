/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.dynamics.btTypedConstraint;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.damage.DetachReason;
import dev.syndicate.core.damage.VehicleDestroyedEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.ShapeCacheKey;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.PartDetachment;
import dev.syndicate.core.vehicle.PartPlacement;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 14: decides when a part leaves its vehicle, and turns what left into debris
 * (docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.7).
 *
 * <p>Authority only. D07-R23 splits the work in two: D05-S5.5 says <em>what</em> detachment does to a
 * vehicle and lives in {@link PartDetachment}; this system says <em>when</em> it happens. It runs
 * after {@code FractureSystem} (13), so every destroyed part that breaks into shards is already gone
 * by the time the triggers below look at the graph, and before {@code MassPropertySystem} (15),
 * which reconciles the vehicle's mass, COM and inertia in the same tick (G10, D04-E11).
 *
 * <p><b>The four triggers of D07-S5.7.</b>
 *
 * <ul>
 *   <li><b>T1</b> — a {@code DESTROYED} part detaches, immediately or after hanging by a thread for
 *       up to {@link #HANGING_TICKS} if its type is authored that way.
 *   <li><b>T2</b> — a part joined by a constraint whose accumulated impulse exceeded its break
 *       threshold. Bullet disables such a constraint and does not remove it; that is this system's
 *       job (D06-S5.6), and it is done here rather than in the physics layer because the response to
 *       a broken joint is a detach.
 *   <li><b>T3</b> — a part whose parent left. There is no separate check for it: a part carries its
 *       children with it, inside {@link PartDetachment#detach} (D05-S5.5 step 1).
 *   <li><b>T4</b> — the chassis died, so the whole assembly comes apart and the vehicle entity with
 *       it (D05-R26).
 * </ul>
 *
 * <p><b>Everything that left becomes a world object here</b>, in a pass that does not care which
 * trigger removed it — or whether this system removed it at all. {@code FractureSystem} detaches a
 * whole subtree and turns only its root into shards; the parts that hung below it are found by this
 * pass, because {@code PartDetachment} records each detached part's world transform and the velocity
 * it left with (v + ω × r) on the part itself. That is what closes the gap where a subtree detached
 * below a fractured part was left bodyless and inert.
 *
 * <p><b>What is not implemented.</b> D07-S5.7 adds a kick of up to {@code DETACH_KICK_MPS} = 3.0 m/s
 * along the hit normal when a hit is what caused the detachment, so parts fly off in the direction
 * they were struck. No hit normal exists to read yet — {@code CollisionEventSystem} (11) and
 * {@code DamageSystem} (12) are unwritten — and inventing a direction would be worse than leaving
 * the part with exactly the momentum it had. The kick belongs to the session that gives a part a
 * recorded last hit.
 */
public final class DetachSystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(DetachSystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 14;

    /**
     * Ticks a destroyed part may hang by a thread before it falls (D06-S5.6).
     *
     * <p>One second at {@code TICK_RATE_HZ}. It is an upper bound, not a delay: a hanging part with
     * a constraint falls the moment that constraint breaks, and this is when it gives up waiting.
     */
    public static final int HANGING_TICKS = 60;

    /**
     * Seconds a wrecked chassis stays in the world (D07-S5.8).
     *
     * <p>Two and a half times a shard's, because a wreck is a landmark: players navigate by it, take
     * cover behind it, and remember where the last fight was. A shard is scenery for a moment.
     */
    public static final float WRECK_LIFETIME_S = 30.0f;

    private final AssetIndex assets;
    private final ShapeCache shapes;
    private final DebrisFactory debrisFactory;
    private final PhysicsWorld physics;

    private Family attachedParts;
    private Family vehicles;
    private Family looseParts;

    private final Matrix4 scratchWorld = new Matrix4();
    private final Matrix4 scratchFrameInVehicle = new Matrix4();
    private final Matrix4 scratchFrameInPart = new Matrix4();
    private final Vector3 scratchComWorld = new Vector3();
    private final Vector3 scratchLinear = new Vector3();
    private final Vector3 scratchAngular = new Vector3();
    private final Vector3 scratchPosition = new Vector3();
    private final Vector3 scratchVelocity = new Vector3();
    private final List<SlotNode> scratchNodes = new ArrayList<>();

    public DetachSystem(AssetIndex assets, ShapeCache shapes, DebrisFactory debrisFactory, PhysicsWorld physics) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.shapes = Objects.requireNonNull(shapes, "shapes");
        this.debrisFactory = Objects.requireNonNull(debrisFactory, "debrisFactory");
        this.physics = Objects.requireNonNull(physics, "physics");
    }

    @Override
    public Phase phase() {
        return Phase.POST_SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        attachedParts = world.family(
                ComponentQuery.all(DamageStateComponent.class, SlotAttachmentComponent.class, PartRefComponent.class));
        vehicles = world.family(ComponentQuery.all(VehicleChassisComponent.class, SlotGraphComponent.class));
        looseParts = world.family(
                ComponentQuery.all(DamageStateComponent.class, PartRefComponent.class, RigidBodyComponent.class));
        debrisFactory.initialize(world);
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        detachTriggers(world, tick);
        wreckTrigger(world, tick);
        embodyDetachedParts(world, tick);
    }

    // ---- T1 and T2 (D07-S5.7) --------------------------------------------------------

    private void detachTriggers(World world, long tick) {
        int count = attachedParts.size();
        int[] entityIds = attachedParts.snapshot();
        for (int i = 0; i < count; i++) {
            int partEntity = entityIds[i];
            // A part detached earlier in this loop — as somebody's child — is already gone.
            if (!world.isAlive(partEntity)) {
                continue;
            }
            DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
            SlotAttachmentComponent attachment = world.getComponent(partEntity, SlotAttachmentComponent.class);
            PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
            if (damageState == null || attachment == null || partRef == null) {
                continue;
            }
            int vehicleEntity = partRef.vehicleEntity;
            if (vehicleEntity == EntityId.NULL || !world.isAlive(vehicleEntity)) {
                continue;
            }
            VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
            if (chassis != null && chassis.chassisPartEntity == partEntity) {
                // The chassis never detaches (D05-R26). Its death wrecks the vehicle instead, which
                // is T4 below.
                continue;
            }

            // T1: destroyed parts detach, immediately or after a hanging delay if authored.
            if (damageState.state == DamageState.DESTROYED) {
                if (hangsBeforeFalling(partRef) && tick - damageState.stateEnteredTick < HANGING_TICKS) {
                    ensureHangingConstraint(world, vehicleEntity, partEntity, partRef, attachment, chassis);
                } else {
                    releaseConstraint(attachment);
                    PartDetachment.detach(world, shapes, vehicleEntity, partEntity, DetachReason.DESTROYED, tick);
                    continue;
                }
            }

            // T2: the constraint broke under load. Bullet disables it and leaves it in the world; the
            // removal and the disposal are ours (D06-S5.6, G19).
            btTypedConstraint constraint = attachment.constraintHandle;
            if (constraint != null && !constraint.isEnabled()) {
                releaseConstraint(attachment);
                PartDetachment.detach(world, shapes, vehicleEntity, partEntity, DetachReason.JOINT_BROKE, tick);
            }
        }
    }

    /**
     * Joins a hanging part to its vehicle so it can break off under load (D06-S5.6).
     *
     * <p>A constraint needs two bodies, and the usual attached part is not one — it is geometry
     * inside the vehicle's compound (D06-R20, DEC-004), so there is nothing to join it to and the
     * delay alone is the hang. The constraint is therefore created only for a part that a spawn path
     * gave its own body, which is the articulated case D06-R21 allows (DEC-017).
     */
    private void ensureHangingConstraint(
            World world,
            int vehicleEntity,
            int partEntity,
            PartRefComponent partRef,
            SlotAttachmentComponent attachment,
            VehicleChassisComponent chassis) {

        if (attachment.constraintHandle != null) {
            return;
        }
        RigidBodyComponent partBody = world.getComponent(partEntity, RigidBodyComponent.class);
        RigidBodyComponent vehicleBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (partBody == null
                || partBody.body == null
                || vehicleBody == null
                || vehicleBody.body == null
                || chassis == null
                || graph == null) {
            return;
        }
        Matrix4 chainTransform = SlotChain.of(graph, chassis).transformOf(partRef.slotPath);
        if (chainTransform == null) {
            return;
        }
        // The vehicle body's local space has its origin on the centre of mass (D06-S5.7 step 2), so
        // the joint frame is the part's chassis-local placement shifted by the same amount.
        scratchFrameInVehicle
                .setToTranslation(-chassis.comLocal.x, -chassis.comLocal.y, -chassis.comLocal.z)
                .mul(chainTransform);
        scratchFrameInPart.idt();
        attachment.constraintHandle = physics.attachBreakable(
                vehicleBody.body, partBody.body, scratchFrameInVehicle, scratchFrameInPart, attachment.breakImpulseN);
    }

    private boolean hangsBeforeFalling(PartRefComponent partRef) {
        PartType partType = assets.partType(partRef.partTypeId);
        return partType != null && partType.hangsBeforeFalling();
    }

    /** Removes and disposes a constraint, in that order and never during a step (D02-S5.7 rule 4). */
    private void releaseConstraint(SlotAttachmentComponent attachment) {
        btTypedConstraint constraint = attachment.constraintHandle;
        if (constraint == null) {
            return;
        }
        attachment.constraintHandle = null;
        physics.removeConstraint(constraint);
        constraint.dispose();
        NativeResourceTracker.release(constraint.getClass().getSimpleName());
    }

    // ---- T4: the chassis died (D07-S5.7) ---------------------------------------------

    private void wreckTrigger(World world, long tick) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            int vehicleEntity = entityIds[i];
            // Destroying the vehicle is what makes wrecking one-way (G9): a destroyed entity is
            // immediately not alive, so a second pass cannot wreck it twice.
            if (!world.isAlive(vehicleEntity)) {
                continue;
            }
            VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
            if (chassis == null || chassis.chassisPartEntity == EntityId.NULL) {
                continue;
            }
            if (isChassisDead(world, chassis.chassisPartEntity)) {
                wreckVehicle(world, vehicleEntity, chassis, tick);
            }
        }
    }

    /**
     * Whether the chassis part is dead.
     *
     * <p>"Not alive" counts, and is the ordinary case for a chassis that breaks into shards:
     * {@code FractureSystem} fractures it in slot 13 and destroys the part entity, so by the time
     * this runs the state it would have read is gone. Reading liveness as well as the damage state
     * is what keeps the fracturing chassis and the non-fracturing one on one code path.
     */
    private boolean isChassisDead(World world, int chassisPartEntity) {
        if (!world.isAlive(chassisPartEntity)) {
            return true;
        }
        DamageStateComponent damageState = world.getComponent(chassisPartEntity, DamageStateComponent.class);
        return damageState != null
                && (damageState.state == DamageState.DESTROYED || damageState.state == DamageState.DETACHED);
    }

    /**
     * Takes a vehicle apart (D07-S5.7 {@code wreckVehicle}).
     *
     * <p>The chassis becomes a debris body unless it broke into shards, which slot 13 has already
     * done by now — D07-S5.7 has {@code wreckVehicle} call {@code fracturePart} itself, but a system
     * may not call another system (D04-R13), and {@code FractureSystem} running one slot earlier in
     * the same tick produces exactly the same outcome from the same manifest (DEC-017).
     */
    private void wreckVehicle(World world, int vehicleEntity, VehicleChassisComponent chassis, long tick) {
        int chassisPartEntity = chassis.chassisPartEntity;
        HealthComponent health = world.getComponent(chassisPartEntity, HealthComponent.class);
        world.events()
                .emit(new VehicleDestroyedEvent(
                        vehicleEntity, health == null ? EntityId.NULL : health.lastAttacker, tick));

        // Captured before anything leaves: every detach moves the centre of mass, and the chassis's
        // own placement is measured from it.
        boolean placed = PartPlacement.chassisToWorld(world, vehicleEntity, scratchWorld, scratchComWorld);
        VelocityComponent velocity = world.getComponent(vehicleEntity, VelocityComponent.class);
        scratchLinear.set(velocity == null ? Vector3.Zero : velocity.linear);
        scratchAngular.set(velocity == null ? Vector3.Zero : velocity.angular);

        // Every remaining part, in ascending slot path order (G3). A part whose ancestor was visited
        // first has already left with it, and detaching it again is the no-op D05-E5 requires.
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (graph != null) {
            scratchNodes.clear();
            scratchNodes.addAll(graph.nodes);
            scratchNodes.sort(Comparator.comparing(node -> node.slotPath));
            for (int i = 0; i < scratchNodes.size(); i++) {
                PartDetachment.detach(
                        world,
                        shapes,
                        vehicleEntity,
                        scratchNodes.get(i).childEntity,
                        DetachReason.VEHICLE_WRECKED,
                        tick);
            }
            scratchNodes.clear();
        }

        // The chassis part itself. Its slot-chain transform is the identity, so the vehicle's
        // chassis-local-to-world transform is its world transform.
        if (world.isAlive(chassisPartEntity)) {
            if (placed) {
                scratchWorld.getTranslation(scratchPosition);
                PartPlacement.velocityAt(
                        scratchLinear, scratchAngular, scratchComWorld, scratchPosition, scratchVelocity);
                spawnDebrisFor(
                        world, chassisPartEntity, scratchWorld, scratchVelocity, scratchAngular, WRECK_LIFETIME_S);
            } else {
                LOG.error(
                        "vehicle {} was wrecked but has no body to place its chassis from; the chassis leaves no "
                                + "wreck (D07-S5.7)",
                        EntityId.toString(vehicleEntity));
            }
            world.destroyEntity(chassisPartEntity);
        }

        LOG.debug("vehicle {} wrecked at tick {}", EntityId.toString(vehicleEntity), tick);
        world.destroyEntity(vehicleEntity);
    }

    // ---- Turning what left into world objects (D05-S5.5 step 4) ----------------------

    /**
     * Gives every detached part that is not yet a world object one.
     *
     * <p>Driven by the parts' own state rather than by what this system just did, so it covers the
     * subtree {@code FractureSystem} detached below a fractured part as well as the parts detached
     * here. A part is retired once its debris body exists: the part entity carried structure the
     * debris does not have, and keeping both would leave the vehicle's former parts addressable by
     * systems that have no business with scrap.
     */
    private void embodyDetachedParts(World world, long tick) {
        int count = looseParts.size();
        int[] entityIds = looseParts.snapshot();
        for (int i = 0; i < count; i++) {
            int partEntity = entityIds[i];
            if (!world.isAlive(partEntity)) {
                continue;
            }
            DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
            PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
            if (damageState == null
                    || damageState.state != DamageState.DETACHED
                    || partRef == null
                    || partRef.vehicleEntity != EntityId.NULL) {
                continue;
            }

            SlotAttachmentComponent attachment = world.getComponent(partEntity, SlotAttachmentComponent.class);
            if (attachment != null) {
                releaseConstraint(attachment);
            }

            RigidBodyComponent rigidBody = world.getComponent(partEntity, RigidBodyComponent.class);
            if (rigidBody != null && rigidBody.body != null) {
                // An articulated part was already its own body; it needs a lifetime, not a spawn.
                markAsDebris(world, partEntity);
                continue;
            }

            TransformComponent transform = world.getComponent(partEntity, TransformComponent.class);
            if (transform == null) {
                LOG.error(
                        "detached part {} has no transform to place a debris body at; retiring it without one",
                        EntityId.toString(partEntity));
                world.destroyEntity(partEntity);
                continue;
            }
            VelocityComponent velocity = world.getComponent(partEntity, VelocityComponent.class);
            scratchWorld.idt().set(transform.position, transform.rotation);
            scratchVelocity.set(velocity == null ? Vector3.Zero : velocity.linear);
            scratchAngular.set(velocity == null ? Vector3.Zero : velocity.angular);

            spawnDebrisFor(
                    world,
                    partEntity,
                    scratchWorld,
                    scratchVelocity,
                    scratchAngular,
                    SimulationConstants.DEBRIS_LIFETIME_S);
            world.destroyEntity(partEntity);
        }
    }

    /**
     * Spawns one debris body for a part that is leaving in one piece (D05-S5.5 step 4).
     *
     * @return true if a body was spawned
     */
    private boolean spawnDebrisFor(
            World world,
            int partEntity,
            Matrix4 worldTransform,
            Vector3 linearVelocity,
            Vector3 angularVelocity,
            float lifetimeSeconds) {

        RigidBodyComponent rigidBody = world.getComponent(partEntity, RigidBodyComponent.class);
        PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
        if (rigidBody == null || partRef == null) {
            return false;
        }
        if (rigidBody.massKg < SimulationConstants.MIN_BODY_MASS_KG) {
            // Never clamped: a clamped mass silently violates G7 (D06-E1). A part this light is a
            // content error, and one log line beats a body that does not weigh what the part did.
            LOG.error(
                    "detached part {} weighs {} kg, below MIN_BODY_MASS_KG; it leaves no debris",
                    EntityId.toString(partEntity),
                    rigidBody.massKg);
            return false;
        }
        ShapeCacheKey shapeKey = rigidBody.shapeKey != null
                ? rigidBody.shapeKey
                : ShapeCacheKey.of(partRef.partTypeId, ShapeCacheKey.Variant.PART_HULL);
        btCollisionShape hull = hullFor(shapeKey, partRef);
        if (hull == null) {
            LOG.error(
                    "detached part {} has no collision hull under {} and no loaded part type to build one from; "
                            + "it leaves no debris",
                    EntityId.toString(partEntity),
                    shapeKey);
            return false;
        }
        debrisFactory.spawnDebris(
                world,
                shapeKey,
                hull,
                rigidBody.massKg,
                worldTransform,
                linearVelocity,
                angularVelocity,
                lifetimeSeconds,
                partEntity);
        return true;
    }

    /**
     * The part's collision hull.
     *
     * <p>Usually already cached, because building the vehicle's compound built it (D06-S5.3). A
     * wheel is the exception that makes the asset lookup necessary rather than defensive: it is a
     * ray cast and contributes no compound geometry (D06-R6), so the first time anything needs a
     * wheel's hull is the moment it detaches and has to bounce away (D06-R19).
     *
     * @return the hull, or null when neither the cache nor the asset index can produce one
     */
    private btCollisionShape hullFor(ShapeCacheKey shapeKey, PartRefComponent partRef) {
        btCollisionShape cached = shapes.get(shapeKey);
        if (cached != null) {
            return cached;
        }
        PartType partType = assets.partType(partRef.partTypeId);
        if (partType == null) {
            return null;
        }
        return shapes.hullFor(shapeKey, partType.collisionMesh());
    }

    /** Gives an already-embodied part the tag and lifetime that make it despawnable debris. */
    private void markAsDebris(World world, int partEntity) {
        if (!world.hasComponent(partEntity, DebrisTagComponent.class)) {
            DebrisTagComponent tag = new DebrisTagComponent();
            tag.sourcePartEntity = partEntity;
            tag.spawnTick = world.currentTick();
            world.addComponent(partEntity, tag);
        }
        if (!world.hasComponent(partEntity, LifetimeComponent.class)) {
            LifetimeComponent lifetime = new LifetimeComponent();
            lifetime.remainingS = SimulationConstants.DEBRIS_LIFETIME_S;
            lifetime.despawnPolicy = LifetimeComponent.DespawnPolicy.SLEEP_THEN_DESTROY;
            world.addComponent(partEntity, lifetime);
        }
    }
}
