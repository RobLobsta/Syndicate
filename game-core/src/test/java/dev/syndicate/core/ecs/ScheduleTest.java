/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.ecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The fixed system schedule (docs/04_entity_component_model.md#D04-S4.4, #D04-S5.3). */
@Tag("unit")
class ScheduleTest {

    /** Records the order in which it ran, so the test can assert the schedule rather than infer it. */
    private static final class RecordingSystem implements EntitySystem {
        private final Phase phase;
        private final int order;
        private final List<String> log;

        RecordingSystem(Phase phase, int order, List<String> log) {
            this.phase = phase;
            this.order = order;
            this.log = log;
        }

        @Override
        public Phase phase() {
            return phase;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public void update(World world, float dtSeconds, long tick) {
            log.add(phase + "#" + order);
        }
    }

    @Test
    void systemsRun_inCatalogueOrderRegardlessOfRegistrationOrder() {
        // AC-D04-3 / T-D04-8: the order is a compile-time constant, never registration order.
        List<String> log = new ArrayList<>();
        World world = new World(1337L, true);
        world.registerSystems(List.of(
                new RecordingSystem(Phase.CLEANUP, 27, log),
                new RecordingSystem(Phase.INPUT, 1, log),
                new RecordingSystem(Phase.POST_SIM, 12, log),
                new RecordingSystem(Phase.SIM, 10, log)));

        world.tick(0);

        assertThat(log).containsExactly("INPUT#1", "SIM#10", "POST_SIM#12", "CLEANUP#27");
    }

    @Test
    void presentSystems_areSkippedByTickAndRunByPresent() {
        // D04-R7: PRESENT runs once per rendered frame, everything else once per tick. This split
        // is the only place tick/frame decoupling appears.
        List<String> log = new ArrayList<>();
        World world = new World(1337L, true);
        world.registerSystems(
                List.of(new RecordingSystem(Phase.SIM, 10, log), new RecordingSystem(Phase.PRESENT, 26, log)));

        world.tick(0);
        assertThat(log).containsExactly("SIM#10");

        world.present(0.5f);
        assertThat(log).containsExactly("SIM#10", "PRESENT#26");
    }

    @Test
    void twoSystemsClaimingOneSlot_areRejected() {
        // An ambiguous schedule makes the simulation non-reproducible, which G3 forbids.
        World world = new World(1337L, true);
        List<String> log = new ArrayList<>();

        assertThatThrownBy(() -> world.registerSystems(
                        List.of(new RecordingSystem(Phase.SIM, 10, log), new RecordingSystem(Phase.SIM, 10, log))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim schedule slot 10");
    }

    @Test
    void filteredSchedule_remainsASubsequenceOfTheCatalogue() {
        // AC-D03-3: a mode filter drops systems but never reorders them (D03-S5.2).
        List<String> log = new ArrayList<>();
        World world = new World(1337L, true);
        world.registerSystems(List.of(
                new RecordingSystem(Phase.INPUT, 1, log),
                new RecordingSystem(Phase.PRE_SIM, 6, log),
                new RecordingSystem(Phase.SIM, 10, log),
                new RecordingSystem(Phase.CLEANUP, 27, log)));

        List<Integer> orders =
                world.schedule().stream().map(EntitySystem::order).toList();

        assertThat(orders).isSorted().containsExactly(1, 6, 10, 27);
    }

    @Test
    void tick_exposesTheCurrentTickToSystems() {
        // G5: the tick number is the only clock a simulation system may read.
        World world = new World(1337L, true);
        List<Long> observed = new ArrayList<>();
        world.registerSystems(List.<EntitySystem>of(new EntitySystem() {
            @Override
            public Phase phase() {
                return Phase.SIM;
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public void update(World w, float dtSeconds, long tick) {
                observed.add(tick);
                assertThat(w.currentTick()).isEqualTo(tick);
            }
        }));

        world.tick(0);
        world.tick(1);
        world.tick(2);

        assertThat(observed).containsExactly(0L, 1L, 2L);
    }
}
