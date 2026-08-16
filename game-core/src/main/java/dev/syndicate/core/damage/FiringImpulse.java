/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.component.RigidBodyComponent;
import dev.syndicate.core.component.VehicleChassisComponent;
import dev.syndicate.core.ecs.World;
import dev.syndicate.core.physics.PhysicsWorld;
import dev.syndicate.model.WeaponFamily;

/**
 * The two impulses a shot produces: recoil on the firer, knockback on the struck
 * (docs/17_weapon_system.md#D17-S5.12).
 *
 * <p>A static-shaped operation over components, in the mould of {@code PartDetachment} (DEC-016) and
 * {@code DamageApplication} (DEC-038), for the same reason both of those are: two systems need it —
 * {@code WeaponSystem} (8) fires and {@code ProjectileImpact} lands — and D04-R13 prohibits one system
 * calling another.
 *
 * <p><b>Both are authoritative</b> and both go through {@link PhysicsWorld#queueImpulseAt}, never
 * through a direct {@code applyImpulse}. The queue is what makes the result depend on the tick rather
 * than on where in the schedule the call happened: {@code PhysicsSystem} (10) drains it in ascending
 * entity-id order (G3), so two shots landing on the same tick produce the same outcome whatever order
 * the projectiles were created in.
 *
 * <p><b>Applied off the centre of mass on purpose.</b> Both impulses take a lever arm — the muzzle for
 * recoil, the contact point for knockback — because that is the entire gameplay content of the
 * feature. A cannon shell into a front wing spins the target; one into the centreline shoves it
 * straight. Applied at the COM, knockback is a number the player cannot read (D17-R59).
 */
public final class FiringImpulse {

    /**
     * Newton-seconds a single recoil impulse is clamped to (D17-R1, {@code MAX_RECOIL_IMPULSE_NS}).
     *
     * <p>Not a balance number — a guard. The largest shipped recoil is a cannon's 3,000 N·s; this sits
     * four times above it so that no sane weapon reaches it, and stops a content error with a
     * mistyped projectile speed from launching a car into orbit.
     */
    public static final float MAX_RECOIL_IMPULSE_NS = 12_000f;

    /** Multiplier on derived recoil (D17-R1). One: the formula is the tuning. */
    public static final float RECOIL_IMPULSE_SCALE = 1.0f;

    /** Multiplier on derived knockback (D17-R1). */
    public static final float KNOCKBACK_IMPULSE_SCALE = 1.0f;

    /**
     * Below this the impulse is not queued at all.
     *
     * <p>A laser and a flamer carry no momentum, and a machine gun round's 72 N·s on a 1.1 tonne car
     * is 0.06 m/s — real, and worth keeping. This threshold only skips the families whose momentum is
     * exactly zero, so it costs nothing and keeps the queue free of no-op entries.
     */
    private static final float MIN_IMPULSE_NS = 1e-4f;

    private FiringImpulse() {
        throw new AssertionError("no instances");
    }

    /**
     * Queues the kick a vehicle takes for firing one shot (D17-R57).
     *
     * <p>The impulse acts along the <em>negative</em> shot direction at the muzzle, which is what
     * produces the pitch and yaw a real gun does rather than a pure translation.
     *
     * @param family the firing weapon's family; supplies shot mass and recoil fraction
     * @param speedMps the shot's speed, or 0 to take the family's nominal speed
     * @param shotDirectionWorld unit direction the shot left along
     * @param muzzleWorld where it left, in world space
     * @return the impulse magnitude queued, in N·s; 0 when nothing was queued
     */
    public static float queueRecoil(
            PhysicsWorld physics,
            World world,
            int vehicleEntity,
            WeaponFamily family,
            float speedMps,
            Vector3 shotDirectionWorld,
            Vector3 muzzleWorld) {

        if (physics == null || family == null) {
            return 0f;
        }
        float momentum = family.shotMomentumNs(speedMps) * family.recoilFraction() * RECOIL_IMPULSE_SCALE;
        momentum = Math.min(momentum, MAX_RECOIL_IMPULSE_NS);
        if (!(momentum > MIN_IMPULSE_NS)) {
            return 0f;
        }
        Vector3 lever = leverArm(world, vehicleEntity, muzzleWorld);
        if (lever == null) {
            return 0f;
        }
        Vector3 impulse = new Vector3(shotDirectionWorld).nor().scl(-momentum);
        physics.queueImpulseAt(vehicleEntity, impulse, lever);
        return momentum;
    }

    /**
     * Queues the shove a vehicle takes for being hit by one shot (D17-R58).
     *
     * <p>Along the shot's direction of travel, at the contact point. Unreduced by
     * {@code recoilFraction}: a rocket that did not kick its launcher still arrives carrying all of
     * its momentum.
     *
     * @return the impulse magnitude queued, in N·s; 0 when nothing was queued
     */
    public static float queueKnockback(
            PhysicsWorld physics,
            World world,
            int vehicleEntity,
            WeaponFamily family,
            float speedMps,
            Vector3 shotDirectionWorld,
            Vector3 contactWorld) {

        if (physics == null || family == null) {
            return 0f;
        }
        float momentum = family.shotMomentumNs(speedMps) * KNOCKBACK_IMPULSE_SCALE;
        if (!(momentum > MIN_IMPULSE_NS)) {
            return 0f;
        }
        Vector3 lever = leverArm(world, vehicleEntity, contactWorld);
        if (lever == null) {
            return 0f;
        }
        Vector3 impulse = new Vector3(shotDirectionWorld).nor().scl(momentum);
        physics.queueImpulseAt(vehicleEntity, impulse, lever);
        return momentum;
    }

    /**
     * The offset from a vehicle's world centre of mass to {@code pointWorld}, or null when the entity
     * has no body to push.
     *
     * <p>Bullet's {@code applyImpulse} takes its position argument relative to the body's centre of
     * mass, and a vehicle's body origin <em>is</em> its COM because {@code VehicleFactory} recentres
     * the compound on it (DEC-021). Reading the body transform's translation is therefore reading the
     * COM, and doing it this way rather than adding {@code centerOfMassLocal} keeps the two from
     * drifting apart when a part detaches and slot 15 moves the COM (G10).
     */
    private static Vector3 leverArm(World world, int vehicleEntity, Vector3 pointWorld) {
        if (world == null || !world.isAlive(vehicleEntity)) {
            return null;
        }
        if (world.getComponent(vehicleEntity, VehicleChassisComponent.class) == null) {
            return null;
        }
        RigidBodyComponent body = world.getComponent(vehicleEntity, RigidBodyComponent.class);
        if (body == null || body.body == null) {
            return null;
        }
        Vector3 com = new Vector3();
        body.body.getWorldTransform().getTranslation(com);
        return new Vector3(pointWorld).sub(com);
    }
}
