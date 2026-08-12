/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.WeaponFamily;
import java.util.Objects;

/**
 * A weapon fired one shot (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R36).
 *
 * <p><b>Cosmetic, and deferred.</b> Nothing in the simulation reads this — the shot's effects are
 * already carried by the projectile entity a ballistic family spawns and by the damage events a
 * hitscan family resolves in the same tick. It exists so that presentation has something to hang a
 * muzzle report and a flash on, which D15-R36 lists as an event family and which the bank has had
 * seven pairs of sounds for since it was built.
 *
 * <p><b>Published on the deferred bus, never {@code emitSameTick}.</b> That is not a style choice:
 * PRESENT systems run after the tick, so an event emitted same-tick is drained before slot 25 could
 * ever see it. Every hit in the game was silent for exactly that reason (DISC-022), and this is the
 * same shape of event arriving at the same consumer.
 *
 * @param weaponPartEntity the weapon part that fired; treat as a label rather than a handle (D04-E1)
 * @param vehicleEntity the vehicle carrying it
 * @param family which family fired, and therefore which of the seven sounds plays
 * @param muzzleWorld where the muzzle was at the moment of the shot. Copied on construction, because
 *     the caller fires from a reused scratch vector
 * @param tick the tick it fired on
 */
public record WeaponFiredEvent(
        int weaponPartEntity, int vehicleEntity, WeaponFamily family, Vector3 muzzleWorld, long tick) {

    public WeaponFiredEvent {
        Objects.requireNonNull(family, "family");
        muzzleWorld = new Vector3(muzzleWorld == null ? Vector3.Zero : muzzleWorld);
    }
}
