/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.util;

import com.badlogic.gdx.math.Vector3;

/**
 * Vector draws from a seeded stream (docs/06_physics_simulation.md#D06-S5.8).
 *
 * <p>Every draw here takes an explicit {@link Pcg32}, never a global. Gameplay randomness comes from
 * {@code World.random().stream(...)} and nothing else (G4), and a helper that could reach for a
 * shared generator would be the shortest path to breaking that.
 *
 * <p>The unit-vector draw is the sphere-point-picking method: sample {@code z} uniformly in
 * {@code [-1, 1]} and the azimuth uniformly in {@code [0, 2π)}. That is uniform over the sphere's
 * surface, whereas the obvious alternative — three uniform components, normalised — is not: it
 * concentrates points toward the cube's corners, so shard scatter would have eight preferred
 * directions instead of none.
 */
public final class RandomVectors {

    private RandomVectors() {
        throw new AssertionError("no instances");
    }

    /** Writes a unit vector uniformly distributed over the sphere into {@code out}. */
    public static Vector3 nextUnitVector(Pcg32 random, Vector3 out) {
        float z = random.nextFloat(-1f, 1f);
        float azimuth = random.nextFloat(0f, (float) (Math.PI * 2.0));
        float radius = (float) Math.sqrt(Math.max(0f, 1f - z * z));
        return out.set(radius * (float) Math.cos(azimuth), radius * (float) Math.sin(azimuth), z);
    }

    /** Writes a vector with each component uniform in {@code [-bound, bound]} into {@code out}. */
    public static Vector3 nextVectorInCube(Pcg32 random, float bound, Vector3 out) {
        return out.set(
                random.nextFloat(-bound, bound), random.nextFloat(-bound, bound), random.nextFloat(-bound, bound));
    }

    /**
     * Shortens {@code vector} to {@code maxMagnitude} if it is longer, leaving it alone otherwise.
     *
     * <p>Clamping rather than asserting is deliberate for debris (D06-R30): a shard above the clamp
     * is a symptom of overlapping spawn positions, which the harness's PROG-009 check reports, and
     * at runtime the game stays playable instead of aborting on a cosmetic anomaly.
     */
    public static Vector3 clampMagnitude(Vector3 vector, float maxMagnitude) {
        float lengthSquared = vector.len2();
        if (lengthSquared > maxMagnitude * maxMagnitude && lengthSquared > 0f) {
            vector.scl(maxMagnitude / (float) Math.sqrt(lengthSquared));
        }
        return vector;
    }
}
