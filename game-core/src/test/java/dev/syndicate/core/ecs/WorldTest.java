/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Entity lifecycle, family ordering, and deferred destruction
 * (docs/04_entity_component_model.md#D04-S5.1, #D04-S5.5, #D04-S5.7).
 */
@Tag("unit")
class WorldTest {

    /** A minimal component; the real catalogue is D04-S4.3. */
    static final class MarkerComponent implements Component {
        int value;

        @Override
        public void reset() {
            value = 0;
        }
    }

    static final class OtherComponent implements Component {
        @Override
        public void reset() {
            // no state
        }
    }

    private World world;

    @BeforeEach
    void setUp() {
        world = new World(1337L, true);
    }

    @Test
    void createdEntity_isAliveAndCarriesItsComponents() {
        int id = world.createEntity().id();
        MarkerComponent marker = new MarkerComponent();
        marker.value = 42;
        world.addComponent(id, marker);

        assertThat(world.isAlive(id)).isTrue();
        assertThat(world.getComponent(id, MarkerComponent.class)).isSameAs(marker);
        assertThat(world.hasComponent(id, MarkerComponent.class)).isTrue();
        assertThat(world.hasComponent(id, OtherComponent.class)).isFalse();
    }

    @Test
    void componentOnDestroyedEntity_returnsNullAndNeverThrows() {
        // D04-E1: callers null-check; access never throws.
        int id = world.createEntity().id();
        world.addComponent(id, new MarkerComponent());
        world.destroyEntity(id);
        world.tick(0);

        assertThat(world.get(id)).isNull();
        assertThat(world.getComponent(id, MarkerComponent.class)).isNull();
        assertThat(world.isAlive(id)).isFalse();
    }

    @Test
    void staleId_afterIndexRecycle_reportsNotAlive() {
        // T-D04-1 (docs/04_entity_component_model.md#D04-S9): a recycled index must not resolve
        // a stale id to the wrong entity.
        int first = world.createEntity().id();
        world.destroyEntity(first);
        world.tick(0);

        int second = world.createEntity().id();

        assertThat(EntityId.index(second)).isEqualTo(EntityId.index(first));
        assertThat(EntityId.generation(second)).isNotEqualTo(EntityId.generation(first));
        assertThat(world.isAlive(first)).isFalse();
        assertThat(world.isAlive(second)).isTrue();
    }

    @Test
    void destroyingTwice_tearsDownOnceWithoutThrowing() {
        // T-D04-2 (docs/04_entity_component_model.md#D04-S9)
        int id = world.createEntity().id();
        world.destroyEntity(id);
        world.destroyEntity(id);

        world.tick(0);

        assertThat(world.isAlive(id)).isFalse();
        assertThat(world.entityCount()).isZero();
    }

    @Test
    void destroyedEntity_leavesFamiliesImmediately_butTearsDownInCleanup() {
        // D04-R15: deferral is what makes destroy-during-iteration safe.
        Family family = familyOfMarkers();
        int id = world.createEntity().id();
        world.addComponent(id, new MarkerComponent());
        assertThat(family.size()).isEqualTo(1);

        world.destroyEntity(id);

        assertThat(family.size()).isZero();
        assertThat(world.get(id)).isNull();
    }

    @Test
    void familyIteration_isAscendingByIdAfterHeavyChurn() {
        // T-D04-6 / AC-D04-4: ordering must survive add/remove churn, since G3 depends on it.
        Family family = familyOfMarkers();
        List<Integer> created = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int id = world.createEntity().id();
            world.addComponent(id, new MarkerComponent());
            created.add(id);
        }
        for (int i = 0; i < created.size(); i += 2) {
            world.removeComponent(created.get(i), MarkerComponent.class);
        }
        for (int i = 0; i < 50; i++) {
            int id = world.createEntity().id();
            world.addComponent(id, new MarkerComponent());
        }

        int[] members = family.toArray();

        assertThat(members).isSorted();
        assertThat(family.size()).isEqualTo(members.length);
    }

    @Test
    void familySnapshot_isStableWhileComponentsChange() {
        // D04-R12: a system never observes its family mutating underneath its own loop.
        Family family = familyOfMarkers();
        for (int i = 0; i < 5; i++) {
            int id = world.createEntity().id();
            world.addComponent(id, new MarkerComponent());
        }
        int[] snapshot = family.snapshot();
        int lengthBefore = family.size();

        for (int i = 0; i < lengthBefore; i++) {
            world.removeComponent(snapshot[i], MarkerComponent.class);
        }

        assertThat(family.size()).isZero();
        assertThat(snapshot).hasSizeGreaterThanOrEqualTo(lengthBefore);
    }

    @Test
    void excludeClause_removesMatchingEntities() {
        Family family = world.family(ComponentQuery.all(MarkerComponent.class).exclude(OtherComponent.class));
        int plain = world.createEntity().id();
        int excluded = world.createEntity().id();
        world.addComponent(plain, new MarkerComponent());
        world.addComponent(excluded, new MarkerComponent());
        world.addComponent(excluded, new OtherComponent());

        assertThat(family.contains(plain)).isTrue();
        assertThat(family.contains(excluded)).isFalse();
    }

    @Test
    void duplicateComponent_isRejected() {
        // D04-E4: two components of one type would leave the loser invisible and unfreed.
        int id = world.createEntity().id();
        world.addComponent(id, new MarkerComponent());

        assertThatThrownBy(() -> world.addComponent(id, new MarkerComponent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate component");
    }

    @Test
    void unboundedFamily_isRejectedAtConstruction() {
        // D04-E7: a family with no all() clause would iterate every entity every tick.
        assertThatThrownBy(ComponentQuery::all).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reservedIndex_canBeClaimedForTheMatchSingleton() {
        // D04-R5: the match entity always occupies index 1 so every peer addresses it identically.
        Entity match = world.createEntityWithReservedIndex(EntityId.MATCH);

        assertThat(EntityId.index(match.id())).isEqualTo(EntityId.MATCH);
        assertThat(world.isAlive(match.id())).isTrue();
    }

    @Test
    void clientWorld_allocatesOnlyFromTheClientLocalRange() {
        // AC-D04-11: client-local ids must never collide with authority-allocated ones.
        World client = new World(1337L, false);

        int id = client.createEntity().id();

        assertThat(EntityId.isClientLocalIndex(EntityId.index(id))).isTrue();
    }

    @Test
    void removedComponent_isResetForPooling() {
        // D04-R17: a stale field leaking into a reused component is a correctness bug.
        int id = world.createEntity().id();
        MarkerComponent marker = new MarkerComponent();
        marker.value = 99;
        world.addComponent(id, marker);

        world.removeComponent(id, MarkerComponent.class);

        assertThat(marker.value).isZero();
    }

    private Family familyOfMarkers() {
        return world.family(ComponentQuery.all(MarkerComponent.class));
    }
}
