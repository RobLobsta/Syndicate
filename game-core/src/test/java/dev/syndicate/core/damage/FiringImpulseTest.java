/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.damage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.syndicate.model.WeaponFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The momentum model behind recoil and knockback (docs/17_weapon_system.md#D17-S5.12).
 *
 * <p>The impulses themselves need a Bullet world and a spawned vehicle, so they are asserted in
 * {@code WeaponRecoilPhysicsTest} on the real physics scene. What is tested here is the arithmetic
 * that decides how hard each family shoves — the part that makes "the cannon should apply impulse" a
 * formula rather than a constant (T-D17-11's precondition).
 */
@Tag("unit")
class FiringImpulseTest {

    @Test
    @DisplayName("A cannon shell carries two orders of magnitude more momentum than a rifle round")
    void cannonCarriesFarMoreMomentumThanAnAutocannon() {
        float cannon = WeaponFamily.CANNON.shotMomentumNs(250f);
        float autocannon = WeaponFamily.AUTOCANNON.shotMomentumNs(600f);

        assertThat(cannon).isEqualTo(3000f);
        assertThat(autocannon).isEqualTo(72f);
        // This ratio is the whole feature: the same formula makes one shove felt and the other not.
        assertThat(cannon / autocannon).isGreaterThan(30f);
    }

    @Test
    @DisplayName("D17-R57a: a rocket knocks back at full momentum and recoils at none")
    void rocketIsRecoilless() {
        // A rocket accelerates on its own motor after leaving the tube, so the launcher never takes
        // the round's momentum. Without this the same formula would have a rocket pod shove a car as
        // hard as a cannon does.
        assertThat(WeaponFamily.ROCKET.recoilFraction()).isZero();
        assertThat(WeaponFamily.ROCKET.shotMomentumNs(120f)).isGreaterThan(0f);

        for (WeaponFamily family : new WeaponFamily[] {
            WeaponFamily.CANNON, WeaponFamily.AUTOCANNON, WeaponFamily.SHOTGUN, WeaponFamily.MORTAR
        }) {
            assertThat(family.recoilFraction())
                    .as("%s fires its shot with a propellant charge, so it kicks", family)
                    .isEqualTo(1f);
        }
    }

    @Test
    @DisplayName("A family that fires no mass carries no momentum in either direction")
    void beamsAndFlamesCarryNoMomentum() {
        for (WeaponFamily family : new WeaponFamily[] {WeaponFamily.LASER, WeaponFamily.FLAMER, WeaponFamily.RAM}) {
            assertThat(family.shotMomentumNs(0f)).as("%s", family).isZero();
            assertThat(family.recoilFraction()).as("%s", family).isZero();
        }
    }

    @Test
    @DisplayName("A hitscan family falls back to its nominal speed, because its shot has no entity")
    void hitscanUsesTheNominalSpeed() {
        // A shotgun arrives in the tick it is fired, so there is no travelling projectile to read a
        // speed from — and it still kicks.
        assertThat(WeaponFamily.SHOTGUN.shotMomentumNs(0f))
                .isEqualTo(WeaponFamily.SHOTGUN.projectileMassKg() * WeaponFamily.SHOTGUN.nominalSpeedMps());
        assertThat(WeaponFamily.SHOTGUN.shotMomentumNs(0f)).isGreaterThan(0f);

        // A supplied speed wins over the nominal one, which is what a ballistic family passes.
        assertThat(WeaponFamily.CANNON.shotMomentumNs(500f)).isEqualTo(6000f);
    }

    @Test
    @DisplayName("D17-E12: the recoil clamp sits well above the heaviest shipped weapon")
    void theClampIsAGuardAndNotABalanceNumber() {
        float heaviestShipped = WeaponFamily.CANNON.shotMomentumNs(250f);
        assertThat(FiringImpulse.MAX_RECOIL_IMPULSE_NS)
                .as("no sane weapon should reach the clamp")
                .isGreaterThan(heaviestShipped * 3f);
    }
}
