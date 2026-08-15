/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.ModuleFamily;

/**
 * What makes a utility part a particular module (docs/08_asset_pipeline.md#D08-S4.2).
 *
 * <p>The third block on a part definition, beside {@link HandlingBlock} (DEC-031) and
 * {@link WeaponBlock} (DEC-039), and it is here for the same reason both of those are: a family is
 * an identity rather than a quantity, and two modules' families do not sum.
 *
 * <p><b>The stats stay authoritative for the numbers.</b> A module's duration and cooldown are the
 * {@code moduleDurationS} and {@code moduleCooldownS} stats, so a damaged cloak stays lit for less
 * time and an ammo feed can buff a booster, exactly as a damaged weapon fires slower. This block
 * carries what cannot degrade: which family, and how many charges the module starts a match with.
 *
 * @param family which module this is
 * @param charges activations before the module is spent, or {@link #UNLIMITED_CHARGES} for one that
 *     recharges forever. Ignored by a {@link ModuleFamily.Activation#PASSIVE} family, which is never
 *     activated at all.
 * @param radiusM metres of effect for a family with an extent — a smoke cloud, a repair field. 0 for
 *     a module that acts on its own vehicle only, which is most of them.
 */
public record ModuleBlock(ModuleFamily family, int charges, float radiusM) {

    /** A {@code charges} that never depletes. */
    public static final int UNLIMITED_CHARGES = -1;

    /** Seconds an activation lasts when a part authors no {@code MODULE_DURATION_S} stat. */
    public static final float DEFAULT_DURATION_S = 5.0f;

    /** Seconds before a module may be activated again when it authors no cooldown stat. */
    public static final float DEFAULT_COOLDOWN_S = 20.0f;

    public ModuleBlock {
        if (family == null) {
            throw new IllegalArgumentException("a module block must name a family (D08-R6)");
        }
        if (!(radiusM >= 0f)) {
            throw new IllegalArgumentException("radiusM " + radiusM + " must be zero or positive");
        }
        if (charges == 0 || charges < UNLIMITED_CHARGES) {
            throw new IllegalArgumentException(
                    "charges " + charges + " must be positive or " + UNLIMITED_CHARGES + " for unlimited");
        }
    }

    /** A minimal module of a family: unlimited charges, acting on its own vehicle. */
    public static ModuleBlock of(ModuleFamily family) {
        return new ModuleBlock(family, UNLIMITED_CHARGES, 0f);
    }

    /** True when the player triggers this module rather than merely carrying it. */
    public boolean isActive() {
        return family.isActive();
    }
}
