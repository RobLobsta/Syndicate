/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.physics;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.Bullet;
import com.badlogic.gdx.physics.bullet.collision.btBoxShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.AssemblyDef;
import dev.syndicate.core.asset.FractureManifest;
import dev.syndicate.core.asset.InMemoryAssetIndex;
import dev.syndicate.core.asset.MeshData;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.ShardDefinition;
import dev.syndicate.core.asset.SlotDefinition;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TeamComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.damage.DamageApplication;
import dev.syndicate.core.damage.HitResolution;
import dev.syndicate.core.damage.ProjectileImpact;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.system.CollisionEventSystem;
import dev.syndicate.core.system.DamageSystem;
import dev.syndicate.core.system.DetachSystem;
import dev.syndicate.core.system.EntityDestroySystem;
import dev.syndicate.core.system.FractureSystem;
import dev.syndicate.core.system.LifetimeSystem;
import dev.syndicate.core.system.MassPropertySystem;
import dev.syndicate.core.system.PhysicsSystem;
import dev.syndicate.core.system.ProjectileSystem;
import dev.syndicate.core.system.ScoreSystem;
import dev.syndicate.core.system.SpawnSystem;
import dev.syndicate.core.system.VehicleControlSystem;
import dev.syndicate.core.system.VehicleStatsSystem;
import dev.syndicate.core.system.WeaponSystem;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.util.Transform;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.core.vehicle.SlotType;
import dev.syndicate.core.vehicle.SpawnQueue;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.core.vehicle.VehicleFactory;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A world with a vehicle in it, for the systems that change a vehicle's structure
 * (docs/12_testing_validation_ci.md#D12-S4.1 level L3).
 *
 * <p>It builds nothing itself any more: {@link #spawnVehicle} turns its {@link PartSpec} list into
 * the {@link PartType}s and the {@link AssemblyDef} a real load would produce, and hands them to
 * {@link VehicleFactory} — the same call {@code SpawnSystem} (slot 5) makes. So the vehicles these
 * tests assert against are built by the code the game uses, and a spawn-path regression fails the
 * destruction tests rather than hiding behind a parallel test-only assembler.
 *
 * <p>{@link PartSpec} survives as the authoring shorthand because an assembly manifest is a verbose
 * thing to write inline, and a test that spends thirty lines describing a vehicle stops saying what
 * it is testing.
 */
public final class DestructionTestScene implements AutoCloseable {

    static {
        // D02-R3 puts Bullet.init() in an executable's bootstrap. A test process has no bootstrap,
        // and useRefCounting = false because ownership here is manual and explicit (G19).
        Bullet.init(false);
    }

    /** How heavy a slot in a test assembly will accept — every test part, by construction. */
    private static final float TEST_SLOT_MAX_MASS_KG = 100_000f;

    /** Newton-seconds. Well above anything a test scene generates, so nothing detaches by accident. */
    private static final float TEST_BREAK_IMPULSE_NS = 40_000f;

    /** One part of a test assembly. */
    public record PartSpec(
            String slotPath,
            PartCategory category,
            float massKg,
            Vector3 halfExtents,
            Vector3 localPosition,
            AssetId partTypeId,
            AssetId manifestRef,
            boolean hangsBeforeFalling,
            StatBlock stats) {

        public static PartSpec of(String slotPath, PartCategory category, float massKg, Vector3 localPosition) {
            return new PartSpec(
                    slotPath,
                    category,
                    massKg,
                    new Vector3(0.5f, 0.2f, 0.5f),
                    localPosition,
                    AssetId.of("part_" + slotPath.replace('/', '_')),
                    null,
                    false,
                    new StatBlock());
        }

        /** The same part with a different collision box — a wheel's radius comes from it (DEC-022). */
        public PartSpec sized(Vector3 newHalfExtents) {
            return new PartSpec(
                    slotPath,
                    category,
                    massKg,
                    newHalfExtents,
                    localPosition,
                    partTypeId,
                    manifestRef,
                    hangsBeforeFalling,
                    stats);
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
                    hangsBeforeFalling,
                    stats);
        }

        /** The same part, but one that hangs by a thread before it falls (D07-S5.7 T1). */
        public PartSpec hanging() {
            return new PartSpec(
                    slotPath, category, massKg, halfExtents, localPosition, partTypeId, manifestRef, true, stats);
        }

        /** The same part, contributing {@code add} of one stat — an engine force, a fire interval. */
        public PartSpec contributing(StatBlock.Stat stat, float add) {
            StatBlock combined = new StatBlock().set(stats);
            combined.setAdd(stat, add);
            return new PartSpec(
                    slotPath,
                    category,
                    massKg,
                    halfExtents,
                    localPosition,
                    partTypeId,
                    manifestRef,
                    hangsBeforeFalling,
                    combined);
        }

        /** The same part, multiplying one stat — a utility's buff (D05-S5.6 phase 2). */
        public PartSpec multiplying(StatBlock.Stat stat, float mul) {
            StatBlock combined = new StatBlock().set(stats);
            combined.setMul(stat, mul);
            return new PartSpec(
                    slotPath,
                    category,
                    massKg,
                    halfExtents,
                    localPosition,
                    partTypeId,
                    manifestRef,
                    hangsBeforeFalling,
                    combined);
        }

        /** The slot's id on the parent: the last segment of the path. */
        String slotId() {
            return slotPath.substring(slotPath.lastIndexOf('/') + 1);
        }

        boolean isChassis() {
            return SlotChain.ROOT_SLOT_PATH.equals(slotPath);
        }
    }

    private final World world;
    private final PhysicsWorld physics;
    private final ShapeCache shapes;
    private final DebrisFactory debrisFactory;
    private final InMemoryAssetIndex assets = new InMemoryAssetIndex();
    private final SpawnQueue spawnQueue = new SpawnQueue();

    private final PhysicsSystem physicsSystem;
    private final SpawnSystem spawnSystem;
    private final WeaponSystem weaponSystem;
    private final ProjectileSystem projectileSystem;
    private final CollisionEventSystem collisionEventSystem;
    private final DamageSystem damageSystem;
    private final ScoreSystem scoreSystem;
    private final VehicleStatsSystem vehicleStatsSystem;
    private final VehicleControlSystem vehicleControlSystem;
    private final FractureSystem fractureSystem;
    private final DetachSystem detachSystem;
    private final MassPropertySystem massPropertySystem;
    private final LifetimeSystem lifetimeSystem;
    private final EntityDestroySystem entityDestroySystem;

    private final HitResolution hitResolution;
    private final DamageApplication damageApplication;
    private final ProjectileImpact projectileImpact;

    private final Family embodied;

    /** Static geometry this scene owns directly, because it belongs to no entity (G19). */
    private final List<btBoxShape> groundShapes = new ArrayList<>();

    private final List<btRigidBody> groundBodies = new ArrayList<>();
    private final List<btDefaultMotionState> groundMotionStates = new ArrayList<>();

    private long tick;

    public DestructionTestScene(long matchSeed) {
        world = new World(matchSeed, true);
        physics = PhysicsWorld.create();
        shapes = new ShapeCache();
        debrisFactory = new DebrisFactory(physics);
        physicsSystem = new PhysicsSystem(physics);
        spawnSystem = new SpawnSystem(spawnQueue, assets, physics, shapes);
        vehicleStatsSystem = new VehicleStatsSystem(assets);
        vehicleControlSystem = new VehicleControlSystem();
        hitResolution = new HitResolution(shapes);
        damageApplication = new DamageApplication(assets, hitResolution);
        projectileImpact = new ProjectileImpact(physics, assets, hitResolution);
        weaponSystem = new WeaponSystem(assets, projectileImpact, true);
        projectileSystem = new ProjectileSystem(projectileImpact, true);
        collisionEventSystem = new CollisionEventSystem(physics, assets, hitResolution);
        damageSystem = new DamageSystem(assets, damageApplication);
        scoreSystem = new ScoreSystem();
        fractureSystem = new FractureSystem(assets, shapes, debrisFactory);
        detachSystem = new DetachSystem(assets, shapes, debrisFactory, physics);
        massPropertySystem = new MassPropertySystem(shapes);
        lifetimeSystem = new LifetimeSystem();
        entityDestroySystem = new EntityDestroySystem(physics, shapes);
        world.registerSystems(List.<EntitySystem>of(
                spawnSystem,
                vehicleStatsSystem,
                vehicleControlSystem,
                weaponSystem,
                projectileSystem,
                physicsSystem,
                collisionEventSystem,
                damageSystem,
                fractureSystem,
                detachSystem,
                massPropertySystem,
                lifetimeSystem,
                scoreSystem,
                entityDestroySystem));
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

    public SpawnQueue spawnQueue() {
        return spawnQueue;
    }

    public SpawnSystem spawnSystem() {
        return spawnSystem;
    }

    public VehicleStatsSystem vehicleStatsSystem() {
        return vehicleStatsSystem;
    }

    public VehicleControlSystem vehicleControlSystem() {
        return vehicleControlSystem;
    }

    /**
     * A static ground box whose top face is at {@code y = 0}, so ray-cast wheels have something to
     * find.
     *
     * <p>A box rather than {@code btStaticPlaneShape}, for the reason {@code PhysicsTestScene} gives:
     * a plane has no thickness, so anything that outruns a step passes through it.
     */
    public void addGround() {
        btBoxShape shape = new btBoxShape(new Vector3(200f, 1f, 200f));
        shape.setMargin(PhysicsWorld.COLLISION_MARGIN_M);
        groundShapes.add(shape);
        NativeResourceTracker.register("btBoxShape");

        btDefaultMotionState motionState = new btDefaultMotionState(new Matrix4().setToTranslation(0f, -1f, 0f));
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(0f, motionState, shape, Vector3.Zero);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();
        body.setFriction(0.9f);
        body.setRestitution(0f);
        physics.addBody(body, CollisionLayer.STATIC);
        groundBodies.add(body);
        groundMotionStates.add(motionState);
    }

    public WeaponSystem weaponSystem() {
        return weaponSystem;
    }

    public ProjectileSystem projectileSystem() {
        return projectileSystem;
    }

    public CollisionEventSystem collisionEventSystem() {
        return collisionEventSystem;
    }

    public DamageSystem damageSystem() {
        return damageSystem;
    }

    public ScoreSystem scoreSystem() {
        return scoreSystem;
    }

    /** The shared damage arithmetic, for a test that wants to apply one event directly. */
    public DamageApplication damageApplication() {
        return damageApplication;
    }

    /** The shared hit resolution, for a test that wants to resolve a contact by hand. */
    public HitResolution hitResolution() {
        return hitResolution;
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

    public LifetimeSystem lifetimeSystem() {
        return lifetimeSystem;
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
     * Registers the part types and the assembly a {@link PartSpec} list describes, then spawns it
     * through {@link VehicleFactory} — the same path {@code SpawnSystem} takes.
     *
     * <p>Each spec's {@code localPosition} becomes the slot offset on its <em>parent</em>, which is
     * where a real assembly's geometry comes from: a part's chassis-local placement is the product of
     * the slot offsets from the chassis down to it (D05-S4.3).
     *
     * @param parts the chassis first, at slot path {@code root}, then everything else
     * @return the vehicle entity id
     */
    public int spawnVehicle(AssetId assemblyId, List<PartSpec> parts, Vector3 worldPosition) {
        AssemblyDef assembly = registerAssembly(assemblyId, parts);
        return VehicleFactory.spawnVehicle(
                world,
                physics,
                shapes,
                assets,
                assembly,
                new Matrix4().setToTranslation(worldPosition),
                EntityId.NULL,
                TeamComponent.FREE_FOR_ALL);
    }

    /**
     * Registers the same content without spawning, for a test that wants the request to go through
     * {@code SpawnSystem}'s queue instead.
     */
    public AssemblyDef registerAssembly(AssetId assemblyId, List<PartSpec> parts) {
        Map<String, PartType.Builder> builders = new LinkedHashMap<>();
        for (PartSpec spec : parts) {
            builders.put(
                    spec.slotPath(),
                    PartType.builder(spec.partTypeId(), spec.category(), boxMesh(spec.halfExtents()))
                            .massKg(spec.massKg())
                            .maxHp(100f)
                            .breakImpulseN(TEST_BREAK_IMPULSE_NS)
                            .hangsBeforeFalling(spec.hangsBeforeFalling())
                            .stats(spec.stats())
                            .fractureManifestRef(spec.manifestRef()));
        }

        AssetId chassisTypeId = null;
        List<AssemblyDef.PartPlacement> placements = new ArrayList<>();
        for (PartSpec spec : parts) {
            if (spec.isChassis()) {
                chassisTypeId = spec.partTypeId();
                continue;
            }
            String parentPath = SlotChain.parentPathOf(spec.slotPath());
            PartType.Builder parent = builders.get(parentPath);
            if (parent == null) {
                throw new IllegalArgumentException(
                        "part " + spec.slotPath() + " has no parent at " + parentPath + " in this spec list");
            }
            Transform offset = new Transform();
            offset.position.set(spec.localPosition());
            parent.slot(new SlotDefinition(
                    spec.slotId(), slotTypeFor(spec.category()), offset, TEST_SLOT_MAX_MASS_KG, List.of(), true));
            placements.add(new AssemblyDef.PartPlacement(
                    spec.slotPath(),
                    parentPath,
                    spec.slotId(),
                    spec.partTypeId(),
                    spec.category() == PartCategory.WHEEL
                            ? AssemblyDef.Overrides.wheel(true, true)
                            : AssemblyDef.Overrides.NONE));
        }
        if (chassisTypeId == null) {
            throw new IllegalArgumentException("a test assembly needs a part at slot path root");
        }
        for (PartType.Builder builder : builders.values()) {
            assets.put(builder.build());
        }
        AssemblyDef assembly = new AssemblyDef(assemblyId, "medium", chassisTypeId, placements, null);
        assets.put(assembly);
        return assembly;
    }

    private static SlotType slotTypeFor(PartCategory category) {
        return switch (category) {
            case CHASSIS -> SlotType.ROOT;
            case WHEEL -> SlotType.WHEEL;
            case ARMOR -> SlotType.ARMOR_PANEL;
            case WEAPON, UTILITY -> SlotType.HARDPOINT;
            case DECORATIVE -> SlotType.ACCESSORY;
        };
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
        return SlotChain.ROOT_SLOT_PATH.equals(slotPath) ? chassis.chassisPartEntity : EntityId.NULL;
    }

    /** Seconds of simulated time in {@code ticks} ticks. */
    public static float seconds(int ticks) {
        return ticks * SimulationConstants.TICK_DT;
    }

    /**
     * Tears down in the D02-S5.7 rule 5 order: entities (and with them bodies, motion states and
     * ray-cast controllers), then shapes, then the physics world.
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
        for (btRigidBody body : groundBodies) {
            physics.removeBody(body);
            body.dispose();
            NativeResourceTracker.release("btRigidBody");
        }
        for (btDefaultMotionState motionState : groundMotionStates) {
            motionState.dispose();
            NativeResourceTracker.release("btDefaultMotionState");
        }
        for (btBoxShape shape : groundShapes) {
            shape.dispose();
            NativeResourceTracker.release("btBoxShape");
        }
        shapes.dispose();
        physics.dispose();
    }
}
