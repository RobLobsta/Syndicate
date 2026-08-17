/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.core.ecs.Component;
import dev.syndicate.model.AssetId;

/**
 * A weapon part's firing state (docs/04_entity_component_model.md#D04-S4.3.4).
 *
 * <p>{@link #baseFireIntervalS} and {@link #effectiveFireIntervalS} are kept apart for the same
 * reason as {@code PartStatsComponent}'s two stat blocks: effective is recomputed from base and the
 * part's health fraction whenever health changes, never decayed in place, so the result depends on
 * the damage taken rather than on how many separate hits delivered it.
 */
public final class WeaponControllerComponent implements Component {

    /** Which weapon type this part is. */
    public AssetId weaponTypeId;

    /** Seconds until this weapon can fire again. Counts down in {@code WeaponSystem}. */
    public float cooldownRemainingS;

    /** Seconds between shots at full health. */
    public float baseFireIntervalS;

    /** {@link #baseFireIntervalS} after the degradation curve (D05-S5.4) and D17-S5.13. */
    public float effectiveFireIntervalS;

    /** Metres a shot may travel, from the weapon block, before any sub-part loss (D17-R61). */
    public float baseRangeM;

    /** {@link #baseRangeM} after D17-S5.13 — halved while the barrel is gone. */
    public float effectiveRangeM;

    /** Rounds this weapon can hold, from the weapon block. {@code -1} is unlimited. */
    public int ammoCapacity = -1;

    /**
     * True while a sub-part loss stops this weapon firing at all — its receiver is gone (D17-R61).
     *
     * <p>Written by {@code VehicleStatsSystem} (6) and read by {@code WeaponSystem} (8), which is
     * what keeps the D17-S5.13 table out of the firing path: slot 8 asks whether it may fire, not
     * why. A weapon with no barrel still fires (D17-R62), which is why this is its own flag rather
     * than something derived from the multipliers beside it.
     */
    public boolean disabledBySubPartLoss;

    /** Rounds left, or {@code -1} for unlimited. */
    public int ammoRemaining = -1;

    /** Heat, {@code [0,1]}. At 1 the weapon stops firing until it cools. */
    public float heat;

    /** Which {@code PlayerInputComponent.fireMask} bit fires this weapon. */
    public int groupIndex;

    /** Muzzle position in the part's local space, metres — where projectiles are spawned. */
    public final Vector3 muzzleLocal = new Vector3();

    @Override
    public void reset() {
        weaponTypeId = null;
        cooldownRemainingS = 0f;
        baseFireIntervalS = 0f;
        effectiveFireIntervalS = 0f;
        baseRangeM = 0f;
        effectiveRangeM = 0f;
        ammoCapacity = -1;
        disabledBySubPartLoss = false;
        ammoRemaining = -1;
        heat = 0f;
        groupIndex = 0;
        muzzleLocal.set(0f, 0f, 0f);
    }
}
