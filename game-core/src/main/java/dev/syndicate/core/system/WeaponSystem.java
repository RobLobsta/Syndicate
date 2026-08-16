/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.asset.AssetIndex;
import dev.syndicate.core.asset.PartType;
import dev.syndicate.core.asset.WeaponBlock;
import dev.syndicate.core.component.BallisticMotionComponent;
import dev.syndicate.core.component.DamageStateComponent;
import dev.syndicate.core.component.LifetimeComponent;
import dev.syndicate.core.component.MatchStateComponent;
import dev.syndicate.core.component.OwnerComponent;
import dev.syndicate.core.component.PartRefComponent;
import dev.syndicate.core.component.PartStatsComponent;
import dev.syndicate.core.component.PlayerInputComponent;
import dev.syndicate.core.component.ProjectileComponent;
import dev.syndicate.core.component.SlotGraphComponent;
import dev.syndicate.core.component.TransformComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.component.WeaponControllerComponent;
import dev.syndicate.core.damage.FiringImpulse;
import dev.syndicate.core.damage.ProjectileImpact;
import dev.syndicate.core.damage.WeaponFiredEvent;
import dev.syndicate.core.ecs.ComponentQuery;
import dev.syndicate.core.ecs.Entity;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Family;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.core.util.Pcg32;
import dev.syndicate.core.util.StreamId;
import dev.syndicate.core.vehicle.PartPlacement;
import dev.syndicate.core.vehicle.SlotChain;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.DamageState;
import dev.syndicate.model.MatchPhase;
import dev.syndicate.model.WeaponFamily;
import java.util.Objects;

/**
 * Schedule slot 8: turns a held trigger into shots
 * (docs/04_entity_component_model.md#D04-S4.4, docs/01_product_game_design.md#D01-S4.4).
 *
 * <p>Runs on the authority, and on a client as prediction (D03-S5.2) — the predicted variant spawns
 * the same projectiles and authors no damage, because a client that decided its own hits would be
 * authoring gameplay (G15).
 *
 * <p><b>What a weapon needs before it can fire.</b> Five conditions, and each of them is a rule from
 * somewhere else in the suite: the weapon part is not destroyed (D01-E6 — firing stops at the tick
 * the part reaches 0 HP), the cooldown has elapsed, ammunition remains, heat is below full, and the
 * match is {@code ACTIVE} (D01-E12 — no firing during countdown). The cooldown itself is the
 * <em>effective</em> fire interval, which {@code VehicleStatsSystem} (6) recomputed one phase ago
 * from this tick's health, so a damaged autocannon has already slowed down by the time it is asked
 * to fire.
 *
 * <p><b>Two ways a shot reaches its target</b>, chosen by the weapon's family rather than inferred
 * from a speed (D01-S4.4). A ballistic family spawns a projectile entity that {@code
 * ProjectileSystem} (9) integrates over the following ticks; a hitscan or continuous family resolves
 * in this tick with a single ray test. Both end in the same place — a {@link ProjectileImpact} —
 * so the damage path does not care which kind of gun produced the hit.
 *
 * <p><b>Spread is seeded</b> from the {@code DAMAGE_SPREAD} stream (D06-S5.8), not from an ad-hoc
 * random: two peers replaying the same inputs must put the same pellets in the same places, or the
 * authority and a predicting client disagree about a shotgun blast (G4).
 */
public final class WeaponSystem implements EntitySystem {

    /** This system's fixed slot in the D04-S4.4 catalogue. */
    public static final int ORDER = 8;

    /**
     * Heat, {@code [0,1]}, at or above which a weapon stops firing until it cools.
     *
     * <p>Exactly 1: heat is normalised so that a part's authored {@code heatPerShot} is a fraction
     * of the weapon's capacity, which keeps the cap out of content and the tuning in it.
     */
    public static final float HEAT_LOCKOUT = 1.0f;

    private final AssetIndex assets;
    private final ProjectileImpact impacts;
    private final PhysicsWorld physics;
    private final boolean authority;

    private Family weapons;

    private final Matrix4 scratchChassisToWorld = new Matrix4();
    private final Matrix4 scratchPartToWorld = new Matrix4();
    private final Vector3 scratchComWorld = new Vector3();
    private final Vector3 scratchMuzzle = new Vector3();
    private final Vector3 scratchAim = new Vector3();

    /**
     * @param physics the world recoil is queued on (D17-S5.12). Queued rather than applied, so the
     *     kick lands in {@code PhysicsSystem}'s sorted drain and not in the middle of slot 8
     * @param authority true on a process that owns the world's damage; false for the client-side
     *     predicted variant, which spawns projectiles for the look of them and resolves no hits
     */
    public WeaponSystem(AssetIndex assets, ProjectileImpact impacts, PhysicsWorld physics, boolean authority) {
        this.assets = Objects.requireNonNull(assets, "assets");
        this.impacts = Objects.requireNonNull(impacts, "impacts");
        this.physics = Objects.requireNonNull(physics, "physics");
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
        weapons = world.family(ComponentQuery.all(
                WeaponControllerComponent.class, PartRefComponent.class, DamageStateComponent.class));
    }

    @Override
    public void update(World world, float dtSeconds, long tick) {
        boolean weaponsLive = matchAllowsFiring(world);
        int count = weapons.size();
        int[] entityIds = weapons.snapshot();
        for (int i = 0; i < count; i++) {
            int partEntity = entityIds[i];
            if (!world.isAlive(partEntity)) {
                continue;
            }
            WeaponControllerComponent weapon = world.getComponent(partEntity, WeaponControllerComponent.class);
            PartRefComponent partRef = world.getComponent(partEntity, PartRefComponent.class);
            DamageStateComponent damageState = world.getComponent(partEntity, DamageStateComponent.class);
            if (weapon == null || partRef == null || damageState == null) {
                continue;
            }

            // Cooldown and heat advance whatever else is true, so a weapon holstered through a
            // countdown is ready when the match starts rather than a full interval behind it.
            weapon.cooldownRemainingS = Math.max(0f, weapon.cooldownRemainingS - dtSeconds);
            weapon.heat = Math.max(0f, weapon.heat - WeaponBlock.HEAT_COOLING_PER_SECOND * dtSeconds);

            if (!weaponsLive) {
                continue;
            }
            if (damageState.state == DamageState.DESTROYED || damageState.state == DamageState.DETACHED) {
                // D01-E6: firing stops at the tick the part dies. Shots already in flight persist.
                continue;
            }
            int vehicleEntity = partRef.vehicleEntity;
            if (vehicleEntity == EntityId.NULL || !world.isAlive(vehicleEntity)) {
                continue;
            }
            PlayerInputComponent input = world.getComponent(vehicleEntity, PlayerInputComponent.class);
            if (input == null || (input.fireMask & (1 << weapon.groupIndex)) == 0) {
                continue;
            }
            PartType type = assets.partType(partRef.partTypeId);
            WeaponBlock block = type == null ? null : type.weapon();
            if (block == null) {
                continue;
            }
            if (weapon.cooldownRemainingS > 0f || weapon.ammoRemaining == 0 || weapon.heat >= HEAT_LOCKOUT) {
                continue;
            }
            fire(world, partEntity, vehicleEntity, weapon, partRef, block, input, tick, dtSeconds);
        }
    }

    // ---- Firing ----------------------------------------------------------------------

    /** Places the muzzle, aims it, and delivers one shot of whatever kind the family is. */
    private void fire(
            World world,
            int partEntity,
            int vehicleEntity,
            WeaponControllerComponent weapon,
            PartRefComponent partRef,
            WeaponBlock block,
            PlayerInputComponent input,
            long tick,
            float dtSeconds) {

        if (!muzzleWorld(world, vehicleEntity, partRef, weapon, scratchMuzzle)) {
            return;
        }
        PartStatsComponent stats = world.getComponent(partEntity, PartStatsComponent.class);
        float damagePerShot = statOrDefault(stats, StatBlock.Stat.DAMAGE_PER_SHOT, WeaponBlock.DEFAULT_DAMAGE_PER_SHOT);
        // Zero spread and zero heat are both meaningful: a laser has neither.
        float spreadRad = stat(stats, StatBlock.Stat.SPREAD_RAD);
        float heatPerShot = stat(stats, StatBlock.Stat.HEAT_PER_SHOT);
        float projectileSpeed =
                statOrDefault(stats, StatBlock.Stat.PROJECTILE_SPEED_MPS, WeaponBlock.DEFAULT_PROJECTILE_SPEED_MPS);

        Pcg32 spread = world.random().stream(StreamId.DAMAGE_SPREAD);
        aimDirection(input.aimYawRad, input.aimPitchRad, spreadRad, spread, scratchAim);

        int ownerPlayer = ownerOf(world, vehicleEntity);
        if (block.family().spawnsProjectile()) {
            spawnProjectile(
                    world,
                    block,
                    scratchMuzzle,
                    scratchAim,
                    projectileSpeed,
                    damagePerShot,
                    vehicleEntity,
                    ownerPlayer,
                    weapon.groupIndex,
                    tick);
        } else if (authority) {
            // Hitscan and continuous families resolve now. A continuous weapon deals its damage per
            // second rather than per shot, so the beam's damage scales with the tick it burned for
            // — otherwise a laser's damage would depend on the tick rate (G2).
            float amount = block.family().isContinuous() ? damagePerShot * dtSeconds : damagePerShot;
            ProjectileImpact.Sweep sweep = impacts.resolveHitscan(
                    world,
                    scratchMuzzle,
                    scratchAim,
                    block.effectiveRangeM(),
                    block.damageType(),
                    amount,
                    0f,
                    vehicleEntity,
                    ownerPlayer,
                    weapon.groupIndex,
                    tick);
            // A hitscan shot still carries momentum (D17-R58). Its speed is the family's nominal one,
            // because a shot that arrives in the tick it was fired has no travelling entity to read a
            // speed from — a shotgun kicks, a laser does not, and the family table is what says which.
            if (sweep.hasHit()) {
                impacts.queueKnockback(world, sweep.hitEntity(), block.family(), 0f, scratchAim, sweep.point());
            }
        }

        // Recoil (D17-R57). Queued on every peer that runs slot 8, predicted and authoritative alike:
        // the kick is part of the vehicle's motion, so a client that predicted the shot and not the
        // shove would reconcile a correction on every trigger pull.
        FiringImpulse.queueRecoil(
                physics, world, vehicleEntity, block.family(), projectileSpeed, scratchAim, scratchMuzzle);

        // Deferred, not same-tick: PRESENT systems run after the tick, so an emitSameTick event is
        // drained before slot 25 or slot 24 could ever see it (DISC-022). This is the shot's only
        // cosmetic trace — the simulation reads none of it.
        world.events().emit(new WeaponFiredEvent(partEntity, vehicleEntity, block.family(), scratchMuzzle, tick));

        weapon.cooldownRemainingS = block.family().isContinuous() ? 0f : Math.max(0f, weapon.effectiveFireIntervalS);
        weapon.heat = Math.min(HEAT_LOCKOUT, weapon.heat + heatPerShot);
        if (weapon.ammoRemaining > 0) {
            weapon.ammoRemaining--;
        }
    }

    /**
     * Creates the projectile entity a ballistic family fires (D06-S5.9).
     *
     * <p>It carries no rigid body. Hundreds of small fast bodies are expensive, need continuous
     * collision detection, and tunnel anyway; a swept ray per tick is cheaper and cannot miss
     * (D06-S5.9). {@code MORTAR} keeps full gravity so it arcs; the flatter families scale it down so
     * a 600 m/s round does not visibly drop over 200 m.
     */
    private static void spawnProjectile(
            World world,
            WeaponBlock block,
            Vector3 muzzleWorld,
            Vector3 direction,
            float speedMps,
            float damageAmount,
            int shooterVehicle,
            int ownerPlayer,
            int weaponGroup,
            long tick) {

        Entity entity = world.createEntity();
        int projectileEntity = entity.id();

        TransformComponent transform = new TransformComponent();
        transform.position.set(muzzleWorld);
        world.addComponent(projectileEntity, transform);

        BallisticMotionComponent motion = new BallisticMotionComponent();
        motion.velocity.set(direction).scl(speedMps);
        motion.gravityScale = block.family() == WeaponFamily.MORTAR || block.family() == WeaponFamily.CANNON ? 1f : 0f;
        world.addComponent(projectileEntity, motion);

        ProjectileComponent projectile = new ProjectileComponent();
        projectile.family = block.family();
        projectile.damageType = block.damageType();
        projectile.damageAmount = damageAmount;
        projectile.blastRadiusM = block.blastRadiusM();
        projectile.maxRangeM = block.effectiveRangeM();
        projectile.shooterVehicleEntity = shooterVehicle;
        projectile.sourceWeaponGroup = weaponGroup;
        world.addComponent(projectileEntity, projectile);

        OwnerComponent owner = new OwnerComponent();
        owner.ownerEntity = ownerPlayer;
        world.addComponent(projectileEntity, owner);

        // A range bound and a time bound, because a shot fired straight up never reaches its range.
        LifetimeComponent lifetime = new LifetimeComponent();
        lifetime.remainingS = speedMps > 0f ? block.effectiveRangeM() / speedMps : 1f;
        lifetime.despawnPolicy = LifetimeComponent.DespawnPolicy.DESTROY;
        world.addComponent(projectileEntity, lifetime);
    }

    // ---- Geometry --------------------------------------------------------------------

    /**
     * Writes a weapon's muzzle position in world space.
     *
     * @return false when the vehicle has no body to place the part from
     */
    private boolean muzzleWorld(
            World world, int vehicleEntity, PartRefComponent partRef, WeaponControllerComponent weapon, Vector3 out) {

        VehicleChassisComponent chassis = world.getComponent(vehicleEntity, VehicleChassisComponent.class);
        SlotGraphComponent graph = world.getComponent(vehicleEntity, SlotGraphComponent.class);
        if (chassis == null
                || graph == null
                || !PartPlacement.chassisToWorld(world, vehicleEntity, scratchChassisToWorld, scratchComWorld)) {
            return false;
        }
        Matrix4 chainTransform = SlotChain.of(graph, chassis).transformOf(partRef.slotPath);
        if (chainTransform == null) {
            return false;
        }
        scratchPartToWorld.set(scratchChassisToWorld).mul(chainTransform);
        out.set(weapon.muzzleLocal).mul(scratchPartToWorld);
        return true;
    }

    /**
     * Writes a unit aim direction from a yaw and pitch, scattered by the weapon's spread.
     *
     * <p>Forward is {@code -Z} at zero yaw (D00-R15). The scatter is two independent uniform angles
     * inside the cone rather than a rejection-sampled disc: it is cheap, it is deterministic in one
     * draw per axis, and the difference is invisible on a cone a few degrees wide.
     */
    static Vector3 aimDirection(float yawRad, float pitchRad, float spreadRad, Pcg32 random, Vector3 out) {
        float yaw = yawRad;
        float pitch = pitchRad;
        if (spreadRad > 0f && random != null) {
            yaw += random.nextFloat(-spreadRad, spreadRad);
            pitch += random.nextFloat(-spreadRad, spreadRad);
        }
        float cosPitch = (float) Math.cos(pitch);
        return out.set((float) -Math.sin(yaw) * cosPitch, (float) Math.sin(pitch), (float) -Math.cos(yaw) * cosPitch)
                .nor();
    }

    // ---- Match context ---------------------------------------------------------------

    /**
     * Whether the match lets weapons fire (D01-E12, AC-D01-12).
     *
     * <p>True when there is no match singleton at all, so a test range with two vehicles and no match
     * state can still shoot — a rule that silently disabled every weapon in every test would be a
     * long afternoon.
     */
    private static boolean matchAllowsFiring(World world) {
        MatchStateComponent state = world.getComponent(EntityId.MATCH, MatchStateComponent.class);
        return state == null || state.phase == MatchPhase.ACTIVE;
    }

    private static int ownerOf(World world, int vehicleEntity) {
        OwnerComponent owner = world.getComponent(vehicleEntity, OwnerComponent.class);
        return owner == null ? EntityId.NULL : owner.ownerEntity;
    }

    /**
     * A weapon stat after degradation, resolved against zero.
     *
     * <p>Zero, not the fallback: a stat's {@code add} term <em>is</em> the authored value (D05-R15),
     * so resolving against anything else adds the default on top of what content wrote.
     */
    private static float stat(PartStatsComponent stats, StatBlock.Stat stat) {
        return stats == null ? 0f : stats.effectiveStats.resolve(stat, 0f);
    }

    /** As {@link #stat}, with a default for the stats where zero is not a usable value. */
    private static float statOrDefault(PartStatsComponent stats, StatBlock.Stat stat, float fallback) {
        float value = stat(stats, stat);
        return value > 0f ? value : fallback;
    }
}
