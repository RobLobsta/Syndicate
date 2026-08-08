/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.component;

import dev.syndicate.core.ecs.Component;
import dev.syndicate.core.vehicle.StatBlock;
import dev.syndicate.model.AssetId;
import dev.syndicate.model.PartCategory;

/**
 * A part's contribution to its vehicle, before and after damage
 * (docs/04_entity_component_model.md#D04-S4.3.2).
 *
 * <p>Keeping {@link #baseStats} alongside {@link #effectiveStats} is what makes degradation
 * reversible in the arithmetic sense: {@code VehicleStatsSystem} recomputes effective from base and
 * the current health fraction every time health changes (D05-S5.4), rather than applying a decay to
 * the previous effective value. Repeated in-place decay would accumulate float error and would make
 * the result depend on how many times health happened to change, not on what it changed to.
 */
public final class PartStatsComponent implements Component {

    /** An immutable copy of the part type's stats. Never modified after spawn. */
    public final StatBlock baseStats = new StatBlock();

    /** {@link #baseStats} after the degradation curve for {@link #category} (D05-S5.4). */
    public final StatBlock effectiveStats = new StatBlock();

    /** Drives slot compatibility, the degradation curve, and stat aggregation rules. */
    public PartCategory category = PartCategory.DECORATIVE;

    /** Drives density (and therefore mass) and the damage-type modifiers of D07-S4.3. */
    public AssetId materialId;

    @Override
    public void reset() {
        baseStats.reset();
        effectiveStats.reset();
        category = PartCategory.DECORATIVE;
        materialId = null;
    }
}
