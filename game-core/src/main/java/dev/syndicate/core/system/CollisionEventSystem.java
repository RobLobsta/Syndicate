/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.btCollisionObject;
import com.badlogic.gdx.physics.bullet.collision.btDispatcher;
import com.badlogic.gdx.physics.bullet.collision.btManifoldPoint;
import com.badlogic.gdx.physics.bullet.collision.btPersistentManifold;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.damage.CoverageMap;
import dev.syndicate.core.damage.DamageEvent;
import dev.syndicate.core.damage.HitResolution;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.model.DamageType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schedule slot 11: turns Bullet's contact manifolds into damage events
 * (docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.1).
 *
 * <p>Authority only, and the first half of the pair that makes damage happen at all. Before this
 * system existed the only way a part lost hit points was a test reaching in and declaring it
 * destroyed; with it, driving a vehicle into a wall at speed does what a player expects.
 *
 * <p><b>Damage is momentum, not speed</b> (D07-S5.2 {@code collisionDamage}). The solver's applied
 * impulse over a manifold, in newton-seconds, is what this reads — so a heavy vehicle hits harder
 * than a light one at the same speed, which is the whole of P3 and the reason mass is worth
 * managing. {@link #COLLISION_DAMAGE_THRESHOLD_NS} exists so that scraping along a wall is free
 * (D07-E14): without it, every vehicle would grind itself down on the scenery.
 *
 * <p><b>Why manifolds are read after the step rather than collected during it.</b> Bullet offers a
 * contact-added callback, which fires from inside the solver in an order that depends on the
 * broadphase's internal state — the classic way a simulation stops being reproducible. Reading the
 * dispatcher's manifold array after {@code PhysicsSystem} (10) has stepped gives the same
 * information, and this system sorts what it finds by the entity pair before emitting anything (G3),
 * so two peers stepping identical worlds emit identical events in identical order.
 *
 * <p><b>Debris is inert.</b> A contact where either side is on the debris layer produces nothing
 * (D06-R10, AC-D07-16): a player cannot be killed by their own scrap, and a shard cloud never acts
 * as spaced armour.
 */
public final class CollisionEventSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 11;

    /**
     * Newton-seconds below which a contact does no damage (D07-S5.2).
     *
     * <p>A 1500 kg vehicle stopping dead from 20 m/s is 30,000 N·s, so this is one twentieth of a
     * serious crash — comfortably above the impulse of resting on the ground or brushing a kerb,
     * comfortably below anything a player would describe as a collision.
     */
    public static final float COLLISION_DAMAGE_THRESHOLD_NS = 1500f;

    /** Hit points per newton-second above the threshold (D07-S5.2). */
    public static final float COLLISION_DAMAGE_SCALE = 0.02f;

    /** One vehicle's share of a contact pair's damage. Both sides take the full figure (T-D07-24). */
    private record Contact(
            int entityA,
            int entityB,
            int childIndexA,
            int childIndexB,
            float impulseNs,
            Vector3 point,
            Vector3 normal) {}

    private final PhysicsWorld physics;
    private final AssetIndex assets;
    private final HitResolution hits;

    private Family bodies;

    /** Bullet pointer → entity, rebuilt each tick. See {@link #rebuildBodyIndex}. */
    private final Map<Long, Integer> entityByBodyPointer = new HashMap<>();

    private final List<Contact> contacts = new ArrayList<>();
    private final CoverageMap coverage = new CoverageMap();
    private final Vector3 scratchPoint = new Vector3();
    private final Vector3 scratchNormal = new Vector3();

    public CollisionEventSystem(PhysicsWorld physics, AssetIndex assets, HitResolution hits) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.hits = Objects.requireNonNull(hits, "hits");
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
        bodies = world.family(ComponentQuery.all(RigidBodyComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        contacts.clear();
        collectContacts(world);
        if (contacts.isEmpty()) {
            return;
        }
        // Sorted by the entity pair so the events reach slot 12 in an order that is a function of
        // the world's state rather than of Bullet's broadphase (G3).
        contacts.sort(Comparator.<Contact>comparingInt(Contact::entityA).thenComparingInt(Contact::entityB));
        for (Contact contact : contacts) {
            emitDamage(world, contact, tick);
        }
        contacts.clear();
    }

    // ---- Reading the manifolds -------------------------------------------------------

    /**
     * Reads this tick's manifolds and keeps the ones that could hurt something.
     *
     * <p>One {@link Contact} per manifold, carrying the manifold's <em>largest</em> contact point
     * impulse rather than the sum. A manifold between two boxes meeting flat holds four points that
     * together describe one impact; summing them would quadruple the damage for a flat hit and leave
     * a corner hit unchanged, which is exactly backwards.
     */
    private void collectContacts(World world) {
        rebuildBodyIndex(world);
        btDispatcher dispatcher = physics.dynamicsWorld().getDispatcher();
        int manifoldCount = dispatcher.getNumManifolds();
        for (int i = 0; i < manifoldCount; i++) {
            btPersistentManifold manifold = dispatcher.getManifoldByIndexInternal(i);
            int contactCount = manifold.getNumContacts();
            if (contactCount == 0) {
                continue;
            }
            int entityA = entityOf(manifold.getBody0());
            int entityB = entityOf(manifold.getBody1());
            if (entityA == EntityId.NULL && entityB == EntityId.NULL) {
                continue;
            }
            if (isDebris(world, entityA) || isDebris(world, entityB)) {
                // D06-R10: debris deals no damage. Nothing further to compute.
                continue;
            }
            if (!isDamageable(world, entityA) && !isDamageable(world, entityB)) {
                continue;
            }

            float strongestImpulse = 0f;
            int childIndexA = -1;
            int childIndexB = -1;
            for (int p = 0; p < contactCount; p++) {
                btManifoldPoint point = manifold.getContactPoint(p);
                float impulse = point.getAppliedImpulse();
                if (impulse <= strongestImpulse) {
                    continue;
                }
                strongestImpulse = impulse;
                childIndexA = point.getIndex0();
                childIndexB = point.getIndex1();
                point.getPositionWorldOnB(scratchPoint);
                point.getNormalWorldOnB(scratchNormal);
            }
            if (strongestImpulse < COLLISION_DAMAGE_THRESHOLD_NS) {
                // D07-E14: scraping a wall is free.
                continue;
            }
            contacts.add(new Contact(
                    entityA,
                    entityB,
                    childIndexA,
                    childIndexB,
                    strongestImpulse,
                    new Vector3(scratchPoint),
                    new Vector3(scratchNormal)));
        }
    }

    /**
     * Rebuilds the Bullet-pointer-to-entity index.
     *
     * <p>gdx-bullet offers {@code btCollisionObject.setUserValue(int)}, which would carry the entity
     * id on the body itself and save this walk. It is not used, because it would have to be written
     * at every place a body is created — the vehicle factory, the debris factory, the arena, and
     * every test that builds a body by hand — and a body that missed the call is a contact silently
     * attributed to nothing. Rebuilding from the {@code RigidBodyComponent} family cannot miss one:
     * the family <em>is</em> the set of bodies the simulation knows about.
     */
    private void rebuildBodyIndex(World world) {
        entityByBodyPointer.clear();
        int count = bodies.size();
        int[] entityIds = bodies.snapshot();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            RigidBodyComponent rigidBody = world.getComponent(entityId, RigidBodyComponent.class);
            if (rigidBody != null && rigidBody.body != null) {
                entityByBodyPointer.put(rigidBody.body.getCPointer(), entityId);
            }
        }
    }

    private int entityOf(btCollisionObject object) {
        return object == null ? EntityId.NULL : entityByBodyPointer.getOrDefault(object.getCPointer(), EntityId.NULL);
    }

    private static boolean isDebris(World world, int entityId) {
        return entityId != EntityId.NULL && world.hasComponent(entityId, DebrisTagComponent.class);
    }

    private static boolean isDamageable(World world, int entityId) {
        return entityId != EntityId.NULL && world.hasComponent(entityId, VehicleChassisComponent.class);
    }

    // ---- Emitting the damage ---------------------------------------------------------

    /**
     * Emits one damage event per damageable side of a contact (T-D07-24).
     *
     * <p>Both vehicles in a head-on collision take damage from the same impulse: the impulse is what
     * the pair exchanged, and each side's structure absorbed all of it. Attribution goes to the
     * <em>other</em> vehicle, so ramming someone to death is a kill rather than a suicide, and the
     * normal is flipped for the second side because a manifold's normal points one way only.
     */
    private void emitDamage(World world, Contact contact, long tick) {
        float damage = COLLISION_DAMAGE_SCALE * (contact.impulseNs() - COLLISION_DAMAGE_THRESHOLD_NS);
        if (damage <= 0f) {
            return;
        }
        emitFor(world, contact.entityA(), contact.entityB(), contact.childIndexA(), contact, damage, false, tick);
        emitFor(world, contact.entityB(), contact.entityA(), contact.childIndexB(), contact, damage, true, tick);
    }

    private void emitFor(
            World world,
            int victimVehicle,
            int attackerVehicle,
            int childIndex,
            Contact contact,
            float damage,
            boolean flipNormal,
            long tick) {

        if (!isDamageable(world, victimVehicle)) {
            return;
        }
        coverage.rebuild(world, assets, victimVehicle);
        HitResolution.Resolved resolved = hits.resolve(world, victimVehicle, childIndex, contact.point(), coverage);
        if (!resolved.hasPart()) {
            return;
        }
        scratchNormal.set(contact.normal());
        if (flipNormal) {
            scratchNormal.scl(-1f);
        }
        // Pipeline rather than same-tick only: slot 12 drains the same-tick queue in this tick, and
        // presentation subscribes to the deferred one. Emitting only same-tick meant a hit that took
        // health off a car threw no sparks and made no noise, because the event had already been
        // consumed by the time slots 24 and 25 ran. Still D04-R14's exception, not a widening of it.
        world.events()
                .emitPipeline(DamageEvent.direct(
                        resolved.partEntity(),
                        isDamageable(world, attackerVehicle) ? attackerVehicle : EntityId.NULL,
                        ownerOf(world, attackerVehicle),
                        DamageType.COLLISION,
                        damage,
                        contact.point(),
                        scratchNormal,
                        tick,
                        DamageEvent.NO_WEAPON_GROUP));
    }

    /**
     * The player behind a vehicle, or {@link EntityId#NULL}.
     *
     * <p>A rammed vehicle's damage is credited to whoever was driving the other one, which is what
     * makes ramming a way to score rather than a way to be scored against (D01-S4.4 {@code RAM}).
     */
    private static int ownerOf(World world, int vehicleEntity) {
        if (vehicleEntity == EntityId.NULL) {
            return EntityId.NULL;
        }
        OwnerComponent owner = world.getComponent(vehicleEntity, OwnerComponent.class);
        return owner == null ? EntityId.NULL : owner.ownerEntity;
    }
}
