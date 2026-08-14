/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.arena;

/**
 * What a square metre of ground is made of (docs/16_procedural_arena_generation.md#D16-S4.4).
 *
 * <p>The single authority for the four things a surface changes: how much grip a tyre finds on it,
 * how hard it is to roll across, what it sounds like, and whether a vehicle can be there at all.
 * D16-R11 makes this table the one place those numbers live, so tuning sand is one row rather than
 * three files that have to agree.
 *
 * <p><b>Grip multiplies the wheel's friction slip, not the body's Bullet friction</b> (D16-R12,
 * DEC-070). A ray-cast wheel never produces a Bullet contact point for the tyre, so the terrain
 * body's friction coefficient governs a chassis sliding on its roof and nothing else. Per-surface
 * grip set there would be a number that reads correctly in a review and changes nothing a driver can
 * feel.
 */
public enum Surface {

    /** Road. The reference surface: every vehicle in {@code VEHICLES.md} was calibrated on it. */
    TARMAC(1.00f, 0.015f, "tarmac"),

    /** Shoulder, verge, and the scoured faces where sand will not sit. */
    GRAVEL(0.72f, 0.028f, "gravel"),

    /**
     * Desert floor and dune faces.
     *
     * <p>Rolling resistance is four times tarmac's, which is what makes leaving the road a decision
     * rather than a shortcut. It is also the number in this table most likely to be wrong, and
     * D16-R13 names it as the first thing to tune by driving rather than by reading.
     */
    SAND(0.55f, 0.060f, "gravel"),

    /** Exposed plateau caps and anything past the angle of repose. Grips better than it looks. */
    ROCK(0.88f, 0.020f, "metal");

    private final float gripMultiplier;
    private final float rollingResistance;
    private final String audioMaterial;

    Surface(float gripMultiplier, float rollingResistance, String audioMaterial) {
        this.gripMultiplier = gripMultiplier;
        this.rollingResistance = rollingResistance;
        this.audioMaterial = audioMaterial;
    }

    /** Scales a wheel's friction slip. 1.0 is tarmac, the surface the vehicles were calibrated on. */
    public float gripMultiplier() {
        return gripMultiplier;
    }

    /** Rolling resistance coefficient, replacing the chassis default while a wheel is on it. */
    public float rollingResistance() {
        return rollingResistance;
    }

    /**
     * The tyre loop family this surface plays, matching the sound bank's naming.
     *
     * <p>{@link #SAND} maps to {@code gravel} because the bank has no sand loop (D16-R14). When one
     * exists this row changes and nothing else does — which is the point of the surface owning the
     * mapping rather than the audio system owning a switch.
     */
    public String audioMaterial() {
        return audioMaterial;
    }
}
