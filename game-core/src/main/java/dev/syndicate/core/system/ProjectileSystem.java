/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.BallisticMotionComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.ProjectileComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.damage.ProjectileImpact;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.SimulationConstants;
import java.util.Objects;

/**
 * Schedule slot 9: flies every shot in the air and resolves the ones that land
 * (docs/04_entity_component_model.md#D04-S4.4, docs/06_physics_simulation.md#D06-S5.9).
 *
 * <p><b>Projectiles are not rigid bodies.</b> Hundreds of small fast objects are expensive in the
 * broadphase, need continuous collision detection to behave at all, and tunnel through thin geometry
 * anyway. A swept ray from where the shot was to where it will be is cheaper, cannot tunnel by
 * construction, and is trivially lag-compensatable when D10-S5.6 arrives — so the trajectory is
 * integrated here, in plain arithmetic, and only the segment goes to Bullet.
 *
 * <p>The integration is the explicit-Euler step D06-S5.9 specifies: gravity, then drag, then
 * position. At {@code TICK_DT} and projectile speeds the error against a closed-form arc is
 * millimetres over a full flight, and using the same integrator the pseudocode names keeps a
 * client's prediction and the authority's answer identical rather than merely close (G2).
 *
 * <p><b>Two ways a shot ends.</b> It hits something, in which case {@link ProjectileImpact} turns
 * the landing into damage events for slot 12; or it runs out of range or lifetime, in which case it
 * is simply destroyed. Either way the entity is gone in the same tick — {@code LifetimeSystem} (16)
 * would also expire it, and the range check here exists because a shot fired straight up reaches its
 * lifetime long before it reaches its range, and a shot fired along the ground the other way round.
 */
public final class ProjectileSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 9;

    private final ProjectileImpact impacts;
    private final boolean authority;

    private Family projectiles;

    private final Vector3 scratchPrevious = new Vector3();
    private final Vector3 scratchNext = new Vector3();
    private final Vector3 scratchGravity = new Vector3(
            SimulationConstants.WORLD_GRAVITY_X,
            SimulationConstants.WORLD_GRAVITY_Y,
            SimulationConstants.WORLD_GRAVITY_Z);

    /**
     * @param authority true where this process owns damage; a predicting client flies the same
     *     projectiles for the look of them and resolves no hits (G15)
     */
    public ProjectileSystem(ProjectileImpact impacts, boolean authority) {
        this.impacts = Objects.requireNonNull(impacts, "impacts");
        this.authority = authority;
    }

    @Override
    public Phase phase() {
        return Phase.SIM;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void initialize(World world) {
        projectiles = world.family(ComponentQuery.all(
                ProjectileComponent.class, BallisticMotionComponent.class, TransformComponent.class));
        impacts.initialize(world);
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        int count = projectiles.size();
        int[] entityIds = projectiles.snapshot();
        for (int i = 0; i < count; i++) {
            int projectileEntity = entityIds[i];
            if (!world.isAlive(projectileEntity)) {
                continue;
            }
            ProjectileComponent projectile = world.getComponent(projectileEntity, ProjectileComponent.class);
            BallisticMotionComponent motion = world.getComponent(projectileEntity, BallisticMotionComponent.class);
            TransformComponent transform = world.getComponent(projectileEntity, TransformComponent.class);
            if (projectile == null || motion == null || transform == null) {
                continue;
            }

            scratchPrevious.set(transform.position);
            motion.velocity.mulAdd(scratchGravity, motion.gravityScale * dtSeconds);
            if (motion.dragCoefficient > 0f) {
                motion.velocity.scl(Math.max(0f, 1f - motion.dragCoefficient * dtSeconds));
            }
            scratchNext.set(scratchPrevious).mulAdd(motion.velocity, dtSeconds);

            float stepLength = scratchPrevious.dst(scratchNext);
            ProjectileImpact.Sweep sweep = authority
                    ? impacts.sweep(world, scratchPrevious, scratchNext, projectile.shooterVehicleEntity)
                    : ProjectileImpact.Sweep.MISS;

            if (sweep.hasHit()) {
                impacts.deliver(
                        world,
                        sweep.hitEntity(),
                        sweep.point(),
                        sweep.normal(),
                        projectile.damageType,
                        projectile.damageAmount,
                        projectile.blastRadiusM,
                        projectile.shooterVehicleEntity,
                        ownerOf(world, projectileEntity),
                        projectile.sourceWeaponGroup,
                        tick);
                world.destroyEntity(projectileEntity);
                continue;
            }

            transform.position.set(scratchNext);
            transform.dirty = true;
            projectile.travelledM += stepLength;
            if (projectile.maxRangeM > 0f && projectile.travelledM >= projectile.maxRangeM) {
                // A round that reaches its range is spent. An explosive one does not detonate: a
                // rocket that ran out of fuel over an empty arena should not blast whatever happens
                // to be beneath it (D01-S4.4 gives ROCKET proximity detonation, not timed).
                world.destroyEntity(projectileEntity);
            }
        }
    }

    private static int ownerOf(World world, int projectileEntity) {
        OwnerComponent owner = world.getComponent(projectileEntity, OwnerComponent.class);
        return owner == null ? EntityId.NULL : owner.ownerEntity;
    }
}
