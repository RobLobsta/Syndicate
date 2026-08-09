/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRaycastVehicle;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btVector3;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.VehicleCompound;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.model.SimulationConstants;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedule slot 15: keeps every vehicle's mass, centre of mass and inertia tensor true to the parts
 * it still has (docs/04_entity_component_model.md#D04-S4.4,
 * docs/06_physics_simulation.md#D06-S5.7).
 *
 * <p>This is the enforcement point for G10. Detachment happens in POST_SIM (slots 13 and 14), this
 * system runs immediately after, and the next physics step is a tick away — so a vehicle is never
 * stepped with the mass it had before it lost a part (D04-E11). Running it here, outside
 * {@code stepSimulation}, is also what makes {@code setMassProps} safe at all: Bullet reads a body's
 * inverse mass and inverse inertia tensor during the solve, and changing them mid-step corrupts the
 * island being solved.
 *
 * <p><b>How the recompute is triggered.</b> D06-S5.7 words it as "whenever {@code structuralVersion}
 * changed". The version is not what is compared here — the freshly summed mass and COM are compared
 * against {@code VehicleChassisComponent}'s stored values, and Bullet is written to only when they
 * differ. The two are equivalent (mass and COM are pure functions of the structural state) and the
 * component comparison keeps this system stateless, as D04-R3 requires: a remembered "last version I
 * saw" would be cross-tick state living in an ad-hoc field, invisible to replication and to
 * rollback, and a client rewinding to before a detach would then skip the recompute it needs most.
 * The sum is deterministic, so an unchanged structure produces bit-identical values and the
 * comparison is exact rather than epsilon-based.
 *
 * <p><b>Velocity is never touched</b> (D05-R23, AC-D06-10). Changing a body's mass while preserving
 * its velocity changes its momentum, and that is physically right for mass that <em>leaves</em>: the
 * departing part carries its own momentum away as a debris body. Adjusting the vehicle's velocity to
 * "conserve" momentum here would create or destroy it instead.
 */
public final class MassPropertySystem implements EntitySystem {

    private static final Logger LOG = LoggerFactory.getLogger(MassPropertySystem.class);

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 15;

    /**
     * Metres. A COM shift smaller than this is not worth recentring the compound for
     * (D06-S5.7 step 2).
     *
     * <p>The threshold is not a tolerance on correctness: it stops a sub-micrometre shift from
     * rewriting 64 child transforms and the body's world transform, where the rewrite itself would
     * introduce more float error than the shift it corrects.
     */
    public static final float COM_SHIFT_EPSILON_M = 1e-6f;

    /**
     * Square metres per kilogram. Floor on each principal inertia component (D06-E13).
     *
     * <p>A compound whose children are all coplanar gets a zero component back from
     * {@code calculateLocalInertia}, and a body with zero inertia about an axis is infinitely easy
     * to spin about it — one contact sends it into an unbounded tumble. The floor is expressed per
     * kilogram so it scales with the vehicle rather than being meaningful for a 1 kg body and
     * meaningless for a 1600 kg one.
     */
    public static final float MIN_INERTIA_M2_PER_KG = 1e-4f;

    private final ShapeCache shapes;

    private Family vehicles;

    private final Vector3 scratchPartCom = new Vector3();
    private final Vector3 scratchWeighted = new Vector3();
    private final Vector3 scratchCom = new Vector3();
    private final Vector3 scratchDelta = new Vector3();
    private final Vector3 scratchInertia = new Vector3();
    private final Vector3 scratchAabbMin = new Vector3();
    private final Vector3 scratchAabbMax = new Vector3();
    private final Vector3 scratchPosition = new Vector3();
    private final Matrix4 scratchWorld = new Matrix4();
    private final Matrix4 scratchIdentity = new Matrix4();

    public MassPropertySystem(ShapeCache shapes) {
        this.shapes = Objects.requireNonNull(shapes, "shapes");
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
        vehicles = world.family(
                ComponentQuery.all(VehicleChassisComponent.class, SlotGraphComponent.class, RigidBodyComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = vehicles.size();
        int[] entityIds = vehicles.snapshot();
        for (int i = 0; i < count; i++) {
            recompute(world, entityIds[i], tick);
        }
    }

    /**
     * The recompute of D06-S5.7 for one vehicle.
     *
     * <p>Steps, in the order the blueprint fixes them: sum mass and COM over the live parts; recentre
     * the compound so its origin is the COM; derive inertia from the recentred compound at the new
     * mass; publish to the components the rest of the simulation reads.
     */
    private void recompute(World world, int vehicleEntity, long tick) {
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        RigidBodyComponent rigidBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (chassis == null || graph == null || rigidBody == null || rigidBody.body == null) {
            return;
        }

        // 1. Total mass and COM in chassis-local space, weighted by part mass. The slot chain is
        //    walked in ascending slot path order, so the sum is associated the same way on every
        //    peer and on every run — float addition is not associative, and an order that varied
        //    would put the COM in a slightly different place on each client (G3).
        SlotChain chain = SlotChain.of(graph, chassis);
        float totalMassKg = 0f;
        scratchWeighted.set(0f, 0f, 0f);
        for (Map.Entry<String, Integer> entry : chain.partEntities()) {
            RigidBodyComponent partBody = world.getComponent(entry.getValue(), RigidBodyComponent.class);
            if (partBody == null || partBody.massKg <= 0f) {
                continue;
            }
            Matrix4 partTransform = chain.transformOf(entry.getKey());
            scratchPartCom.set(partBody.centerOfMassLocal).mul(partTransform);
            scratchWeighted.mulAdd(scratchPartCom, partBody.massKg);
            totalMassKg += partBody.massKg;
        }

        if (totalMassKg < SimulationConstants.MIN_BODY_MASS_KG) {
            // D05-S5.5 asserts this before committing a detach. Reaching it here means the assembly
            // has no mass left at all, which is not a state the vehicle can be stepped in.
            LOG.error(
                    "vehicle {} has {} kg of live parts, below MIN_BODY_MASS_KG; leaving its mass properties "
                            + "unchanged rather than giving Bullet a massless dynamic body (D06-R3)",
                    EntityId.toString(vehicleEntity),
                    totalMassKg);
            return;
        }
        scratchCom.set(scratchWeighted).scl(1f / totalMassKg);

        boolean massChanged = chassis.totalMassKg != totalMassKg;
        scratchDelta.set(scratchCom).sub(chassis.comLocal);
        boolean comChanged = scratchDelta.len2() > COM_SHIFT_EPSILON_M * COM_SHIFT_EPSILON_M;
        if (!massChanged && !comChanged) {
            return;
        }

        btRigidBody body = rigidBody.body;
        VehicleCompound compound = shapes.vehicleCompound(vehicleEntity);

        // 2. Recentre the compound so the body's origin coincides with the COM. Bullet treats a
        //    compound's local origin as the centre of mass; a vehicle whose compound is not
        //    recentred rotates about its mesh origin instead — the classic "car pivots around its
        //    nose" bug, which gets worse the more lopsided the damage is.
        if (comChanged && compound != null) {
            warnIfComOutsideCompound(vehicleEntity, compound.compound());
            compound.recentre(scratchDelta.x, scratchDelta.y, scratchDelta.z);

            // Keep the body in the same world place despite the origin shift. Matrix4.translate
            // post-multiplies, so the delta is applied in body-local space and lands in the world
            // already rotated by the body's orientation — which is what the shift of a local origin
            // means.
            body.getWorldTransform(scratchWorld);
            scratchWorld.translate(scratchDelta);
            body.setWorldTransform(scratchWorld);
            if (rigidBody.motionState != null) {
                rigidBody.motionState.setWorldTransform(scratchWorld);
            }
            TransformComponent transform = world.getComponent(vehicleEntity, TransformComponent.class);
            if (transform != null) {
                // The component mirrors the body (D06-S5.4 step 3), and the body's origin just
                // moved. Leaving it stale would put the render mesh and every system that reads
                // position a few centimetres away from the physics until the next step.
                scratchWorld.getTranslation(scratchPosition);
                transform.position.set(scratchPosition);
                transform.dirty = true;
            }
        }

        if (comChanged) {
            moveWheelConnectionPoints(world, chassis, chain, scratchCom);
        }

        // 3. Inertia from the recentred compound at the new mass. On a compound this is an
        //    approximation over child AABBs (D06-R24) — acceptable for a vehicle, and explicitly not
        //    good enough for a single part under verification, where the harness integrates the
        //    tensor from mesh geometry instead (D14-S5.4).
        btCollisionShape shape = compound != null ? compound.compound() : body.getCollisionShape();
        scratchInertia.set(0f, 0f, 0f);
        if (shape != null) {
            shape.calculateLocalInertia(totalMassKg, scratchInertia);
        }
        floorInertia(vehicleEntity, totalMassKg);

        body.setMassProps(totalMassKg, scratchInertia);
        body.updateInertiaTensor();
        // Never leave a body asleep holding stale mass properties: it would ignore the next impulse
        // for a tick and respond to the one after with the wrong inertia. Chassis bodies never sleep
        // anyway (D06-R4); this covers the moment before that is configured.
        body.activate(true);

        // 4. Publish. Everything downstream — the vehicle controller, stat aggregation, replication's
        //    agreement check (AC-D07-20) — reads these components, not Bullet.
        chassis.totalMassKg = totalMassKg;
        chassis.comLocal.set(scratchCom);
        rigidBody.massKg = totalMassKg;
        rigidBody.localInertia.set(scratchInertia);
        rigidBody.centerOfMassLocal.set(scratchCom);

        VehicleStatsComponent stats = world.getComponent(vehicleEntity, VehicleStatsComponent.class);
        if (stats != null) {
            stats.dirty = true;
        }

        LOG.debug(
                "vehicle {} mass properties recomputed at tick {}: {} kg, com {} (D06-S5.7)",
                EntityId.toString(vehicleEntity),
                tick,
                totalMassKg,
                scratchCom);
    }

    /**
     * Re-places the ray-cast wheels after the body's local origin moved (D06-S5.5).
     *
     * <p>A wheel's connection point is expressed in the chassis body's local space, whose origin is
     * the centre of mass — the same space the compound's children live in. Recentring the compound
     * without moving the wheels leaves them attached where the COM used to be, so a vehicle that
     * loses its rear armour finds its wheels have crept backwards under it: the suspension rays
     * start in the wrong place, and the vehicle drives at a slight, permanent list.
     *
     * <p>Recomputed from the slot chain rather than shifted by the delta. The two are arithmetically
     * the same, and deriving from the chain means a wheel whose placement was corrected some other
     * way still ends up where the graph says it is, rather than accumulating deltas from an origin
     * nobody remembers.
     */
    private void moveWheelConnectionPoints(
            World world, VehicleChassisComponent chassis, SlotChain chain, Vector3 comLocal) {

        btRaycastVehicle controller = chassis.vehicleController;
        if (controller == null) {
            return;
        }
        for (int i = 0; i < chassis.wheelCount; i++) {
            int wheelEntity = chassis.wheelEntities[i];
            WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
            PartRefComponent partRef = world.getComponent(wheelEntity, PartRefComponent.class);
            if (wheel == null || partRef == null || wheel.wheelIndex >= controller.getNumWheels()) {
                continue;
            }
            Matrix4 chassisLocal = chain.transformOf(partRef.slotPath);
            if (chassisLocal == null) {
                continue;
            }
            chassisLocal.getTranslation(scratchPosition);
            scratchPosition.sub(comLocal);
            // A temporary native: Bullet's member setter assigns by value, so this is freed
            // immediately rather than becoming one live btVector3 per wheel per structural change.
            btVector3 connection = new btVector3(scratchPosition.x, scratchPosition.y, scratchPosition.z);
            controller.getWheelInfo(wheel.wheelIndex).setChassisConnectionPointCS(connection);
            connection.dispose();
        }
    }

    /** D06-E13: a zero principal inertia is legal arithmetic and an unusable body. */
    private void floorInertia(int vehicleEntity, float totalMassKg) {
        float floor = totalMassKg * MIN_INERTIA_M2_PER_KG;
        if (scratchInertia.x >= floor && scratchInertia.y >= floor && scratchInertia.z >= floor) {
            return;
        }
        LOG.error(
                "vehicle {} has a degenerate inertia tensor {}; flooring at {} kg·m². A near-zero component "
                        + "means the compound's children are coplanar (D06-E13)",
                EntityId.toString(vehicleEntity),
                scratchInertia,
                floor);
        scratchInertia.set(
                Math.max(scratchInertia.x, floor),
                Math.max(scratchInertia.y, floor),
                Math.max(scratchInertia.z, floor));
    }

    /**
     * D06-E3: a COM outside the compound's own bounds is legal — a vehicle can be lopsided enough
     * for it — but it is nearly always a mis-authored slot transform, so it is worth one line in the
     * log rather than silence.
     */
    private void warnIfComOutsideCompound(int vehicleEntity, btCollisionShape compound) {
        compound.getAabb(scratchIdentity, scratchAabbMin, scratchAabbMax);
        boolean outside = scratchDelta.x < scratchAabbMin.x
                || scratchDelta.x > scratchAabbMax.x
                || scratchDelta.y < scratchAabbMin.y
                || scratchDelta.y > scratchAabbMax.y
                || scratchDelta.z < scratchAabbMin.z
                || scratchDelta.z > scratchAabbMax.z;
        if (outside) {
            LOG.warn(
                    "vehicle {} has its centre of mass at {}, outside the compound's bounds {}..{}; "
                            + "legal but usually a mis-authored slot transform (D06-E3)",
                    EntityId.toString(vehicleEntity),
                    scratchDelta,
                    scratchAabbMin,
                    scratchAabbMax);
        }
    }
}
