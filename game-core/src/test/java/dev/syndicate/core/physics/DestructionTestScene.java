/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btCompoundShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.FractureManifest;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.ShardDefinition;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.FractureDataComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.VehicleStatsComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.system.DetachSystem;
import dev.syndicate.core.system.EntityDestroySystem;
import dev.syndicate.core.system.FractureSystem;
import dev.syndicate.core.system.MassPropertySystem;
import dev.syndicate.core.system.PhysicsSystem;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * A world with a vehicle in it, for the systems that change a vehicle's structure
 * (docs/12_testing_validation_ci.md#D12-S4.1 level L3).
 *
 * <p>It stands in for {@code SpawnSystem} (slot 5) and the assembly loader, neither of which exists.
 * Everything it builds follows the shape the real spawn path will: one rigid body per vehicle with a
 * compound of part hulls (DEC-004), parts as bodyless entities carrying their own mass, and the slot
 * graph as the structural record. When the spawn path arrives this class collapses into a call to
 * it.
 */
public final class DestructionTestScene implements AutoCloseable {

    static {
        // D02-R3 puts Bullet.init() in an executable's bootstrap. A test process has no bootstrap,
        // and useRefCounting = false because ownership here is manual and explicit (G19).
        Bullet.init(false);
    }

    /** One part of a test assembly. */
    public record PartSpec(
            String slotPath,
            PartCategory category,
            float massKg,
            Vector3 halfExtents,
            Vector3 localPosition,
            AssetId partTypeId,
            AssetId manifestRef,
            boolean hangsBeforeFalling) {

        public static PartSpec of(String slotPath, PartCategory category, float massKg, Vector3 localPosition) {
            return new PartSpec(
                    slotPath,
                    category,
                    massKg,
                    new Vector3(0.5f, 0.2f, 0.5f),
                    localPosition,
                    AssetId.of("part_" + slotPath.replace('/', '_')),
                    null,
                    false);
        }

        /** The same part, but one that breaks into shards described by {@code manifestRef}. */
        public PartSpec fracturing(AssetId manifestRef) {
            return new PartSpec(
                    slotPath,
                    category,
                    massKg,
                    halfExtents,
                    localPosition,
                    partTypeId,
                    manifestRef,
                    hangsBeforeFalling);
        }

        /** The same part, but one that hangs by a thread before it falls (D07-S5.7 T1). */
        public PartSpec hanging() {
            return new PartSpec(slotPath, category, massKg, halfExtents, localPosition, partTypeId, manifestRef, true);
        }
    }

    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final DebrisFactory debrisFactory;
    private final InMemoryAssetIndex assets = new InMemoryAssetIndex();

    private final PhysicsSystem physicsSystem;
    private final FractureSystem fractureSystem;
    private final DetachSystem detachSystem;
    private final MassPropertySystem massPropertySystem;
    private final EntityDestroySystem entityDestroySystem;

    private final Family embodied;

    private long tick;

    public DestructionTestScene(long matchSeed) {
        world = new World(matchSeed, true);
        physics = PhysicsWorld.create();
        shapes = new ShapeCache();
        debrisFactory = new DebrisFactory(physics);
        physicsSystem = new PhysicsSystem(physics);
        fractureSystem = new FractureSystem(assets, shapes, debrisFactory);
        detachSystem = new DetachSystem(assets, shapes, debrisFactory, physics);
        massPropertySystem = new MassPropertySystem(shapes);
        entityDestroySystem = new EntityDestroySystem(physics, shapes);
        world.registerSystems(List.<EntitySystem>of(
                physicsSystem, fractureSystem, detachSystem, massPropertySystem, entityDestroySystem));
        embodied = world.family(ComponentQuery.all(RigidBodyComponent.class));
    }

    public World world() {
        return world;
    }

    public PhysicsWorld physics() {
        return physics;
    }

    public ShapeCache shapes() {
        return shapes;
    }

    public DebrisFactory debrisFactory() {
        return debrisFactory;
    }

    public InMemoryAssetIndex assets() {
        return assets;
    }

    public FractureSystem fractureSystem() {
        return fractureSystem;
    }

    public DetachSystem detachSystem() {
        return detachSystem;
    }

    public MassPropertySystem massPropertySystem() {
        return massPropertySystem;
    }

    /** Advances the whole schedule one tick, which is one {@code TICK_DT} of simulation (G2). */
    public void step() {
        world.tick(tick++);
    }

    public void step(int count) {
        for (int i = 0; i < count; i++) {
            step();
        }
    }

    public long tick() {
        return tick;
    }

    // ---- Content ---------------------------------------------------------------------

    /** The eight corners of a box, which is the smallest mesh whose hull has the box's volume. */
    public static MeshData boxMesh(Vector3 halfExtents) {
        float x = halfExtents.x;
        float y = halfExtents.y;
        float z = halfExtents.z;
        return new MeshData(
                new float[] {-x, -y, -z, x, -y, -z, -x, y, -z, x, y, -z, -x, -y, z, x, -y, z, -x, y, z, x, y, z});
    }

    /**
     * A manifest of {@code shardCount} equal boxes laid out along +X, summing exactly to
     * {@code partMassKg}.
     *
     * <p>Equal masses and a straight line make the momentum arithmetic of PROG-004 checkable by hand:
     * the expected total is the part's mass times the velocity at the part's own position.
     */
    public FractureManifest registerManifest(AssetId manifestId, AssetId partTypeId, float partMassKg, int shardCount) {
        List<ShardDefinition> shards = new ArrayList<>(shardCount);
        MeshData hull = boxMesh(new Vector3(0.08f, 0.08f, 0.08f));
        float shardMass = partMassKg / shardCount;
        for (int i = 0; i < shardCount; i++) {
            float offset = (i - (shardCount - 1) * 0.5f) * 0.2f;
            Transform placement = new Transform();
            placement.position.set(offset, 0f, 0f);
            shards.add(new ShardDefinition(
                    String.format("shard_%03d", i), i, shardMass, new Vector3(offset, 0f, 0f), placement, hull));
        }
        FractureManifest manifest = new FractureManifest(manifestId, partTypeId, partMassKg, shards);
        assets.put(manifest);
        return manifest;
    }

    // ---- Vehicle assembly ------------------------------------------------------------

    /**
     * Spawns a vehicle: one body, a compound of the non-wheel parts' hulls, and one entity per part.
     *
     * @param parts the chassis first, at slot path {@code root}, then everything else
     * @return the vehicle entity id
     */
    public int spawnVehicle(AssetId assemblyId, List<PartSpec> parts, Vector3 worldPosition) {
        Entity vehicle = world.createEntity();
        int vehicleEntity = vehicle.id();

        SlotGraphComponent graph = new SlotGraphComponent();
        VehicleChassisComponent chassis = new VehicleChassisComponent();
        chassis.assemblyId = assemblyId;

        List<VehicleCompound.Child> children = new ArrayList<>();
        float totalMassKg = 0f;
        for (PartSpec spec : parts) {
            int partEntity = createPart(vehicleEntity, spec);
            totalMassKg += spec.massKg();

            if ("root".equals(spec.slotPath())) {
                chassis.chassisPartEntity = partEntity;
            } else {
                SlotNode node = new SlotNode();
                node.slotPath = spec.slotPath();
                node.slotId = spec.slotPath().substring(spec.slotPath().lastIndexOf('/') + 1);
                node.childEntity = partEntity;
                node.parentEntity = chassis.chassisPartEntity;
                node.slotType = spec.category() == PartCategory.WHEEL ? SlotType.WHEEL : SlotType.HARDPOINT;
                node.localTransform.position.set(spec.localPosition());
                graph.nodes.add(node);
                graph.parentOf.put(partEntity, chassis.chassisPartEntity);
            }
            if (spec.category() == PartCategory.WHEEL) {
                chassis.wheelEntities[chassis.wheelCount++] = partEntity;
            } else {
                children.add(new VehicleCompound.Child(
                        spec.slotPath(),
                        ShapeCacheKey.of(spec.partTypeId(), ShapeCacheKey.Variant.PART_HULL),
                        boxMesh(spec.halfExtents()),
                        new Matrix4().setToTranslation(spec.localPosition())));
            }
        }

        world.addComponent(vehicleEntity, graph);
        world.addComponent(vehicleEntity, chassis);
        world.addComponent(vehicleEntity, new VehicleStatsComponent());

        VehicleCompound compound = shapes.buildVehicleCompound(vehicleEntity, assemblyId, children);
        attachVehicleBody(vehicleEntity, compound.compound(), totalMassKg, worldPosition);
        return vehicleEntity;
    }

    private int createPart(int vehicleEntity, PartSpec spec) {
        Entity part = world.createEntity();
        int partEntity = part.id();

        // The part type is what DetachSystem builds a detached part's debris body from — and for a
        // wheel it is the only source, since a wheel contributes no compound geometry (D06-R6).
        assets.put(new PartType(spec.partTypeId(), boxMesh(spec.halfExtents()), spec.hangsBeforeFalling()));

        PartRefComponent ref = new PartRefComponent();
        ref.partTypeId = spec.partTypeId();
        ref.vehicleEntity = vehicleEntity;
        ref.slotPath = spec.slotPath();
        world.addComponent(partEntity, ref);

        PartStatsComponent stats = new PartStatsComponent();
        stats.category = spec.category();
        world.addComponent(partEntity, stats);

        // An attached part is geometry inside the vehicle's compound, not a body of its own
        // (DEC-004), so its RigidBodyComponent carries mass and the part-local centre of mass with a
        // null body. That is where MassPropertySystem reads a part's contribution from.
        RigidBodyComponent partBody = new RigidBodyComponent();
        partBody.massKg = spec.massKg();
        partBody.shapeKey = ShapeCacheKey.of(spec.partTypeId(), ShapeCacheKey.Variant.PART_HULL);
        partBody.layer = CollisionLayer.VEHICLE;
        world.addComponent(partEntity, partBody);

        world.addComponent(partEntity, new DamageStateComponent());

        SlotAttachmentComponent attachment = new SlotAttachmentComponent();
        attachment.localTransform.position.set(spec.localPosition());
        world.addComponent(partEntity, attachment);

        if (spec.manifestRef() != null) {
            FractureDataComponent fracture = new FractureDataComponent();
            fracture.manifestRef = spec.manifestRef();
            world.addComponent(partEntity, fracture);
        }
        return partEntity;
    }

    private void attachVehicleBody(int vehicleEntity, btCompoundShape compound, float massKg, Vector3 worldPosition) {
        Vector3 inertia = new Vector3();
        compound.calculateLocalInertia(massKg, inertia);
        Matrix4 transform = new Matrix4().setToTranslation(worldPosition);
        btDefaultMotionState motionState = new btDefaultMotionState(transform);
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(massKg, motionState, compound, inertia);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();
        // D06-R4: a chassis never sleeps, or it would ignore an impulse and a mass change for a tick.
        body.setActivationState(4 /* DISABLE_DEACTIVATION */);
        physics.addBody(body, CollisionLayer.VEHICLE);

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.body = body;
        rigidBody.motionState = motionState;
        rigidBody.massKg = massKg;
        rigidBody.localInertia.set(inertia);
        rigidBody.layer = CollisionLayer.VEHICLE;
        rigidBody.mask = CollisionLayer.VEHICLE.mask();
        world.addComponent(vehicleEntity, rigidBody);

        TransformComponent transformComponent = new TransformComponent();
        transformComponent.position.set(worldPosition);
        world.addComponent(vehicleEntity, transformComponent);
        world.addComponent(vehicleEntity, new VelocityComponent());
    }

    /** Marks a part destroyed, which is what {@code DamageSystem} will do in slot 12. */
    public void destroyPart(int partEntity) {
        DamageStateComponent state = world.getComponent(partEntity, DamageStateComponent.class);
        state.state = DamageState.DESTROYED;
        state.stateEnteredTick = tick;
        state.stateVersion++;
    }

    /** The vehicle's body, for assertions that must read Bullet rather than the components. */
    public btRigidBody bodyOf(int entityId) {
        RigidBodyComponent rigidBody = world.getComponent(entityId, RigidBodyComponent.class);
        return rigidBody == null ? null : rigidBody.body;
    }

    /** The part entity at a slot path, or {@link EntityId#NULL}. */
    public int partAt(int vehicleEntity, String slotPath) {
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        for (SlotNode node : graph.nodes) {
            if (node.slotPath.equals(slotPath)) {
                return node.childEntity;
            }
        }
        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        return "root".equals(slotPath) ? chassis.chassisPartEntity : EntityId.NULL;
    }

    /** Seconds of simulated time in {@code ticks} ticks. */
    public static float seconds(int ticks) {
        return ticks * SimulationConstants.TICK_DT;
    }

    /**
     * Tears down in the D02-S5.7 rule 5 order: entities (and with them bodies and motion states),
     * then shapes, then the physics world.
     *
     * <p>Entities go through {@code EntityDestroySystem} rather than {@code World.dispose()} alone,
     * because slot 27 is what releases natives — a scene that freed them itself would leave every
     * test asserting a disposal path the game does not use. The loop repeats because destroying a
     * vehicle queues its parts, which are torn down on the next pass.
     */
    @Override
    public void close() {
        for (int pass = 0; pass < 8 && !embodied.isEmpty(); pass++) {
            int[] ids = embodied.snapshot();
            int count = embodied.size();
            for (int i = 0; i < count; i++) {
                world.destroyEntity(ids[i]);
            }
            entityDestroySystem.update(world, SimulationConstants.TICK_DT, tick);
        }
        world.dispose();
        shapes.dispose();
        physics.dispose();
    }
}
