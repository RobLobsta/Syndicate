/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

/**
 * The noise the ground is made of (docs/16_procedural_arena_generation.md#D16-S5.2).
 *
 * <p>Two-dimensional gradient noise on an integer lattice, and fractal Brownian motion over it. Both
 * are <b>pure functions of position and seed</b>: there is no permutation table to initialise, no
 * generator to advance, and no state of any kind. That is not a stylistic preference, it is what
 * D16-R30 requires and what two separate things depend on.
 *
 * <p><b>Why a hash rather than a permutation table.</b> A field that can be evaluated at one point,
 * without generating its neighbours, is what makes {@code heightAt} on an arbitrary world position
 * cheap — the road carve (D16-S5.4) and structure pads both sample the land before the grid they
 * would index into exists. A permutation table would work and would also have to be built, seeded,
 * and kept identical across processes, which is one more thing to get wrong for no gain.
 *
 * <p><b>Why no trigonometry anywhere in here.</b> Gradients come from a fixed table of eight
 * directions selected by hash bits, not from {@code sin}/{@code cos} of a hashed angle. Java permits
 * {@code Math.sin} to differ by an ulp between platforms, and an ulp in a gradient is a different
 * height field, which is a desync (D16-R61). Everything in this class is integer arithmetic and IEEE
 * addition, subtraction and multiplication, all of which are exactly specified.
 *
 * <p>Everything is computed in {@code double} and returned as {@code float}. The intermediate
 * precision costs nothing here and keeps the accumulated error of a five-octave sum well below the
 * point where the final narrowing could differ between two machines that agree on every operation.
 */
public final class TerrainNoise {

    /**
     * Eight unit gradients, on the diagonals and axes of the plane.
     *
     * <p>The diagonal entries are normalised so no direction is favoured: an unnormalised
     * {@code (1,1)} is 1.41 times the length of {@code (1,0)} and shows up as a visible bias along
     * the diagonals of the lattice — a regular quilting that is unmistakable once seen on a dune
     * field, and invisible in a single-octave test.
     */
    private static final double[] GRADIENTS = {
        1.0,
        0.0,
        -1.0,
        0.0,
        0.0,
        1.0,
        0.0,
        -1.0,
        0.7071067811865476,
        0.7071067811865476,
        -0.7071067811865476,
        0.7071067811865476,
        0.7071067811865476,
        -0.7071067811865476,
        -0.7071067811865476,
        -0.7071067811865476
    };

    /** Default frequency multiplier between successive octaves (D16-R29). */
    public static final double LACUNARITY = 2.0;

    /** Default amplitude multiplier between successive octaves (D16-R29). */
    public static final double GAIN = 0.5;

    private TerrainNoise() {
        throw new AssertionError("no instances");
    }

    /**
     * Gradient noise at a point, in roughly {@code [-1, 1]}.
     *
     * @param x lattice-space x
     * @param z lattice-space z
     * @param octave mixed into the hash so two octaves of the same field are uncorrelated
     * @param seed the terrain seed
     */
    public static double gradientNoise(double x, double z, int octave, long seed) {
        int x0 = floor(x);
        int z0 = floor(z);
        double fx = x - x0;
        double fz = z - z0;

        // Quintic rather than cubic (D16-R31). The cubic smoothstep is continuous in value and in
        // its first derivative but not its second, and the discontinuity lands on every lattice
        // boundary — invisible in a heightmap image, and felt through a suspension at speed as a
        // regular jolt every `1 / frequency` metres.
        double u = fade(fx);
        double v = fade(fz);

        double n00 = dot(x0, z0, octave, seed, fx, fz);
        double n10 = dot(x0 + 1, z0, octave, seed, fx - 1.0, fz);
        double n01 = dot(x0, z0 + 1, octave, seed, fx, fz - 1.0);
        double n11 = dot(x0 + 1, z0 + 1, octave, seed, fx - 1.0, fz - 1.0);

        double a = n00 + u * (n10 - n00);
        double b = n01 + u * (n11 - n01);
        return a + v * (b - a);
    }

    /**
     * Fractal Brownian motion: {@code octaves} of {@link #gradientNoise} at doubling frequency and
     * halving amplitude, normalised to roughly {@code [-1, 1]} (D16-R29).
     *
     * <p>Octaves are summed in ascending order and that order is part of the contract (D16-R61 rule
     * 3): floating-point addition is not associative, so the same terms added in a different order
     * are a different number, and a different number here is a different arena.
     *
     * @param x world x, metres
     * @param z world z, metres
     * @param frequency cycles per metre of the first octave
     * @param octaves how many, at least one
     * @param seed the terrain seed
     */
    public static double fbm(double x, double z, double frequency, int octaves, long seed) {
        double sum = 0.0;
        double norm = 0.0;
        double amp = 1.0;
        double freq = frequency;
        for (int octave = 0; octave < octaves; octave++) {
            sum += amp * gradientNoise(x * freq, z * freq, octave, seed);
            norm += amp;
            freq *= LACUNARITY;
            amp *= GAIN;
        }
        return norm == 0.0 ? 0.0 : sum / norm;
    }

    /** The dot product of the lattice point's gradient with the offset to the sample. */
    private static double dot(int ix, int iz, int octave, long seed, double dx, double dz) {
        int g = (int) (hash(ix, iz, octave, seed) >>> 60) & 7;
        return GRADIENTS[g * 2] * dx + GRADIENTS[g * 2 + 1] * dz;
    }

    /**
     * The quintic fade curve {@code 6t⁵ − 15t⁴ + 10t³} (D16-R31), in Horner form.
     *
     * <p>Horner rather than the written-out polynomial because it is three multiplies instead of
     * seven and — the reason that matters here — it fixes the evaluation order, so this is one
     * expression rather than one of several arithmetically equal ones.
     */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    /**
     * A 64-bit hash of a lattice cell, an octave and the seed.
     *
     * <p>SplitMix64's finalising mix over the four inputs folded together with large odd
     * multipliers. The same construction {@code RandomSource} uses to derive a stream from the match
     * seed, and chosen for the same reason: it avalanches well enough that adjacent lattice cells,
     * which differ in one low bit, produce completely unrelated gradients.
     */
    static long hash(int ix, int iz, int octave, long seed) {
        long h = seed;
        h ^= (ix & 0xFFFFFFFFL) * 0x9E3779B97F4A7C15L;
        h ^= (iz & 0xFFFFFFFFL) * 0xC2B2AE3D27D4EB4FL;
        h ^= (octave & 0xFFFFFFFFL) * 0x165667B19E3779F9L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    /**
     * Floor to an int.
     *
     * <p>{@code (int) x} truncates toward zero, so it is wrong for every negative coordinate — and
     * an arena is centred on the origin, so half of it is negative. The failure is a single row of
     * lattice cells at {@code x = 0} and {@code z = 0} behaving as though they were their positive
     * mirror, which reads as a seam through the middle of the map.
     */
    private static int floor(double x) {
        int i = (int) x;
        return x < i ? i - 1 : i;
    }
}
