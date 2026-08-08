/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The fixed-capacity ring of docs/04_entity_component_model.md#D04-S5.6. */
@Tag("unit")
class RingBufferTest {

    /** Holder so the test can write into a slot and read it back, like the real call sites do. */
    private static final class Slot {
        int value;
    }

    /**
     * D04-S5.6: slots are allocated once at construction. If {@code next()} ever allocated, the
     * prediction buffer would produce garbage every tick, which is what AC-D04-6 measures.
     */
    @Test
    void allocatesEverySlotOnceAtConstruction() {
        AtomicInteger constructed = new AtomicInteger();
        RingBuffer<Slot> ring = new RingBuffer<>(4, () -> {
            constructed.incrementAndGet();
            return new Slot();
        });

        assertThat(constructed).hasValue(4);
        for (int i = 0; i < 20; i++) {
            ring.next().value = i;
        }
        assertThat(constructed).as("next() must never allocate").hasValue(4);
    }

    /** {@code get(0)} is the newest and {@code get(size-1)} the oldest, before the ring fills. */
    @Test
    void indexesBackwardFromNewest() {
        RingBuffer<Slot> ring = new RingBuffer<>(4, Slot::new);
        for (int i = 1; i <= 3; i++) {
            ring.next().value = i;
        }

        assertThat(ring.size()).isEqualTo(3);
        assertThat(ring.get(0).value).isEqualTo(3);
        assertThat(ring.get(1).value).isEqualTo(2);
        assertThat(ring.get(2).value).isEqualTo(1);
        assertThat(ring.newest().value).isEqualTo(3);
    }

    /** Past capacity the oldest entry is overwritten and size stays pinned at capacity. */
    @Test
    void overwritesOldestWhenFull() {
        RingBuffer<Slot> ring = new RingBuffer<>(3, Slot::new);
        for (int i = 1; i <= 5; i++) {
            ring.next().value = i;
        }

        assertThat(ring.size()).isEqualTo(3);
        assertThat(ring.get(0).value).isEqualTo(5);
        assertThat(ring.get(1).value).isEqualTo(4);
        assertThat(ring.get(2).value).isEqualTo(3);
    }

    /** Reading past {@code size()} is a bug in the caller, not an empty slot to be handed back. */
    @Test
    void rejectsReadsOutsideLiveRange() {
        RingBuffer<Slot> ring = new RingBuffer<>(3, Slot::new);
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.newest()).isNull();

        assertThatThrownBy(() -> ring.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        ring.next();
        assertThatThrownBy(() -> ring.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    /** {@code clear()} forgets entries without discarding the preallocated slots. */
    @Test
    void clearKeepsSlots() {
        RingBuffer<Slot> ring = new RingBuffer<>(3, Slot::new);
        ring.next().value = 9;
        Slot slot = ring.get(0);

        ring.clear();

        assertThat(ring.size()).isZero();
        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.next()).as("the same preallocated slot must come back").isSameAs(slot);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new RingBuffer<>(0, Slot::new)).isInstanceOf(IllegalArgumentException.class);
    }
}
