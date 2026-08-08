/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import java.util.function.Supplier;

/**
 * A fixed-capacity ring of preallocated elements, overwriting oldest-first when full
 * (docs/04_entity_component_model.md#D04-S5.6).
 *
 * <p>Used for the pending-input and interpolation buffers of D10-S5.5 and #D10-S5.6. Both are
 * written every tick and read every tick, so a growable collection would allocate forever; D04-S5.6
 * requires them to be fixed-capacity ring buffers sized at construction.
 *
 * <p>Elements are <b>allocated once and mutated in place</b>. {@link #next()} hands back the slot
 * that is about to become the newest entry so the caller can write into it; nothing is ever handed
 * to a pool or replaced. That is what makes the steady-state allocation of AC-D04-6 zero, and it is
 * also why a reference returned by {@link #get(int)} is only valid until the buffer wraps past it.
 *
 * @param <T> the element type; must be mutable, since entries are written in place
 */
public final class RingBuffer<T> {

    private final Object[] slots;
    private int head;
    private int size;

    /**
     * @param capacity number of slots; must be positive
     * @param factory called exactly {@code capacity} times at construction to fill the ring
     */
    public RingBuffer(int capacity, Supplier<T> factory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("ring capacity must be positive, got " + capacity);
        }
        this.slots = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            slots[i] = factory.get();
        }
    }

    /** How many slots exist. Constant for the buffer's lifetime. */
    public int capacity() {
        return slots.length;
    }

    /** How many slots hold live entries: rises to {@link #capacity()} and stays there. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Advances the ring and returns the slot the caller should overwrite, which becomes the newest
     * entry. When the ring is full this reuses the oldest slot, silently discarding it — that is the
     * intended behaviour for both call sites, where an entry older than the buffer is by definition
     * no longer needed.
     */
    @SuppressWarnings("unchecked")
    public T next() {
        head = (head + 1) % slots.length;
        if (size < slots.length) {
            size++;
        }
        return (T) slots[head];
    }

    /**
     * The entry {@code age} steps back from the newest: {@code get(0)} is the newest,
     * {@code get(size()-1)} the oldest.
     *
     * @throws IndexOutOfBoundsException if {@code age} is outside {@code [0, size())}
     */
    @SuppressWarnings("unchecked")
    public T get(int age) {
        if (age < 0 || age >= size) {
            throw new IndexOutOfBoundsException("age " + age + " outside [0, " + size + ")");
        }
        int index = Math.floorMod(head - age, slots.length);
        return (T) slots[index];
    }

    /** The newest entry, or null when nothing has been written yet. */
    @SuppressWarnings("unchecked")
    public T newest() {
        return size == 0 ? null : (T) slots[head];
    }

    /**
     * Forgets every entry without discarding the preallocated slots, so the ring can be reused for
     * a new match without re-allocating. Slot contents are left as they were; a caller reading
     * before writing would see stale data, which {@link #size()} prevents by returning 0.
     */
    public void clear() {
        head = 0;
        size = 0;
    }
}
