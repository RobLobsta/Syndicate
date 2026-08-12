/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import com.badlogic.gdx.math.Vector3;
import java.util.Objects;

/**
 * A piece of debris came to rest (docs/15_vehicle_preparation_pipeline.md#D15-S8, D15-R36).
 *
 * <p>D15-R36 says debris settle is "driven by the existing debris lifetime", and the bank has had a
 * settle sound per material since it was built. What did not exist was the moment to play it on:
 * {@code LifetimeSystem} (16) knew when a body had been asleep <em>long enough to despawn</em>, which
 * is seconds too late and is a disappearance rather than a landing.
 *
 * <p>This is the transition instead — the tick a body first reaches {@code ISLAND_SLEEPING} — which is
 * the moment a shard stops moving and therefore the moment it makes its last noise. One event per
 * body per settling, not one per tick asleep.
 *
 * <p>Deferred, like every event a PRESENT system consumes (DISC-022).
 *
 * @param entity the debris body that settled
 * @param materialId the material it is made of, so the listener can pick the right settle sound
 *     without a second lookup through a part type that may already be gone
 * @param pointWorld where it came to rest. Copied on construction
 * @param tick the tick it settled on
 */
public record DebrisSettledEvent(int entity, String materialId, Vector3 pointWorld, long tick) {

    public DebrisSettledEvent {
        Objects.requireNonNull(materialId, "materialId");
        pointWorld = new Vector3(pointWorld == null ? Vector3.Zero : pointWorld);
    }
}
