/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.physics.bullet.collision.CollisionConstants;
import dev.syndicate.core.component.DebrisTagComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.physics.DebrisFactory;
import dev.syndicate.core.physics.DestructionTestScene;
import dev.syndicate.core.physics.DestructionTestScene.PartSpec;
import dev.syndicate.core.util.NativeResourceTracker;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;
import dev.syndicate.model.SimulationConstants;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Schedule slot 16 (docs/04_entity_component_model.md#D04-S4.4,
 * docs/07_damage_destruction_model.md#D07-S5.8).
 */
@Tag("integration")
class LifetimeSystemTest {

    private static final AssetId ASSEMBLY = AssetId.of("assembly_medium_01");
    private static final float CHASSIS_MASS_KG = 1260f;
    private static final float PLATE_MASS_KG = 340f;

    private DestructionTestScene scene;
    private Family debris;
    private int vehicle;

    @BeforeEach
    void setUp() {
        NativeResourceTracker.install();
        scene = new DestructionTestScene(1337L);
        vehicle = scene.spawnVehicle(
                ASSEMBLY,
                List.of(
                        PartSpec.of("root", PartCategory.CHASSIS, CHASSIS_MASS_KG, new Vector3()),
                        PartSpec.of("root/panel_front", PartCategory.PANEL, PLATE_MASS_KG, new Vector3(0f, 0f, 2f))),
                new Vector3(0f, 40f, 0f));
        debris = scene.world().family(ComponentQuery.all(DebrisTagComponent.class));
    }

    @AfterEach
    void tearDown() {
        scene.close();
        assertThat(NativeResourceTracker.outstanding())
                .as(NativeResourceTracker.describeOutstanding())
                .isZero();
        NativeResourceTracker.uninstall();
    }

    @Test
    void system_occupiesSlot16OfPostSim() {
        // AC-D04-3. Slot 16 is after the structural systems (13, 14) that create debris and after
        // MassPropertySystem (15), so an entity is never destroyed between a detach and the mass
        // recompute that has to see it.
        assertThat(scene.lifetimeSystem().order()).isEqualTo(16);
        assertThat(scene.lifetimeSystem().phase()).isEqualTo(Phase.POST_SIM);
    }

    @Test
    void aLifetime_countsDownByOneTickPerTick() {
        // D07-S5.8. dt is always TICK_DT (G2), so the countdown is frame-rate independent by
        // construction rather than by care at the call site.
        int entity = spawnTimedEntity(1.0f, LifetimeComponent.DespawnPolicy.DESTROY);

        scene.step(10);

        assertThat(scene.world().getComponent(entity, LifetimeComponent.class).remainingS)
                .isEqualTo(1.0f - 10 * SimulationConstants.TICK_DT, within(1e-4f));
    }

    @Test
    void anExpiredLifetime_destroysTheEntity() {
        // D07-S5.8. Destruction is deferred to CLEANUP, so the entity is not alive from this tick on
        // but its teardown ran in slot 27 of the same tick (D04-R15).
        // Two and a half ticks, not three: an exact multiple of TICK_DT lands on zero only if the
        // float subtractions happen to cancel, and the thing under test is the crossing, not that.
        int entity = spawnTimedEntity(2.5f * SimulationConstants.TICK_DT, LifetimeComponent.DespawnPolicy.DESTROY);

        scene.step(2);
        assertThat(scene.world().isAlive(entity)).isTrue();

        scene.step();

        assertThat(scene.world().isAlive(entity)).isFalse();
    }

    @Test
    void debrisFromADetachedPart_despawnsWhenItsLifetimeRunsOut() {
        // AC-D07-17's other half, and the reason this system exists: before it, MAX_DEBRIS_BODIES
        // recycling was the only thing bounding the debris population, so a quiet corner of the
        // arena kept its scrap for the whole match.
        scene.destroyPart(scene.partAt(vehicle, "root/panel_front"));
        scene.step();
        assertThat(debris.size()).isEqualTo(1);

        int plateBody = debris.snapshot()[0];
        // The plate leaves with DEBRIS_LIFETIME_S. Nothing else in the scene touches it, and it is
        // falling, so the sleep path cannot retire it early.
        scene.step((int) Math.ceil(SimulationConstants.DEBRIS_LIFETIME_S / SimulationConstants.TICK_DT));

        assertThat(scene.world().isAlive(plateBody)).isFalse();
        assertThat(debris.size()).isZero();
    }

    @Test
    void aSleepingBody_isRetiredEarlyUnderSleepThenDestroy() {
        // D06-R29. A settled pile of scrap costs broadphase work every tick and adds nothing, so it
        // goes before its lifetime is up. The sleep clock is Bullet's own deactivation time, which
        // freezes once the body reaches ISLAND_SLEEPING and is kept running by slot 16 (DISC-010).
        int entity = spawnTimedEntity(
                SimulationConstants.DEBRIS_LIFETIME_S, LifetimeComponent.DespawnPolicy.SLEEP_THEN_DESTROY);
        // No body: the sleep test cannot fire, so only the lifetime can retire it.
        scene.step(60);
        assertThat(scene.world().isAlive(entity)).isTrue();

        // A body that is already asleep on the tick it is examined reaches the threshold after
        // SLEEP_DESPAWN_S of ticks and no sooner.
        int sleeper = spawnSleepingDebris();
        int ticksToSleepOut = (int) Math.ceil(DebrisFactory.SLEEP_DESPAWN_S / SimulationConstants.TICK_DT);

        scene.step(ticksToSleepOut - 1);
        assertThat(scene.world().isAlive(sleeper)).isTrue();

        scene.step(2);
        assertThat(scene.world().isAlive(sleeper)).isFalse();
    }

    /** An entity that carries nothing but a lifetime — the case with no body to ask about sleep. */
    private int spawnTimedEntity(float remainingS, LifetimeComponent.DespawnPolicy policy) {
        Entity entity = scene.world().createEntity();
        LifetimeComponent lifetime = new LifetimeComponent();
        lifetime.remainingS = remainingS;
        lifetime.despawnPolicy = policy;
        scene.world().addComponent(entity.id(), lifetime);
        return entity.id();
    }

    /**
     * A debris entity whose body is already marked as sleeping.
     *
     * <p>Forced rather than waited for: letting Bullet put a shard to sleep naturally takes a
     * landing plus its own two-second deactivation threshold, and the thing under test is what slot
     * 16 does once a body is asleep, not Bullet's decision to sleep it.
     */
    private int spawnSleepingDebris() {
        scene.destroyPart(scene.partAt(vehicle, "root"));
        scene.step();
        int[] ids = debris.snapshot();
        int newest = ids[debris.size() - 1];
        RigidBodyComponent body = scene.world().getComponent(newest, RigidBodyComponent.class);
        body.body.setActivationState(CollisionConstants.ISLAND_SLEEPING);
        body.body.setDeactivationTime(0f);
        return newest;
    }
}
