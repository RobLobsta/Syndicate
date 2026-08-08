/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Collision layer masks (docs/06_physics_simulation.md#D06-S4.4). */
@Tag("unit")
class CollisionLayerTest {

    @Test
    void bits_areDistinctAndSingle() {
        int seen = 0;
        for (CollisionLayer layer : CollisionLayer.values()) {
            assertThat(Integer.bitCount(layer.bit())).isEqualTo(1);
            assertThat(seen & layer.bit()).isZero();
            seen |= layer.bit();
        }
    }

    @Test
    void bodyLayerMasksAreSymmetric_soAcollisionIsNeverOneSided() {
        // A pair collides only if each side's mask contains the other's layer. An asymmetric mask
        // would make a contact appear or vanish depending on Bullet's pair ordering, which is
        // exactly the kind of non-determinism G3 exists to prevent.
        //
        // SENSOR_RAY is excluded because it is not a body layer: it is the filter group used for
        // ray tests (D06-S4.4), so its relationship with body layers is one-directional by design.
        for (CollisionLayer a : CollisionLayer.values()) {
            if (a == CollisionLayer.SENSOR_RAY) {
                continue;
            }
            for (CollisionLayer b : CollisionLayer.values()) {
                if (b == CollisionLayer.SENSOR_RAY) {
                    continue;
                }
                boolean aSeesB = (a.mask() & b.bit()) != 0;
                boolean bSeesA = (b.mask() & a.bit()) != 0;
                assertThat(aSeesB).as("%s sees %s but not vice versa", a, b).isEqualTo(bSeesA);
            }
        }
    }

    @Test
    void sensorRays_seeStaticVehiclesAndPropsOnly() {
        // D06-S4.4: bots sense the world through ray casts, and a ray that hit debris would let a
        // cloud of cosmetic shards blind a bot.
        assertThat(CollisionLayer.STATIC.isVisibleToSensorRays()).isTrue();
        assertThat(CollisionLayer.VEHICLE.isVisibleToSensorRays()).isTrue();
        assertThat(CollisionLayer.PROP.isVisibleToSensorRays()).isTrue();
        assertThat(CollisionLayer.DEBRIS.isVisibleToSensorRays()).isFalse();
        assertThat(CollisionLayer.PROJECTILE.isVisibleToSensorRays()).isFalse();
    }

    @Test
    void projectilesPassThroughDebris() {
        // D06-R11: a shard cloud must never act as spaced armour, or outcomes would depend on
        // cosmetic-looking clutter.
        assertThat(CollisionLayer.PROJECTILE.collidesWith(CollisionLayer.DEBRIS))
                .isFalse();
    }

    @Test
    void debrisCollidesWithVehicles() {
        // D06-R10: the collision happens; the damage does not. That split lives in D07-S5.2.
        assertThat(CollisionLayer.DEBRIS.collidesWith(CollisionLayer.VEHICLE)).isTrue();
    }

    @Test
    void triggersOnlySeeVehicles() {
        assertThat(CollisionLayer.TRIGGER.collidesWith(CollisionLayer.VEHICLE)).isTrue();
        assertThat(CollisionLayer.TRIGGER.collidesWith(CollisionLayer.DEBRIS)).isFalse();
    }
}
