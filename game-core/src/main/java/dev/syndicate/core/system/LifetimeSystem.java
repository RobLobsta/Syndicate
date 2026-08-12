/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.CollisionConstants;
import com.badlogic.gdx.physics.bullet.dynamics.btRigidBody;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.damage.DebrisSettledEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.DebrisFactory;

/**
 * Schedule slot 16: counts every transient entity down and destroys it when it is out of time
 * (docs/04_entity_component_model.md#D04-S4.4, docs/07_damage_destruction_model.md#D07-S5.8).
 *
 * <p>Runs in every mode, not just on the authority (D04-S4.4 row 16). Debris despawn is deliberately
 * <em>not</em> replicated per body (D07-R25): a client spawns its own shards locally and runs this
 * same countdown over them, and a divergence of a few ticks in when a piece of scrap vanishes is
 * invisible. Replicating it would put hundreds of cosmetic entities on the wire to synchronise
 * something nobody can perceive.
 *
 * <p><b>Why this system is what keeps a long match alive.</b> D04-E3 says exhausting
 * {@code MAX_ENTITIES} always means a leak, and debris that never despawns is the leak it means.
 * Until this system existed, the only thing bounding the debris population was
 * {@code MAX_DEBRIS_BODIES} recycling in {@link DebrisFactory} — a backstop doing the job of a
 * policy: every shard lived until 256 newer ones had pushed it out, so a quiet corner of the arena
 * kept its scrap for the whole match while a busy one lost debris that was seconds old.
 *
 * <p><b>The two ways an entity runs out (D07-S5.8).</b> Its lifetime expires, or — under
 * {@link LifetimeComponent.DespawnPolicy#SLEEP_THEN_DESTROY} — its body has slept for
 * {@link DebrisFactory#SLEEP_DESPAWN_S}. The second retires most debris in practice: a settled pile
 * of scrap costs broadphase work every tick and contributes nothing to the game (D06-R29), so it
 * goes early rather than serving out a full {@code DEBRIS_LIFETIME_S}.
 *
 * <p>{@link LifetimeComponent.DespawnPolicy#FADE} destroys on expiry here exactly as
 * {@code DESTROY} does. Despawn is instantaneous on the authority and the fade is a client-side
 * render effect over a mesh whose entity is already gone (D07-R24) — a fade that delayed the
 * destruction would be cosmetic state holding up authoritative state, which is G6 backwards.
 *
 * <p>Destruction is deferred: {@code World.destroyEntity} deactivates the entity immediately and
 * {@code EntityDestroySystem} (27) releases its body in the CLEANUP phase, outside
 * {@code stepSimulation} (D04-R15, D04-E5).
 */
public final class LifetimeSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 16;

    private Family transients;

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
        transients = world.family(ComponentQuery.all(LifetimeComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = transients.size();
        int[] entityIds = transients.snapshot();
        for (int i = 0; i < count; i++) {
            int entityId = entityIds[i];
            LifetimeComponent lifetime = world.getComponent(entityId, LifetimeComponent.class);
            if (lifetime == null) {
                continue;
            }
            lifetime.remainingS -= dtSeconds;

            boolean expired = lifetime.remainingS <= 0f;
            // Evaluated even when the lifetime already expired, because it advances the sleep clock
            // below; short-circuiting past it would leave a body's clock frozen behind the others'.
            boolean slept = lifetime.despawnPolicy == LifetimeComponent.DespawnPolicy.SLEEP_THEN_DESTROY
                    && hasSleptLongEnough(world, entityId, dtSeconds);
            reportSettled(world, entityId, tick);
            if (expired || slept) {
                world.destroyEntity(entityId);
            }
        }
    }

    /**
     * Publishes a settle the first tick a debris body reaches rest.
     *
     * <p><b>The moment matters, and it is not the one this system already knew.</b>
     * {@link #hasSleptLongEnough} answers "has this been still long enough to remove", which is three
     * seconds after the interesting thing happened — a disappearance rather than a landing. D15-R36
     * lists debris settle as an event family and the bank has had a sound per material since it was
     * built; what was missing was a moment to play it on, and this is it: the transition into
     * {@code ISLAND_SLEEPING}.
     *
     * <p>Fires once per body per settling. Bullet reactivates a sleeping body that is struck, so a
     * shard kicked by a passing car settles again and legitimately makes the noise again — which is
     * why the latch is cleared on wake rather than set permanently.
     */
    private void reportSettled(World world, int entityId, long tick) {
        DebrisTagComponent tag = world.getComponent(entityId, DebrisTagComponent.class);
        RigidBodyComponent rigidBody = world.getComponent(entityId, RigidBodyComponent.class);
        if (tag == null || rigidBody == null || rigidBody.body == null) {
            return;
        }
        boolean asleep = rigidBody.body.getActivationState() == CollisionConstants.ISLAND_SLEEPING;
        if (!asleep) {
            tag.hasSettled = false;
            return;
        }
        if (tag.hasSettled) {
            return;
        }
        tag.hasSettled = true;

        TransformComponent transform = world.getComponent(entityId, TransformComponent.class);
        Vector3 at = transform == null ? Vector3.Zero : transform.position;
        // Deferred, because slot 25 is a PRESENT system and would never see a same-tick event
        // (DISC-022).
        world.events().emit(new DebrisSettledEvent(entityId, tag.materialId, at, tick));
    }

    /**
     * Whether this entity's body has been asleep for {@link DebrisFactory#SLEEP_DESPAWN_S}, and
     * advances the clock that answers the question.
     *
     * <p>D07-S5.8 words the test as {@code ticksSince(body.deactivationTick)}, which presumes a
     * recorded moment of falling asleep. Bullet's own clock is {@code btCollisionObject}'s
     * deactivation time: it accumulates while a body is below the sleeping thresholds and is zeroed
     * the instant anything reactivates the body — exactly the "continuous sleep duration, reset on
     * wake" semantics the rule needs, and per-body state rather than a remembered value in this
     * system, which D04-R3 rules out and a client rewinding across a landing would get wrong.
     *
     * <p>It stops accumulating at the moment it becomes useful, though: {@code btRigidBody
     * ::updateDeactivation} returns early once the state is {@code ISLAND_SLEEPING}, so the clock
     * freezes at Bullet's own {@code gDeactivationTime} of 2 s and can never reach 3 (DISC-010).
     * This keeps it running for the second the rule asks for beyond that. Writing it back has no
     * side effect on the simulation: Bullet reads the value only to decide when to deactivate a body
     * that is already deactivated, and zeroes it on reactivation whatever is stored here.
     */
    private boolean hasSleptLongEnough(World world, int entityId, float dtSeconds) {
        RigidBodyComponent rigidBody = world.getComponent(entityId, RigidBodyComponent.class);
        if (rigidBody == null || rigidBody.body == null) {
            // Nothing to sleep, so the lifetime alone decides (D07-S5.8).
            return false;
        }
        btRigidBody body = rigidBody.body;
        if (body.getActivationState() != CollisionConstants.ISLAND_SLEEPING) {
            return false;
        }
        float sleptS = body.getDeactivationTime() + dtSeconds;
        body.setDeactivationTime(sleptS);
        return sleptS >= DebrisFactory.SLEEP_DESPAWN_S;
    }
}
