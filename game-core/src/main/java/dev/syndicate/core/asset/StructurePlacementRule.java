/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import dev.syndicate.model.AssetId;
import java.util.Objects;

/**
 * How many of a structure an arena gets, and where (docs/16_procedural_arena_generation.md#D16-S4.7).
 *
 * <p><b>A rule, not a list of transforms</b> (D16-R21). An authored list would be content, and this
 * document's premise is that arena content is generated: the terrain is different every match under
 * D16-R6b, so a transform authored against one landscape is a building half-buried in the next.
 *
 * @param structureId which structure to place
 * @param placement where it may stand (D16-R22)
 * @param spacingM metres between instances along a road verge; ignored by other placements
 * @param jitterM how far an instance may wander off its nominal position, metres
 * @param countMin the fewest instances to attempt
 * @param countMax the most
 * @param densityPerHa instances per hectare, for {@link Placement#SCATTER}
 */
public record StructurePlacementRule(
        AssetId structureId,
        Placement placement,
        float spacingM,
        float jitterM,
        int countMin,
        int countMax,
        float densityPerHa) {

    /** Where a structure may stand (D16-R22). A closed set: every member costs a candidate generator. */
    public enum Placement {
        /** Along a road's shoulder, both sides, facing the road. */
        ROAD_VERGE,
        /** Crossing a road, above it — a sign gantry, an overpass. */
        ROAD_SPAN,
        /** A group on a flattened pad, off-road, within reach of a road. */
        CLUSTER,
        /** Anywhere drivable, sparse. */
        SCATTER;

        /** Whether this placement is allowed to sit on a road corridor (D16-R23). */
        public boolean mayStandOnRoad() {
            return this == ROAD_VERGE || this == ROAD_SPAN;
        }
    }

    /** Metres. Below this two instances of anything read as one object with a seam. */
    public static final float MIN_SPACING_M = 2.0f;

    /** The most instances one rule may produce, whatever the density says. */
    public static final int MAX_INSTANCES = 64;

    public StructurePlacementRule {
        Objects.requireNonNull(structureId, "structureId");
        placement = placement == null ? Placement.SCATTER : placement;
        spacingM = Math.max(MIN_SPACING_M, spacingM);
        jitterM = Math.max(0f, jitterM);
        countMin = Math.max(0, countMin);
        countMax = Math.min(MAX_INSTANCES, Math.max(countMin, countMax));
        densityPerHa = Math.max(0f, densityPerHa);
    }

    /** A scatter rule at a density, which is the shape most arena content takes. */
    public static StructurePlacementRule scatter(AssetId structureId, float densityPerHa) {
        return new StructurePlacementRule(
                structureId, Placement.SCATTER, MIN_SPACING_M, 0f, 0, MAX_INSTANCES, densityPerHa);
    }
}
