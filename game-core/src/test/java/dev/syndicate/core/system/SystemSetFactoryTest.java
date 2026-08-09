/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.syndicate.core.ecs.EntitySystem;
import dev.syndicate.core.ecs.Phase;
import dev.syndicate.core.ecs.World;
import dev.syndicate.model.RuntimeMode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Mode filtering over the fixed catalogue (docs/03_runtime_modes.md#D03-S5.2). */
@Tag("unit")
class SystemSetFactoryTest {

    /** D04-R6: the catalogue is exactly the 27 systems of D04-S4.4, in their fixed order. */
    @Test
    void theCatalogueIsTheD04Table() {
        List<SystemSlot> catalogue = SystemSlot.catalogue();
        assertThat(catalogue).hasSize(27);
        for (int i = 0; i < catalogue.size(); i++) {
            assertThat(catalogue.get(i).order()).isEqualTo(i + 1);
            assertThat(SystemSlot.byOrder(i + 1)).isEqualTo(catalogue.get(i));
        }
    }

    /** D04-R7 and the phase column of D04-S4.4: phases never run out of declaration order. */
    @Test
    void phasesAreMonotonicAcrossTheCatalogue() {
        Phase previous = Phase.INPUT;
        for (SystemSlot slot : SystemSlot.catalogue()) {
            assertThat(slot.phase().ordinal()).isGreaterThanOrEqualTo(previous.ordinal());
            previous = slot.phase();
        }
    }

    /** D03-S5.2: a dedicated server has no input collection and none of systems 22-26. */
    @Test
    void theDedicatedServerHasNoInputAndNoPresentationSystems() {
        List<SystemSlot> slots = SystemSetFactory.slotsFor(RuntimeMode.DEDICATED_SERVER);

        assertThat(slots).doesNotContain(SystemSlot.INPUT_COLLECTION);
        assertThat(slots)
                .doesNotContain(
                        SystemSlot.INTERPOLATION,
                        SystemSlot.DAMAGE_VISUAL,
                        SystemSlot.EFFECT,
                        SystemSlot.AUDIO,
                        SystemSlot.RENDER);
        assertThat(slots).doesNotContain(SystemSlot.NETWORK_RECEIVE, SystemSlot.RECONCILIATION);
        assertThat(slots).contains(SystemSlot.SPAWN, SystemSlot.DAMAGE, SystemSlot.NETWORK_SEND, SystemSlot.TRANSFORM);
    }

    /** D03-S5.2 and G15: a pure client never runs the systems that author authoritative damage. */
    @Test
    void aLocalClientRunsNoAuthoritativeSystems() {
        List<SystemSlot> slots = SystemSetFactory.slotsFor(RuntimeMode.LOCAL_CLIENT);

        assertThat(slots)
                .doesNotContain(
                        SystemSlot.INPUT_RECEIVE,
                        SystemSlot.BOT_DECISION,
                        SystemSlot.MATCH_FLOW,
                        SystemSlot.SPAWN,
                        SystemSlot.COLLISION_EVENT,
                        SystemSlot.DAMAGE,
                        SystemSlot.FRACTURE,
                        SystemSlot.DETACH,
                        SystemSlot.SCORE,
                        SystemSlot.NETWORK_SEND);
        assertThat(slots).contains(SystemSlot.NETWORK_RECEIVE, SystemSlot.RECONCILIATION, SystemSlot.RENDER);
    }

    /** D03-R9: single player is both authority and client, so it runs both halves. */
    @Test
    void singlePlayerIsBothAuthorityAndClient() {
        List<SystemSlot> slots = SystemSetFactory.slotsFor(RuntimeMode.SINGLE_PLAYER);

        assertThat(slots).contains(SystemSlot.SPAWN, SystemSlot.DAMAGE, SystemSlot.NETWORK_SEND);
        assertThat(slots).contains(SystemSlot.NETWORK_RECEIVE, SystemSlot.RECONCILIATION, SystemSlot.RENDER);
    }

    /** Slots 6, 7, 10, 15, 16, 21 and 27 run in every mode (D03-S5.2's unconditional lines). */
    @Test
    void theUnconditionalSlotsAreInEveryMode() {
        List<SystemSlot> always = List.of(
                SystemSlot.VEHICLE_STATS,
                SystemSlot.VEHICLE_CONTROL,
                SystemSlot.PHYSICS,
                SystemSlot.MASS_PROPERTY,
                SystemSlot.LIFETIME,
                SystemSlot.TRANSFORM,
                SystemSlot.ENTITY_DESTROY);
        for (RuntimeMode mode : RuntimeMode.values()) {
            assertThat(SystemSetFactory.slotsFor(mode)).containsAll(always);
        }
    }

    /** D03-S5.2's closing assertion: filtering never reorders. */
    @Test
    void everyModeSchedulesInCatalogueOrder() {
        for (RuntimeMode mode : RuntimeMode.values()) {
            List<SystemSlot> slots = SystemSetFactory.slotsFor(mode);
            // A subsequence of a strictly ordered list is exactly a strictly increasing selection
            // from it, which is cheaper to state than a subsequence matcher and says the same thing.
            assertThat(SystemSlot.catalogue()).containsSubsequence(slots.toArray(new SystemSlot[0]));
            assertThat(slots.stream().map(SystemSlot::order).toList()).isSorted();
        }
    }

    /** A schedule built from the core provider runs, in order, and skips what is unimplemented. */
    @Test
    void theCoreProviderFillsTheSlotsThatExist() {
        List<EntitySystem> systems = SystemSetFactory.forMode(RuntimeMode.DEDICATED_SERVER, stubProvider());

        assertThat(systems).isNotEmpty();
        SystemSetFactory.verifyOrder(systems);
        assertThat(systems.stream().map(EntitySystem::order).toList()).isSorted();
    }

    /** A provider that returns a system whose {@code order()} disagrees with its slot is refused. */
    @Test
    void aMisnumberedSystemIsRejected() {
        SystemProvider liar = (slot, mode) -> slot == SystemSlot.PHYSICS ? new StubSystem(Phase.SIM, 3) : null;

        assertThatThrownBy(() -> SystemSetFactory.forMode(RuntimeMode.DEDICATED_SERVER, liar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slot");
    }

    /** {@code verifyOrder} is the guard behind the structural claim, so it has to actually fail. */
    @Test
    void verifyOrderRejectsAnOutOfOrderSchedule() {
        List<EntitySystem> outOfOrder = List.of(new StubSystem(Phase.SIM, 10), new StubSystem(Phase.PRE_SIM, 6));

        assertThatThrownBy(() -> SystemSetFactory.verifyOrder(outOfOrder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalogue order");
    }

    /** Fills every slot with a correctly numbered stub, so the test needs no Bullet natives. */
    private static SystemProvider stubProvider() {
        return (slot, mode) -> new StubSystem(slot.phase(), slot.order());
    }

    private record StubSystem(Phase phase, int order) implements EntitySystem {

        @Override
        public void update(World world, float dtSeconds, long tick) {
            // A schedule test asserts what is in the list and in what order, never what it does.
        }
    }
}
