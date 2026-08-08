/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Packing, unpacking, and generation wrap for {@code EntityId} (docs/04_entity_component_model.md#D04-S6.1). */
@Tag("unit")
class EntityIdTest {

    @Test
    void packedId_roundTripsIndexAndGeneration() {
        int id = EntityId.pack(12345, 7);

        assertThat(EntityId.index(id)).isEqualTo(12345);
        assertThat(EntityId.generation(id)).isEqualTo(7);
    }

    @Test
    void maximumIndex_doesNotOverflowIntoGeneration() {
        int id = EntityId.pack(0x00FF_FFFF, 255);

        assertThat(EntityId.index(id)).isEqualTo(0x00FF_FFFF);
        assertThat(EntityId.generation(id)).isEqualTo(255);
    }

    @Test
    void generation_wrapsAtEightBits() {
        // D04-R11: the wrap is by design, and is safe because ids are only compared against
        // the index's current generation.
        assertThat(EntityId.nextGeneration(255)).isZero();
        assertThat(EntityId.nextGeneration(0)).isEqualTo(1);
    }

    @Test
    void negativeIndex_isRejected() {
        assertThat(catchIllegalArgument(() -> EntityId.pack(-1, 0))).isNotNull();
    }

    @Test
    void authorityAndClientRanges_areDisjoint() {
        // T-D04-11 (docs/04_entity_component_model.md#D04-S9)
        assertThat(EntityId.isAuthorityIndex(EntityId.AUTHORITY_INDEX_MAX)).isTrue();
        assertThat(EntityId.isClientLocalIndex(EntityId.AUTHORITY_INDEX_MAX)).isFalse();
        assertThat(EntityId.isClientLocalIndex(EntityId.CLIENT_LOCAL_INDEX_MIN)).isTrue();
        assertThat(EntityId.isAuthorityIndex(EntityId.CLIENT_LOCAL_INDEX_MIN)).isFalse();
        assertThat(EntityId.CLIENT_LOCAL_INDEX_MAX).isEqualTo(EntityId.MAX_ENTITIES - 1);
    }

    @Test
    void reservedIds_areNotAllocatable() {
        // D04-R5: 0 is the null entity, 1 is the match singleton.
        assertThat(EntityId.NULL).isZero();
        assertThat(EntityId.MATCH).isEqualTo(1);
        assertThat(EntityId.AUTHORITY_INDEX_MIN).isEqualTo(2);
    }

    private static IllegalArgumentException catchIllegalArgument(Runnable action) {
        try {
            action.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e;
        }
    }
}
