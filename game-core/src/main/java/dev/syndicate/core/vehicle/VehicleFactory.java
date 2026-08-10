/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.vehicle;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.CollisionConstants;
import com.badlogic.gdx.physics.bullet.dynamics.btRaycastVehicle;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.AssemblyLayout;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.FractureManifest;
import dev.syndicate.core.asset.HandlingBlock;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.WeaponBlock;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.FractureDataComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.component.WheelControllerComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.ShapeCacheKey;
import dev.syndicate.core.physics.VehicleCompound;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an assembly into a live vehicle (docs/05_vehicle_part_system.md#D05-S5.2).
 *
 * <p>One {@code VEHICLE} entity, one {@code PART} entity per part, one rigid body whose shape is a
 * compound of the parts' hulls (DEC-004), and a {@code btRaycastVehicle} carrying one ray per wheel
 * (D06-R18). It is called by {@code SpawnSystem} (slot 5) and by the test scenes; it is not a
 * system, because instantiating a vehicle is an operation over components rather than a pass over a
 * family — the same shape as {@link PartDetachment}, which is the inverse operation (DEC-016).
 *
 * <p><b>Mass properties are established here, not deferred to slot 15.</b> D05-S5.2 step 4 ends by
 * calling {@code MassPropertySystem.recompute}, which D04-R13 forbids a caller from doing directly.
 * There is no need to: a rigid body cannot be constructed without a mass and an inertia tensor, so
 * this computes them as part of building the body and writes the same values slot 15 would —
 * including recentring the compound on the centre of mass. {@code MassPropertySystem} then finds the
 * stored mass and COM already equal to what it computes, and does nothing, which is exactly the
 * "no structural change since last tick" path it is written around (DEC-021).
 *
 * <p><b>Stats are left dirty on purpose.</b> A spawned vehicle's {@code VehicleStatsComponent} is
 * empty and {@code dirty}; {@code VehicleStatsSystem} (slot 6) fills it in the same tick, before
 * {@code VehicleControlSystem} (7) reads it. Aggregating here as well would be a second
 * implementation of D05-S5.6 to keep in step with the first.
 */
public final class VehicleFactory {

    private static final Logger LOG = LoggerFactory.getLogger(VehicleFactory.class);

    /**
     * The ray-cast vehicle's per-wheel defaults are D06-S4.5's reference chassis table (DEC-029).
     *
     * <p>They were previously Bullet's forklift-demo values, chosen when D06-S4.5 was read as
     * authoring none of them; it authors all of them. Content still overrides the two D05-S4.5 makes
     * stats — {@code SUSPENSION_STIFFNESS} and {@code FRICTION_SLIP} — through
     * {@link StatBlock#resolve}, which is what "these are the reference defaults" means.
     */
    public static final float WHEEL_SUSPENSION_REST_LENGTH_M = HandlingBlock.REFERENCE_SUSPENSION_REST_LENGTH_M;

    /** Centimetres. Caps how far a wheel may travel before the suspension bottoms out (D06-S4.5). */
    public static final float WHEEL_MAX_SUSPENSION_TRAVEL_CM = HandlingBlock.REFERENCE_MAX_SUSPENSION_TRAVEL_CM;

    /** Newtons. Caps the spring force one wheel may push with, which is what stops a launch. */
    public static final float WHEEL_MAX_SUSPENSION_FORCE_N = HandlingBlock.REFERENCE_MAX_SUSPENSION_FORCE_N;

    /** Default spring constant, overridable per wheel by {@code SUSPENSION_STIFFNESS} (D05-S4.5). */
    public static final float WHEEL_SUSPENSION_STIFFNESS = 30f;

    /** Damping while the suspension relaxes (D06-S4.5 {@code suspensionDamping}). */
    public static final float WHEEL_DAMPING_RELAXATION = HandlingBlock.REFERENCE_SUSPENSION_DAMPING;

    /** Damping while the suspension compresses (D06-S4.5 {@code suspensionCompression}). */
    public static final float WHEEL_DAMPING_COMPRESSION = HandlingBlock.REFERENCE_SUSPENSION_COMPRESSION;

    /** Default tyre grip, overridable per wheel by {@code FRICTION_SLIP} (D05-S4.5). */
    public static final float WHEEL_FRICTION_SLIP = 2.0f;

    /**
     * How much lateral force induces body roll, {@code [0,1]} (D06-S4.5).
     *
     * <p>Low on purpose. A ray-cast vehicle with a high roll influence tips over on a hard corner,
     * and a vehicle that has just lost half its armour has a centre of mass nowhere near where the
     * author put it — the two together produce a car that rolls for no visible reason.
     */
    public static final float WHEEL_ROLL_INFLUENCE = HandlingBlock.REFERENCE_ROLL_INFLUENCE;

    /**
     * Metres. The wheel radius used when a wheel's collision mesh cannot supply one (D06-S4.5).
     *
     * <p>{@link #wheelRadiusOf} derives the radius from the art, which is what D06-S4.5's "From the
     * wheel part" asks for; this is the figure that table gives, and it is what a degenerate mesh
     * falls back to rather than a zero radius that parks the vehicle inside the ground.
     */
    public static final float WHEEL_RADIUS_FALLBACK_M = 0.42f;

    /** Wheel travel direction in chassis-local space: straight down (D00-R16, Y-up). */
    private static final Vector3 WHEEL_DIRECTION_LOCAL = new Vector3(0f, -1f, 0f);

    /** Wheel axle in chassis-local space: along −X, so +Z is forward for a right-handed wheel. */
    private static final Vector3 WHEEL_AXLE_LOCAL = new Vector3(-1f, 0f, 0f);

    private VehicleFactory() {
        throw new AssertionError("no instances");
    }

    /**
     * Instantiates an assembly (D05-S5.2).
     *
     * @param spawnTransform where the chassis body starts, in world space
     * @param ownerEntity the player or bot entity that owns it, or {@link EntityId#NULL}
     * @param teamId which team it fights for
     * @return the vehicle entity id, or {@link EntityId#NULL} if the assembly could not be resolved
     */
    public static int spawnVehicle(
            World world,
            PhysicsWorld physics,
            ShapeCache shapes,
            AssetIndex assets,
            AssemblyDef assembly,
            Matrix4 spawnTransform,
            int ownerEntity,
            int teamId) {

        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(physics, "physics");
        Objects.requireNonNull(shapes, "shapes");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(assembly, "assembly");

        AssemblyLayout layout;
        try {
            layout = AssemblyLayout.resolve(assembly, assets);
        } catch (IllegalArgumentException e) {
            LOG.error("cannot spawn assembly {}: {}", assembly.assemblyId().value(), e.getMessage());
            return EntityId.NULL;
        }
        if (!layout.unresolved().isEmpty()) {
            // Not fatal: G18 says a load with unresolved content degrades rather than refusing. The
            // vehicle spawns without those parts, and the log line is what makes that visible.
            LOG.warn(
                    "assembly {} has {} unresolved parts, which will be missing from the spawned vehicle: {}",
                    assembly.assemblyId().value(),
                    layout.unresolved().size(),
                    layout.unresolved());
        }
        if (layout.totalMassKg() < SimulationConstants.MIN_BODY_MASS_KG) {
            LOG.error(
                    "assembly {} weighs {} kg, below MIN_BODY_MASS_KG; refusing to spawn a massless dynamic "
                            + "body (D06-R3)",
                    assembly.assemblyId().value(),
                    layout.totalMassKg());
            return EntityId.NULL;
        }

        Entity vehicle = world.createEntity();
        int vehicleEntity = vehicle.id();

        SlotGraphComponent graph = new SlotGraphComponent();
        VehicleChassisComponent chassis = new VehicleChassisComponent();
        chassis.assemblyId = assembly.assemblyId();
        Vector3 comLocal = layout.comLocal(new Vector3());

        // 1. Part entities, in ascending slot path order — which is topological order (D08-R11), so
        //    a parent entity always exists by the time its child names it as a parent.
        List<VehicleCompound.Child> compoundChildren = new ArrayList<>();
        List<AssemblyLayout.PlacedPart> wheels = new ArrayList<>();
        // Slot path to the entity created for it. The chassis has no SlotNode — it is the root of
        // the tree, not an edge in it — so the graph alone cannot answer "which entity is at
        // root", which is exactly what every part hanging off the chassis needs to know.
        Map<String, Integer> entityBySlotPath = new TreeMap<>();
        for (AssemblyLayout.PlacedPart placed : layout.parts()) {
            int partEntity = createPart(world, assets, vehicleEntity, placed);
            entityBySlotPath.put(placed.slotPath(), partEntity);
            if (placed.isChassis()) {
                chassis.chassisPartEntity = partEntity;
            } else {
                attachToGraph(graph, entityBySlotPath, placed, partEntity);
            }
            if (placed.type().category() == PartCategory.WHEEL) {
                wheels.add(placed);
                chassis.wheelEntities[chassis.wheelCount++] = partEntity;
            }
            if (placed.type().category().isInCompoundShape()) {
                compoundChildren.add(new VehicleCompound.Child(
                        placed.slotPath(),
                        ShapeCacheKey.of(placed.type().partTypeId(), ShapeCacheKey.Variant.PART_HULL),
                        placed.type().collisionMesh(),
                        placed.chassisLocal()));
            }
        }
        graph.structuralVersion++;

        world.addComponent(vehicleEntity, graph);
        world.addComponent(vehicleEntity, chassis);
        world.addComponent(vehicleEntity, new VehicleStatsComponent());
        // D04-R4 puts PlayerInput in the VEHICLE archetype; D05-S5.2's pseudocode omits it, and
        // without it a spawned vehicle is outside VehicleControlSystem's family and cannot be
        // driven by anything — human or bot, which write the same component (DEC-026). It is added
        // whether or not the vehicle has an owner yet, because a driverless vehicle with zero input
        // is a parked vehicle, and an owner arriving later must not have to add components.
        world.addComponent(vehicleEntity, new PlayerInputComponent());
        TeamComponent team = new TeamComponent();
        team.teamId = teamId;
        world.addComponent(vehicleEntity, team);
        if (ownerEntity != EntityId.NULL) {
            OwnerComponent owner = new OwnerComponent();
            owner.ownerEntity = ownerEntity;
            world.addComponent(vehicleEntity, owner);
        }

        // 2. The single compound collision shape (D06-S5.3), recentred so the body's local origin is
        //    its centre of mass — which is what Bullet assumes a compound's origin to be.
        VehicleCompound compound = shapes.buildVehicleCompound(vehicleEntity, assembly.assemblyId(), compoundChildren);
        compound.recentre(comLocal.x, comLocal.y, comLocal.z);

        attachChassisBody(
                world, physics, vehicleEntity, chassis, compound, layout.totalMassKg(), comLocal, spawnTransform);

        // 3. Ray-cast wheels, in ascending slot path order so the Bullet wheel index is a function of
        //    the assembly alone (G3, D05-S5.2 step 3). The index is load-bearing:
        //    VehicleControlSystem steers and drives by it, so a reordering steers the wrong wheels.
        RigidBodyComponent chassisBody = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (chassisBody != null && chassisBody.body != null && !wheels.isEmpty()) {
            chassis.vehicleController = physics.createRaycastVehicle(chassisBody.body);
            for (int i = 0; i < wheels.size(); i++) {
                addWheel(world, chassis, wheels.get(i), chassis.wheelEntities[i], comLocal);
            }
        }

        LOG.debug(
                "spawned {} as vehicle {}: {} parts, {} kg, {} wheels",
                assembly.assemblyId().value(),
                EntityId.toString(vehicleEntity),
                layout.parts().size(),
                layout.totalMassKg(),
                wheels.size());
        return vehicleEntity;
    }

    // ---- Parts (D05-S5.2 step 1) -----------------------------------------------------

    /** Creates one part entity and copies its type's authored fields onto its components. */
    private static int createPart(World world, AssetIndex assets, int vehicleEntity, AssemblyLayout.PlacedPart placed) {

        PartType type = placed.type();
        Entity part = world.createEntity();
        int partEntity = part.id();

        PartRefComponent ref = new PartRefComponent();
        ref.partTypeId = type.partTypeId();
        ref.vehicleEntity = vehicleEntity;
        ref.slotPath = placed.slotPath();
        world.addComponent(partEntity, ref);

        PartStatsComponent stats = new PartStatsComponent();
        stats.baseStats.set(type.stats());
        // Effective starts equal to base: the degradation curve is a function of health, and health
        // starts full. VehicleStatsSystem (6) recomputes it from base every time health changes,
        // never by decaying the previous effective value (D05-S5.4).
        stats.effectiveStats.set(type.stats());
        stats.category = type.category();
        stats.materialId = type.materialId();
        world.addComponent(partEntity, stats);

        HealthComponent health = new HealthComponent();
        health.maxHp = type.maxHp();
        health.setCurrentHp(type.maxHp());
        health.armorValue = type.armorValue();
        world.addComponent(partEntity, health);

        DamageStateComponent damageState = new DamageStateComponent();
        damageState.stateEnteredTick = world.currentTick();
        world.addComponent(partEntity, damageState);

        // An attached part is geometry inside the vehicle's compound, not a body of its own
        // (DEC-004), so its RigidBodyComponent carries mass with a null body. That is where
        // MassPropertySystem reads a part's contribution from, and where DetachSystem finds the mass
        // for the debris body the part becomes (DEC-014).
        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.massKg = type.massKg();
        rigidBody.shapeKey = ShapeCacheKey.of(type.partTypeId(), ShapeCacheKey.Variant.PART_HULL);
        rigidBody.layer = CollisionLayer.VEHICLE;
        rigidBody.mask = CollisionLayer.VEHICLE.mask();
        world.addComponent(partEntity, rigidBody);

        if (!placed.isChassis()) {
            SlotAttachmentComponent attachment = new SlotAttachmentComponent();
            attachment.slotId = placed.slotId();
            attachment.localTransform.position.set(placed.slot().localTransform().position);
            attachment.localTransform.rotation.set(placed.slot().localTransform().rotation);
            attachment.breakImpulseN = type.breakImpulseN();
            world.addComponent(partEntity, attachment);
        }

        if (type.category() == PartCategory.WEAPON) {
            WeaponBlock block = type.weapon();
            WeaponControllerComponent weapon = new WeaponControllerComponent();
            weapon.weaponTypeId = type.partTypeId();
            // The stat's own add term is the interval, so it resolves against zero. A weapon that
            // authored none would then fire on every tick, which is why the fallback is applied to
            // the result rather than used as the base — using it as the base would add a second
            // later, silently, to every weapon that did author one (D01-R8).
            float authoredInterval = type.stats().resolve(StatBlock.Stat.FIRE_INTERVAL_S, 0f);
            weapon.baseFireIntervalS = authoredInterval > 0f ? authoredInterval : WeaponBlock.DEFAULT_FIRE_INTERVAL_S;
            weapon.effectiveFireIntervalS = weapon.baseFireIntervalS;
            weapon.ammoRemaining = block == null ? WeaponBlock.UNLIMITED_AMMO : block.ammoCapacity();
            if (block != null) {
                weapon.muzzleLocal.set(block.muzzleLocal());
            }
            weapon.groupIndex = placed.overrides().weaponGroup() == null
                    ? 0
                    : placed.overrides().weaponGroup();
            world.addComponent(partEntity, weapon);
        }
        if (type.category() == PartCategory.WHEEL) {
            world.addComponent(partEntity, wheelController(type, placed));
        }

        AssetId manifestRef = type.fractureManifestRef();
        if (manifestRef != null) {
            FractureDataComponent fracture = new FractureDataComponent();
            fracture.manifestRef = manifestRef;
            FractureManifest manifest = assets.fractureManifest(manifestRef);
            // Cached so FractureSystem does not reload the manifest to learn how many bodies it is
            // about to need (D07-S5.6).
            fracture.shardCount = manifest == null ? 0 : manifest.shards().size();
            world.addComponent(partEntity, fracture);
        }
        return partEntity;
    }

    /**
     * Records the part in the slot graph, which is the vehicle's structural record (D05-S4.3).
     *
     * <p>The parent entity is looked up by slot path in the map the spawn loop has been filling.
     * Ascending slot path order is topological (D08-R11), so the parent is always in it already.
     */
    private static void attachToGraph(
            SlotGraphComponent graph,
            Map<String, Integer> entityBySlotPath,
            AssemblyLayout.PlacedPart placed,
            int partEntity) {

        SlotNode node = new SlotNode();
        node.slotPath = placed.slotPath();
        node.slotId = placed.slotId();
        node.childEntity = partEntity;
        node.slotType = placed.slot().slotType();
        node.localTransform.set(placed.slot().localTransform());
        node.parentEntity = entityBySlotPath.getOrDefault(placed.parentSlotPath(), EntityId.NULL);
        graph.nodes.add(node);
        graph.parentOf.put(partEntity, node.parentEntity);
    }

    /** Builds a wheel's controller from its type's stats and the assembly's per-instance overrides. */
    private static WheelControllerComponent wheelController(PartType type, AssemblyLayout.PlacedPart placed) {
        // A wheel's spring and tyre come from its own part type: the two figures D05-S4.5 makes
        // stats are resolved against the reference default, and the rest are its handling block
        // (DEC-031). A part authoring neither gets D06-S4.5's reference corner throughout.
        HandlingBlock handling = type.handling();
        WheelControllerComponent wheel = new WheelControllerComponent();
        wheel.radiusM = wheelRadiusOf(type.collisionMesh());
        wheel.suspensionRestLengthM = handling.suspensionRestLengthM();
        wheel.suspensionStiffness =
                type.stats().resolve(StatBlock.Stat.SUSPENSION_STIFFNESS, WHEEL_SUSPENSION_STIFFNESS);
        wheel.dampingRelax = handling.suspensionDamping();
        wheel.dampingCompress = handling.suspensionCompression();
        wheel.frictionSlip = type.stats().resolve(StatBlock.Stat.FRICTION_SLIP, WHEEL_FRICTION_SLIP);
        wheel.effectiveFrictionSlip = wheel.frictionSlip;
        wheel.rollInfluence = handling.rollInfluence();
        // Unset means "as the part type would have it", and the part type has no opinion: the
        // wheel's role is a property of where it sits on the vehicle, not of what it is made of.
        wheel.isSteering = Boolean.TRUE.equals(placed.overrides().isSteering());
        wheel.isDriven = Boolean.TRUE.equals(placed.overrides().isDriven());
        return wheel;
    }

    /**
     * A wheel's radius, read off its collision mesh.
     *
     * <p>D05-S4.5's stat table has no wheel radius and neither does D08-R5's part schema, but a
     * ray-cast wheel needs one — the ray is cast from the connection point and the contact is placed
     * a radius short of it, so a wrong radius parks the vehicle in the ground or hovers it above.
     * The mesh already answers the question exactly: the axle runs along X, so the wheel's
     * silhouette is its YZ extent and its radius is half of the larger of the two (DEC-022).
     */
    public static float wheelRadiusOf(MeshData collisionMesh) {
        Vector3 min = new Vector3();
        Vector3 max = new Vector3();
        collisionMesh.bounds(min, max);
        float radiusM = Math.max(max.y - min.y, max.z - min.z) * 0.5f;
        return radiusM > 0f ? radiusM : WHEEL_RADIUS_FALLBACK_M;
    }

    // ---- The chassis body (D05-S5.2 step 2) ------------------------------------------

    /** Creates the vehicle's single rigid body, already carrying its mass properties (D06-S5.7). */
    private static void attachChassisBody(
            World world,
            PhysicsWorld physics,
            int vehicleEntity,
            VehicleChassisComponent chassis,
            VehicleCompound compound,
            float totalMassKg,
            Vector3 comLocal,
            Matrix4 spawnTransform) {

        Vector3 inertia = new Vector3();
        compound.compound().calculateLocalInertia(totalMassKg, inertia);

        // The compound was recentred on the COM, so the body's local origin is the COM and the spawn
        // transform has to be shifted by the same amount to leave the mesh where the caller asked
        // for it. Matrix4.translate post-multiplies, so the shift lands in the world already rotated
        // by the spawn orientation, which is what a local-origin shift means.
        Matrix4 bodyTransform = new Matrix4(spawnTransform).translate(comLocal);

        btDefaultMotionState motionState = new btDefaultMotionState(bodyTransform);
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(totalMassKg, motionState, compound.compound(), inertia);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();

        // D06-R4: a chassis never sleeps. A sleeping vehicle ignores the engine force its controller
        // applies and the mass change a detach just made, for as long as nothing touches it.
        body.setActivationState(CollisionConstants.DISABLE_DEACTIVATION);
        // D06-R5: CCD on for vehicles. They are the fast bodies in this world, and a chassis that
        // tunnels through arena geometry ends the match for its driver.
        body.setCcdMotionThreshold(0.2f);
        body.setCcdSweptSphereRadius(0.1f);
        physics.addBody(body, CollisionLayer.VEHICLE);

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.body = body;
        rigidBody.motionState = motionState;
        rigidBody.shapeKey = compound.key();
        rigidBody.massKg = totalMassKg;
        rigidBody.localInertia.set(inertia);
        rigidBody.centerOfMassLocal.set(comLocal);
        rigidBody.layer = CollisionLayer.VEHICLE;
        rigidBody.mask = CollisionLayer.VEHICLE.mask();
        world.addComponent(vehicleEntity, rigidBody);

        // Published so MassPropertySystem (15) compares equal on the spawn tick and leaves the
        // vehicle alone; the first value it will see differ is the one a detach produces (DEC-021).
        chassis.totalMassKg = totalMassKg;
        chassis.comLocal.set(comLocal);

        TransformComponent transform = new TransformComponent();
        Vector3 position = new Vector3();
        bodyTransform.getTranslation(position);
        transform.position.set(position);
        bodyTransform.getRotation(transform.rotation, true);
        world.addComponent(vehicleEntity, transform);
        world.addComponent(vehicleEntity, new VelocityComponent());
    }

    // ---- Wheels (D05-S5.2 step 3, D06-S5.5) ------------------------------------------

    /** Adds one ray-cast wheel and records the Bullet index it was given on the part. */
    private static void addWheel(
            World world,
            VehicleChassisComponent chassis,
            AssemblyLayout.PlacedPart placed,
            int wheelEntity,
            Vector3 comLocal) {

        HandlingBlock handling = placed.type().handling();
        WheelControllerComponent wheel = world.getComponent(wheelEntity, WheelControllerComponent.class);
        btRaycastVehicle controller = chassis.vehicleController;
        if (wheel == null || controller == null) {
            return;
        }
        // Connection points are in the chassis body's local space, whose origin is the COM — the
        // same shift the compound got.
        Vector3 connection = new Vector3();
        placed.chassisLocal().getTranslation(connection);
        connection.sub(comLocal);

        btRaycastVehicle.btVehicleTuning tuning = new btRaycastVehicle.btVehicleTuning();
        tuning.setSuspensionStiffness(wheel.suspensionStiffness);
        tuning.setSuspensionCompression(wheel.dampingCompress);
        tuning.setSuspensionDamping(wheel.dampingRelax);
        tuning.setMaxSuspensionTravelCm(handling.maxSuspensionTravelCm());
        tuning.setMaxSuspensionForce(handling.maxSuspensionForceN());
        tuning.setFrictionSlip(wheel.frictionSlip);

        controller.addWheel(
                connection,
                WHEEL_DIRECTION_LOCAL,
                WHEEL_AXLE_LOCAL,
                wheel.suspensionRestLengthM,
                wheel.radiusM,
                tuning,
                wheel.isSteering);
        // addWheel copies the tuning into the btWheelInfo it creates, so the tuning object is ours
        // to free immediately; keeping it would be one native per wheel per vehicle, forever (G19).
        tuning.dispose();

        wheel.wheelIndex = controller.getNumWheels() - 1;
        controller.getWheelInfo(wheel.wheelIndex).setRollInfluence(wheel.rollInfluence);
    }
}
