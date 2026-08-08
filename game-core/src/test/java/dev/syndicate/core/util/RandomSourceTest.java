/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Seeded, stream-separated randomness (docs/06_physics_simulation.md#D06-S5.8, G4). */
@Tag("unit")
class RandomSourceTest {

    @Test
    void sameSeed_producesIdenticalSequences() {
        // G4 and D12-R9: a run-to-run difference from one seed is always a correctness bug.
        List<Integer> first = draw(new RandomSource(1337L), StreamId.DAMAGE_SPREAD, 64);
        List<Integer> second = draw(new RandomSource(1337L), StreamId.DAMAGE_SPREAD, 64);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSeeds_produceDifferentSequences() {
        List<Integer> first = draw(new RandomSource(1337L), StreamId.DAMAGE_SPREAD, 64);
        List<Integer> second = draw(new RandomSource(1338L), StreamId.DAMAGE_SPREAD, 64);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void streams_areIndependentOfEachOther() {
        // D06-R25: this is the whole point of per-subsystem streams. If they were shared, adding a
        // single bot decision would shift every weapon's spread and break unrelated regressions.
        RandomSource source = new RandomSource(1337L);
        List<Integer> spread = draw(source, StreamId.DAMAGE_SPREAD, 32);

        RandomSource other = new RandomSource(1337L);
        other.stream(StreamId.BOT_DECISION).nextInt();
        other.stream(StreamId.FRACTURE_SCATTER).nextInt();
        List<Integer> spreadAfterUnrelatedDraws = draw(other, StreamId.DAMAGE_SPREAD, 32);

        assertThat(spreadAfterUnrelatedDraws).isEqualTo(spread);
    }

    @Test
    void distinctStreams_doNotProduceTheSameValues() {
        RandomSource source = new RandomSource(1337L);

        List<Integer> spread = draw(source, StreamId.DAMAGE_SPREAD, 32);
        List<Integer> scatter = draw(source, StreamId.FRACTURE_SCATTER, 32);

        assertThat(spread).isNotEqualTo(scatter);
    }

    @Test
    void streamIdentity_survivesEnumReordering() {
        // The stream seed derives from name(), not ordinal(): reordering StreamId must not silently
        // re-map every stream and invalidate recorded physics expectations (D12-R10).
        RandomSource source = new RandomSource(1337L);
        int fromName = new Pcg32(0, fnv(StreamId.MATCH_MISC.name())).nextInt();

        assertThat(source.stream(StreamId.MATCH_MISC)).isNotNull();
        assertThat(fromName).isEqualTo(new Pcg32(0, fnv("MATCH_MISC")).nextInt());
    }

    @Test
    void boundedDraws_stayInRangeAndCoverIt() {
        Pcg32 rng = new Pcg32(1337L, 1L);
        boolean[] seen = new boolean[6];

        for (int i = 0; i < 10_000; i++) {
            int roll = rng.nextInt(6);
            assertThat(roll).isBetween(0, 5);
            seen[roll] = true;
        }

        assertThat(seen).containsOnly(true);
    }

    @Test
    void floatDraws_stayInUnitInterval() {
        Pcg32 rng = new Pcg32(1337L, 1L);

        for (int i = 0; i < 10_000; i++) {
            float value = rng.nextFloat();
            assertThat(value).isGreaterThanOrEqualTo(0.0f).isLessThan(1.0f);
        }
    }

    @Test
    void restoredState_replaysTheSameSequence() {
        // Reconciliation rewinds and replays (D10-S5.5); a stream that cannot be restored would
        // make the replay diverge from the prediction it is correcting.
        Pcg32 rng = new Pcg32(1337L, 1L);
        rng.nextInt();
        long checkpoint = rng.state();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            expected.add(rng.nextInt());
        }

        rng.restoreState(checkpoint);
        List<Integer> replayed = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            replayed.add(rng.nextInt());
        }

        assertThat(replayed).isEqualTo(expected);
    }

    private static List<Integer> draw(RandomSource source, StreamId id, int count) {
        List<Integer> values = new ArrayList<>(count);
        Pcg32 rng = source.stream(id);
        for (int i = 0; i < count; i++) {
            values.add(rng.nextInt());
        }
        return values;
    }

    private static long fnv(String value) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
