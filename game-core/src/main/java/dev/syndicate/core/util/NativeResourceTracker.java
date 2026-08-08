/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts allocations against disposals for every Bullet native type (G19,
 * docs/02_technical_architecture.md#D02-S5.7).
 *
 * <p>Native leaks are invisible until a soak test finds them days later, so every integration test
 * asserts {@link #outstanding()} is zero in an {@code @AfterEach} (D12-R7, AC-D02-10) and world
 * teardown asserts the same (D03-S5.6). The per-type {@link #census()} is what turns "something
 * leaked" into "eleven btConvexHullShapes leaked", which is the difference between a five-minute
 * fix and an afternoon.
 *
 * <p>Counters are per-process and thread-safe because Bullet objects may be released from a
 * disposal path that is not the simulation thread.
 */
public final class NativeResourceTracker {

    private static final Map<String, AtomicInteger> COUNTS = new TreeMap<>();
    private static volatile boolean enabled;

    private NativeResourceTracker() {
        throw new AssertionError("no instances");
    }

    /** Turns tracking on. Installed in debug and profiling builds only (D03-S5.1). */
    public static void install() {
        synchronized (COUNTS) {
            COUNTS.clear();
        }
        enabled = true;
    }

    /** Turns tracking off and clears the census. */
    public static void uninstall() {
        enabled = false;
        synchronized (COUNTS) {
            COUNTS.clear();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Records an allocation. The caller names the type it allocated, e.g. {@code btRigidBody}. */
    public static void register(String nativeType) {
        if (!enabled) {
            return;
        }
        counter(nativeType).incrementAndGet();
    }

    /** Records a disposal. */
    public static void release(String nativeType) {
        if (!enabled) {
            return;
        }
        counter(nativeType).decrementAndGet();
    }

    /** Total outstanding allocations across all types. Zero is the only acceptable value at teardown. */
    public static int outstanding() {
        if (!enabled) {
            return 0;
        }
        int total = 0;
        synchronized (COUNTS) {
            for (AtomicInteger count : COUNTS.values()) {
                total += count.get();
            }
        }
        return total;
    }

    /** Outstanding count per native type, sorted by type name for stable failure messages. */
    public static Map<String, Integer> census() {
        Map<String, Integer> snapshot = new TreeMap<>();
        synchronized (COUNTS) {
            COUNTS.forEach((type, count) -> {
                if (count.get() != 0) {
                    snapshot.put(type, count.get());
                }
            });
        }
        return snapshot;
    }

    /** A one-line census for log and assertion messages. */
    public static String describeOutstanding() {
        Map<String, Integer> census = census();
        return census.isEmpty() ? "no outstanding native resources" : "outstanding natives: " + census;
    }

    private static AtomicInteger counter(String nativeType) {
        synchronized (COUNTS) {
            return COUNTS.computeIfAbsent(nativeType, key -> new AtomicInteger());
        }
    }
}
