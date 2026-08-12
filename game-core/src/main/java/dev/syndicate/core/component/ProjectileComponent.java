/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.ecs.EntityId;
import dev.syndicate.model.DamageType;
import dev.syndicate.model.WeaponFamily;

/**
 * What a shot in flight will do when it lands
 * (docs/04_entity_component_model.md#D04-S4.3.4, docs/06_physics_simulation.md#D06-S5.9).
 *
 * <p>Paired with {@code BallisticMotionComponent}, which says where the shot is going: this says what
 * happens when it gets there. The split matters because a hitscan weapon has the second without the
 * first — a laser resolves in the tick it is fired and never exists as a moving entity.
 *
 * <p><b>The damage is frozen at the muzzle, not read at impact.</b> {@link #damageAmount} is
 * computed from the firing weapon's effective stats when the shot is created and never consulted
 * again. D01-E6 requires exactly this: a weapon destroyed mid-burst stops firing, but the rounds
 * already in the air keep their damage and can still score. Reading the weapon at impact would make
 * a shot weaker because its launcher was blown off after it left.
 *
 * <p>Attribution lives in {@code OwnerComponent}, which names the <em>player</em> rather than the
 * weapon for the same reason (D04-S4.3.4).
 */
public final class ProjectileComponent implements Component {

    /** Which damage type this shot delivers (D07-S4.3). */
    public DamageType damageType = DamageType.KINETIC;

    /** Hit points before material resistance, armour or positional modifiers. */
    public float damageAmount;

    /** Metres. Zero for a point hit; positive for an explosive, which damages every part inside it. */
    public float blastRadiusM;

    /** Metres the shot may travel before it expires. */
    public float maxRangeM;

    /** Metres travelled so far, accumulated per tick by {@code ProjectileSystem}. */
    public float travelledM;

    /** The vehicle that fired it, for the friendly-fire test (D01-E9). */
    public int shooterVehicleEntity = EntityId.NULL;

    /** Which weapon group fired it, for the damage ledger. {@code -1} when nothing did. */
    public int sourceWeaponGroup = -1;

    /**
     * Which family fired it, carried so its landing can be given that family's sound.
     *
     * <p>Cosmetic only. The simulation resolves a landing entirely from {@link #damageType},
     * {@link #damageAmount} and {@link #blastRadiusM}; the family has done its work by the time a
     * round is in the air. It rides along because slot 9 is the only place that still knows which of
     * the seven impact sounds a landing should make, and reconstructing it downstream from damage
     * type and blast radius would be a guess that a shotgun and an autocannon defeat.
     */
    public WeaponFamily family = WeaponFamily.AUTOCANNON;

    @Override
    public void reset() {
        damageType = DamageType.KINETIC;
        damageAmount = 0f;
        blastRadiusM = 0f;
        maxRangeM = 0f;
        travelledM = 0f;
        shooterVehicleEntity = EntityId.NULL;
        sourceWeaponGroup = -1;
        family = WeaponFamily.AUTOCANNON;
    }
}
