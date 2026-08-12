/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Vector3;
import dev.syndicate.model.WeaponFamily;
import java.util.Objects;

/**
 * A shot arrived somewhere (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R36).
 *
 * <p>The other half of {@link WeaponFiredEvent}, and deliberately <b>not</b> the same thing as a
 * {@link DamageEvent}. A shot that lands on the arena floor, on a piece of debris, or on nothing at
 * all still makes a noise and still throws a mark; a damage event only exists where a part took the
 * hit. Keying the impact sound off damage would silence every miss, which is most of what a firefight
 * sounds like.
 *
 * <p>Deferred, for the reason {@link WeaponFiredEvent} gives.
 *
 * @param family which family arrived, and therefore which of the seven impact sounds plays
 * @param pointWorld where it landed. Copied on construction
 * @param hitSomething whether it struck a body at all, or expired in the air at the end of its range
 * @param tick the tick it landed on
 */
public record WeaponImpactEvent(WeaponFamily family, Vector3 pointWorld, boolean hitSomething, long tick) {

    public WeaponImpactEvent {
        Objects.requireNonNull(family, "family");
        pointWorld = new Vector3(pointWorld == null ? Vector3.Zero : pointWorld);
    }
}
