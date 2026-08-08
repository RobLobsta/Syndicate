/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

/**
 * A PCG-XSH-RR 64/32 generator (docs/06_physics_simulation.md#D06-S5.8).
 *
 * <p>PCG rather than {@code java.util.Random} for three reasons that matter here: its sequence
 * ("stream") parameter gives genuinely independent streams from one match seed, its state is 64 bits
 * so a stream is trivially serialisable into a snapshot, and its output is defined by arithmetic
 * this class fully specifies — so a replay produces identical values on any JVM, which
 * {@code java.util.Random}'s documented-but-incidental algorithm would only accidentally do.
 *
 * <p>This class is deliberately not thread-safe. Gameplay randomness is drawn on the simulation
 * thread only; making it synchronised would hide an accidental cross-thread draw that is itself a
 * determinism bug (G4).
 */
public final class Pcg32 {

    private static final long MULTIPLIER = 6364136223846793005L;

    private long state;
    private final long increment;

    /**
     * @param seed the initial state
     * @param sequence selects the stream; two generators with the same seed and different sequences
     *     produce uncorrelated output
     */
    public Pcg32(long seed, long sequence) {
        this.increment = (sequence << 1) | 1L;
        this.state = 0L;
        nextInt();
        this.state += seed;
        nextInt();
    }

    /** The next uniformly distributed 32-bit value. */
    public int nextInt() {
        long previous = state;
        state = previous * MULTIPLIER + increment;
        int xorshifted = (int) (((previous >>> 18) ^ previous) >>> 27);
        int rotation = (int) (previous >>> 59);
        return Integer.rotateRight(xorshifted, rotation);
    }

    /**
     * A uniformly distributed value in {@code [0, bound)}.
     *
     * <p>Uses rejection sampling rather than a modulo, because modulo bias would make some outcomes
     * marginally more likely — invisible in play, but a real distortion in a 500-match balance sweep
     * (D11-S5.8).
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive, was " + bound);
        }
        int threshold = Integer.remainderUnsigned(-bound, bound);
        while (true) {
            int candidate = nextInt();
            if (Integer.compareUnsigned(candidate, threshold) >= 0) {
                return Integer.remainderUnsigned(candidate, bound);
            }
        }
    }

    /** A float in {@code [0, 1)} with 24 bits of precision, matching the project's float policy. */
    public float nextFloat() {
        return (nextInt() >>> 8) * 0x1.0p-24f;
    }

    /** A float in {@code [origin, bound)}. */
    public float nextFloat(float origin, float bound) {
        return origin + nextFloat() * (bound - origin);
    }

    /** A uniformly distributed 64-bit value, assembled from two draws. */
    public long nextLong() {
        return ((long) nextInt() << 32) | Integer.toUnsignedLong(nextInt());
    }

    public boolean nextBoolean() {
        return (nextInt() & 1) != 0;
    }

    /** The internal state, for serialising a stream into a snapshot (D04-S4.3.5). */
    public long state() {
        return state;
    }

    /** Restores a state captured by {@link #state()}, for rollback during reconciliation. */
    public void restoreState(long state) {
        this.state = state;
    }
}
