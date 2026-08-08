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

    /** {@link #baseFireIntervalS} after the degradation curve (D05-S5.4). */
    public float effectiveFireIntervalS;

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
        ammoRemaining = -1;
        heat = 0f;
        groupIndex = 0;
        muzzleLocal.set(0f, 0f, 0f);
    }
}
