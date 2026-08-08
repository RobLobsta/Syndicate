/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import java.util.EnumMap;
import java.util.Map;

/**
 * The per-match seeded PRNG owned by the authority (docs/06_physics_simulation.md#D06-S5.8, G4).
 *
 * <p>All gameplay randomness draws from here, through a named {@link StreamId}. Cosmetic randomness
 * — particle jitter, debris sparks, audio variation — uses a separate client-local unseeded
 * generator and must never write to a replicated field (D06-R26, G6).
 *
 * <p>Streams are derived from the match seed at first use and cached, so the set of streams a match
 * has touched does not depend on the order in which subsystems happened to ask for them.
 */
public final class RandomSource {

    private final long matchSeed;
    private final Map<StreamId, Pcg32> streams = new EnumMap<>(StreamId.class);

    public RandomSource(long matchSeed) {
        this.matchSeed = matchSeed;
    }

    /** The seed this source was constructed with; printed by the admin console's {@code seed}. */
    public long matchSeed() {
        return matchSeed;
    }

    /**
     * The generator for one subsystem.
     *
     * <p>The stream's seed mixes the match seed with the stream's identity, so two matches with
     * different seeds differ in every stream, and two streams within one match are uncorrelated.
     * The identity hash is the enum's {@code name()}, not its {@code ordinal()}: reordering the enum
     * would otherwise silently re-map every stream and invalidate recorded regression expectations.
     */
    public Pcg32 stream(StreamId id) {
        return streams.computeIfAbsent(id, key -> {
            long identity = hash(key.name());
            return new Pcg32(mix(matchSeed, identity), identity);
        });
    }

    /** SplitMix64 finalising mix, giving good avalanche between the seed and the stream identity. */
    private static long mix(long seed, long identity) {
        long z = seed + 0x9E3779B97F4A7C15L * (identity | 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** FNV-1a over the stream name; stable across JVMs, unlike {@code String.hashCode()} contracts. */
    private static long hash(String value) {
        long hash = 0xCBF29CE484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001B3L;
        }
        return hash;
    }
}
