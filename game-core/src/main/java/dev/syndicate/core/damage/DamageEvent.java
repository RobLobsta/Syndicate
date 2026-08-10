/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.DamageType;
import java.util.Objects;

/**
 * One unit of damage aimed at one part (docs/07_damage_destruction_model.md#D07-S4.4).
 *
 * <p>Authoritative, and the only way health ever falls. {@code CollisionEventSystem} (11) produces
 * these from Bullet's manifolds, {@code ProjectileSystem} (9) from projectile hits, and
 * {@code DamageSystem} (12) from its own propagation walk — one record type for all three, so the
 * armour formulas, the positional modifiers and the state machine are written once (D07-S5.2).
 *
 * <p><b>{@code isPropagated} and {@code hopCount} are the termination argument.</b> Propagated
 * damage carries no geometry, so it takes no positional modifiers; and it never propagates again,
 * which is what stops a chain of 40 parts becoming an exponential cascade (D07-R14). Both flags are
 * on the event rather than on the applying system because propagation calls the same
 * {@code applyDamage} the direct hit does, and a system-level flag would have to be saved and
 * restored around a recursive call.
 *
 * <p>The two vectors are copied on construction: producers hand over scratch vectors they reuse on
 * the next contact, and an event queued for the next slot must not change underneath it.
 *
 * @param targetPart the part entity this damage resolved to (D07-S5.1)
 * @param attackerVehicle the vehicle that dealt it, or {@link EntityId#NULL} for world damage
 * @param attackerPlayer the player credited, or {@link EntityId#NULL} for world damage
 * @param type which of the five damage types (D07-R8)
 * @param baseAmount hit points before material resistance, armour or any modifier
 * @param hitPointWorld where the hit landed, world space, metres
 * @param hitNormalWorld the contact normal, world space, unit length
 * @param tick the tick the hit happened on
 * @param sourceWeaponGroup the firing group for the damage ledger, or {@link #NO_WEAPON_GROUP}
 * @param isPropagated true for secondary damage; such damage does not propagate again (D07-R14)
 * @param hopCount 0 for a direct hit, 1 or 2 for a propagated one (D07-S5.4)
 */
public record DamageEvent(
        int targetPart,
        int attackerVehicle,
        int attackerPlayer,
        DamageType type,
        float baseAmount,
        Vector3 hitPointWorld,
        Vector3 hitNormalWorld,
        long tick,
        int sourceWeaponGroup,
        boolean isPropagated,
        int hopCount) {

    /** The {@code sourceWeaponGroup} of damage no weapon fired — a collision, or a burn tick. */
    public static final int NO_WEAPON_GROUP = -1;

    public DamageEvent {
        Objects.requireNonNull(type, "type");
        hitPointWorld = new Vector3(hitPointWorld == null ? Vector3.Zero : hitPointWorld);
        hitNormalWorld = new Vector3(hitNormalWorld == null ? Vector3.Zero : hitNormalWorld);
    }

    /** A direct hit: hop 0, not propagated, with geometry that earns positional modifiers. */
    public static DamageEvent direct(
            int targetPart,
            int attackerVehicle,
            int attackerPlayer,
            DamageType type,
            float baseAmount,
            Vector3 hitPointWorld,
            Vector3 hitNormalWorld,
            long tick,
            int sourceWeaponGroup) {

        return new DamageEvent(
                targetPart,
                attackerVehicle,
                attackerPlayer,
                type,
                baseAmount,
                hitPointWorld,
                hitNormalWorld,
                tick,
                sourceWeaponGroup,
                false,
                0);
    }

    /**
     * The hop-{@code n} neighbour of a direct hit (D07-S5.4).
     *
     * <p>Attribution, type and tick are inherited so the player who fired is credited for what the
     * blast reached; the geometry is not, because a neighbour was not struck anywhere.
     */
    public DamageEvent propagatedTo(int neighbourPart, float amount, int hop) {
        return new DamageEvent(
                neighbourPart,
                attackerVehicle,
                attackerPlayer,
                type,
                amount,
                Vector3.Zero,
                Vector3.Zero,
                tick,
                sourceWeaponGroup,
                true,
                hop);
    }
}
