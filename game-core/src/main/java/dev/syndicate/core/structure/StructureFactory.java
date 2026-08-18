/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.structure;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionShape;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import com.badlogic.gdx.physics.bullet.linearmath.btDefaultMotionState;
import dev.syndicate.core.asset.AssemblyLayout;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.FractureManifest;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.StructureDef;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.FractureDataComponent;
import dev.syndicate.core.component.HealthComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.SlotAttachmentComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.StructureComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VelocityComponent;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.physics.ShapeCache;
import dev.syndicate.core.physics.ShapeCacheKey;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.core.vehicle.SlotNode;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.CollisionLayer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawns a destructible structure into the world (docs/16_procedural_arena_generation.md#D16-S7).
 *
 * <p><b>This is one of the two pieces of new code D16-R81 allows</b> — a factory and a placement
 * pass. Everything that breaks a structure afterwards already exists (DEC-071): a hit becomes damage
 * through {@code DamageApplication}, a destroyed part fractures in slot 13, and every part above it
 * becomes "a part whose parent is gone", which slot 14 already turns into debris. If reading this
 * class ever leads to writing a {@code StructureSystem}, the design has drifted and D16-R81 is the
 * sentence to re-read.
 *
 * <p><b>One static body per part</b>, against {@code VehicleFactory}'s single compound (DEC-004,
 * D16-R77). A vehicle is one body because its parts share an inertia tensor that has to stay correct
 * as parts leave (G10); a structure has none worth maintaining and never moves as a whole, so the
 * split costs nothing and buys the thing that matters — a destroyed part leaves the world without
 * its neighbours' shapes being touched, and a hit resolves to a part by which body it struck rather
 * than by a compound child index.
 *
 * <p>The parts are still placed by {@link AssemblyLayout}, which is what makes "a structure is an
 * assembly" true rather than merely claimed: the slot graph, the chain transforms and the part
 * documents are the vehicle path's, unchanged.
 */
public final class StructureFactory {

    private static final Logger LOG = LoggerFactory.getLogger(StructureFactory.class);

    /**
     * Friction of a structure's surfaces.
     *
     * <p>Higher than the arena floor's, and deliberately: a car that glances off a building should
     * be slowed and turned by it rather than sliding along it like a bumper rail, and the building
     * is the object a player is most likely to be pushed into on purpose.
     */
    public static final float STRUCTURE_FRICTION = 0.95f;

    /** Structures do not bounce. A wall that returned energy would be a trampoline. */
    public static final float STRUCTURE_RESTITUTION = 0f;

    private StructureFactory() {}

    /**
     * Builds one structure at a world transform, and returns the structure entity.
     *
     * <p>The structure entity itself carries no body: it owns the slot graph and the identity, and
     * the geometry lives on its parts. That is the same division {@code VehicleFactory} makes, minus
     * the chassis body, and it is what lets {@code PartDetachment} run over a structure without
     * knowing what it is walking.
     *
     * @param world the ECS world
     * @param physics the physics world the static bodies join
     * @param shapes the shape cache the hulls are owned by (G19)
     * @param assets the index the part types are resolved against
     * @param definition what to build
     * @param worldTransform where to build it, in world space
     * @return the structure entity id, or {@link EntityId#NULL} if it could not be resolved
     */
    public static int spawnStructure(
            World world,
            PhysicsWorld physics,
            ShapeCache shapes,
            AssetIndex assets,
            StructureDef definition,
            Matrix4 worldTransform) {

        Objects.requireNonNull(definition, "definition");
        AssemblyLayout layout = AssemblyLayout.resolve(definition.assembly(), assets);
        if (layout.chassis() == null) {
            LOG.error(
                    "structure {} names root part {} which is not loaded; nothing is placed",
                    definition.structureId(),
                    definition.rootPartTypeId());
            return EntityId.NULL;
        }
        if (!layout.unresolved().isEmpty()) {
            // A warning rather than a refusal (G18): a building missing its top floor is still
            // cover, and the finding names exactly which part type to go and look for.
            LOG.warn(
                    "structure {} has {} unresolved part(s): {}",
                    definition.structureId(),
                    layout.unresolved().size(),
                    layout.unresolved());
        }

        Entity structure = world.createEntity();
        int structureEntity = structure.id();

        StructureComponent component = new StructureComponent();
        component.structureId = definition.structureId();
        component.footprintRadiusM = definition.footprintRadiusM();
        component.footprintHeightM = definition.footprintHeightM();
        world.addComponent(structureEntity, component);

        SlotGraphComponent graph = new SlotGraphComponent();
        world.addComponent(structureEntity, graph);

        TransformComponent structureTransform = new TransformComponent();
        worldTransform.getTranslation(structureTransform.position);
        worldTransform.getRotation(structureTransform.rotation, true);
        world.addComponent(structureEntity, structureTransform);
        // Zero, permanently. It exists because PartDetachment reads the owner's motion to give a
        // leaving part the velocity it left with (DEC-018), and a structure's is nothing.
        world.addComponent(structureEntity, new VelocityComponent());

        Map<String, Integer> entityBySlotPath = new HashMap<>();
        Matrix4 partWorld = new Matrix4();
        for (AssemblyLayout.PlacedPart placed : layout.parts()) {
            int partEntity = createPart(world, physics, shapes, assets, structureEntity, placed, definition);
            if (partEntity == EntityId.NULL) {
                continue;
            }
            entityBySlotPath.put(placed.slotPath(), partEntity);
            partWorld.set(worldTransform).mul(placed.chassisLocal());
            placeBody(world, physics, shapes, partEntity, partWorld);
            if (!placed.isChassis()) {
                attachToGraph(graph, entityBySlotPath, placed, partEntity);
            }
            component.partCount++;
        }
        component.rootPartEntity =
                entityBySlotPath.getOrDefault(layout.chassis().slotPath(), EntityId.NULL);
        LOG.debug(
                "spawned structure {} with {} parts at {}",
                definition.structureId(),
                component.partCount,
                structureTransform.position);
        return structureEntity;
    }

    /**
     * One part entity, with the body a structure part has and a vehicle part does not.
     *
     * <p>The mass on {@link RigidBodyComponent} is the part's authored mass even though the body is
     * zero-mass and static, and that is not a contradiction — it is D16-R79's "the new body's mass
     * comes from the part definition". Slot 14 reads exactly that field when it turns a detached
     * part into debris (DEC-014), so a floor that is shot off falls weighing what a floor weighs.
     */
    private static int createPart(
            World world,
            PhysicsWorld physics,
            ShapeCache shapes,
            AssetIndex assets,
            int structureEntity,
            AssemblyLayout.PlacedPart placed,
            StructureDef definition) {

        PartType type = placed.type();
        Entity part = world.createEntity();
        int partEntity = part.id();

        PartRefComponent ref = new PartRefComponent();
        ref.partTypeId = type.partTypeId();
        // The owning entity, which for a structure part is the structure. The field is named for
        // vehicles because vehicles came first; what slot 14 does with it is ask "is my owner still
        // there", which is the same question either way.
        ref.vehicleEntity = structureEntity;
        ref.slotPath = placed.slotPath();
        world.addComponent(partEntity, ref);

        PartStatsComponent stats = new PartStatsComponent();
        stats.baseStats.set(type.stats());
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

        ShapeCacheKey shapeKey = ShapeCacheKey.of(type.partTypeId(), ShapeCacheKey.Variant.PART_HULL);
        btCollisionShape hull = shapes.get(shapeKey);
        if (hull == null) {
            hull = shapes.hullFor(shapeKey, type.collisionMesh());
        }
        if (hull == null) {
            LOG.error(
                    "structure {} part {} has no collision hull; it is not placed",
                    definition.structureId(),
                    type.partTypeId());
            world.destroyEntity(partEntity);
            return EntityId.NULL;
        }

        RigidBodyComponent rigidBody = new RigidBodyComponent();
        rigidBody.massKg = type.massKg();
        type.centerOfMassLocal(rigidBody.centerOfMassLocal);
        rigidBody.shapeKey = shapeKey;
        rigidBody.layer = CollisionLayer.STATIC;
        rigidBody.mask = CollisionLayer.STATIC.mask();
        world.addComponent(partEntity, rigidBody);
        world.addComponent(partEntity, new VelocityComponent());

        if (!placed.isChassis()) {
            SlotAttachmentComponent attachment = new SlotAttachmentComponent();
            attachment.slotId = placed.slotId();
            attachment.localTransform.position.set(placed.slot().localTransform().position);
            attachment.localTransform.rotation.set(placed.slot().localTransform().rotation);
            attachment.breakImpulseN = type.breakImpulseN();
            world.addComponent(partEntity, attachment);
        }

        AssetId manifestRef = type.fractureManifestRef();
        if (manifestRef != null) {
            FractureDataComponent fracture = new FractureDataComponent();
            fracture.manifestRef = manifestRef;
            FractureManifest manifest = assets.fractureManifest(manifestRef);
            fracture.shardCount = manifest == null ? 0 : manifest.shards().size();
            world.addComponent(partEntity, fracture);
        }

        TransformComponent transform = new TransformComponent();
        transform.parent = EntityId.NULL;
        world.addComponent(partEntity, transform);
        return partEntity;
    }

    /**
     * Gives a part the static body it stands on, at its world transform.
     *
     * <p>Separate from {@link #createPart} because the world transform is only known once the
     * structure's own transform has been composed with the part's chain transform, and composing it
     * inside the creation loop is what keeps that composition in one place.
     *
     * <p>Zero mass on the {@code STATIC} layer, which is what D16-R20's {@code staticRoot} means and
     * what D16-R78 extends to every part while its support chain holds. The part's real mass is on
     * the component, not on the body, and slot 14 spends it when the part stops being static.
     */
    private static void placeBody(
            World world, PhysicsWorld physics, ShapeCache shapes, int partEntity, Matrix4 worldTransform) {

        RigidBodyComponent rigidBody = world.getComponent(partEntity, RigidBodyComponent.class);
        TransformComponent transform = world.getComponent(partEntity, TransformComponent.class);
        if (rigidBody == null || transform == null) {
            return;
        }
        worldTransform.getTranslation(transform.position);
        worldTransform.getRotation(transform.rotation, true);

        btCollisionShape hull = shapes.get(rigidBody.shapeKey);
        if (hull == null) {
            return;
        }
        btDefaultMotionState motionState = new btDefaultMotionState(new Matrix4(worldTransform));
        NativeResourceTracker.register("btDefaultMotionState");
        btRigidBody.btRigidBodyConstructionInfo info =
                new btRigidBody.btRigidBodyConstructionInfo(0f, motionState, hull, Vector3.Zero);
        btRigidBody body = new btRigidBody(info);
        NativeResourceTracker.register("btRigidBody");
        info.dispose();
        body.setFriction(STRUCTURE_FRICTION);
        body.setRestitution(STRUCTURE_RESTITUTION);
        body.userData = partEntity;
        physics.addBody(body, CollisionLayer.STATIC);
        rigidBody.body = body;
        rigidBody.motionState = motionState;
    }

    private static void attachToGraph(
            SlotGraphComponent graph,
            Map<String, Integer> entityBySlotPath,
            AssemblyLayout.PlacedPart placed,
            int partEntity) {

        SlotNode node = new SlotNode();
        node.slotPath = placed.slotPath();
        node.slotId = placed.slotId();
        node.slotType = placed.slot().slotType();
        node.childEntity = partEntity;
        node.parentEntity = entityBySlotPath.getOrDefault(placed.parentSlotPath(), EntityId.NULL);
        node.localTransform.position.set(placed.slot().localTransform().position);
        node.localTransform.rotation.set(placed.slot().localTransform().rotation);
        graph.nodes.add(node);
        graph.parentOf.put(partEntity, node.parentEntity);
        graph.structuralVersion++;
    }

    /** A world transform from a ground position and a heading, which is all placement produces. */
    public static Matrix4 transformAt(Vector3 positionWorld, float yawDeg, Matrix4 out) {
        return out.set(positionWorld, new Quaternion(Vector3.Y, yawDeg));
    }
}
