/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

/**
 * The physical parameters of a chassis or a wheel that no {@code StatBlock} stat can carry
 * (docs/08_asset_pipeline.md#D08-S4.2, docs/06_physics_simulation.md#D06-S4.5).
 *
 * <p>D05-S4.5 fixes a closed list of fourteen stats, and every one of them is something a part
 * <em>contributes</em> to its vehicle — a force, a grip figure, a rate, summed or averaged across
 * the parts that declare it. Aerodynamic drag, rolling resistance, downforce, suspension damping and
 * roll influence are none of those: they are properties of one body or one corner, and adding two
 * chassis together must not add their drag coefficients. So they are authored as a {@code handling}
 * object on the part (D08-R5, amended in the same commit as this class) rather than as stats
 * (DEC-031).
 *
 * <p>Chassis parts use the first three fields and wheel parts use the rest. Nothing enforces the
 * split — a wheel that declares a drag coefficient is simply never read for it — because the
 * alternative is two near-identical records and a schema that has to guess which one a part meant.
 *
 * <p><b>This record is also where D06-S4.5's reference chassis table lives in code.</b>
 * {@link #REFERENCE} is what a part that authors no {@code handling} block gets, and
 * {@code VehicleFactory}, {@code VehicleStatsSystem} and {@code VehicleControlSystem} take their
 * defaults from it rather than declaring their own — one table in the blueprint, one table in the
 * code (DEC-029, DEC-031).
 */
public record HandlingBlock(
        float dragCoefficient,
        float rollingResistance,
        float downforceCoefficient,
        float suspensionCompression,
        float suspensionDamping,
        float rollInfluence,
        float suspensionRestLengthM,
        float maxSuspensionTravelCm,
        float maxSuspensionForceN) {

    /**
     * Newtons per (m/s)². Aerodynamic drag, {@code k_drag} in D05-S5.6 phase 4.
     *
     * <p>Not in D06-S4.5's table and not in D08-R5's schema before this commit; DEC-027 records why
     * it is a constant. 12.0 is a game figure rather than a physical one — it puts a 20 kN chassis's
     * top speed at the arena's own 40 m/s limit, which is what a vehicle with no authored
     * aerodynamics should do. An authored chassis uses its real {@code ½·ρ·Cd·A} instead, which for
     * a road supercar is nearer 0.5.
     */
    public static final float REFERENCE_DRAG_COEFFICIENT = 12.0f;

    /** Dimensionless rolling resistance, {@code k_roll} in D05-S5.6 phase 4; a road tyre's figure. */
    public static final float REFERENCE_ROLLING_RESISTANCE = 0.015f;

    /** Newtons per (m/s)². Downforce at the centre of mass (D06-S4.5, D01-S5.2's mild assist). */
    public static final float REFERENCE_DOWNFORCE_COEFFICIENT = 6.0f;

    /** Damping while the suspension compresses (D06-S4.5 {@code suspensionCompression}). */
    public static final float REFERENCE_SUSPENSION_COMPRESSION = 2.4f;

    /** Damping while the suspension relaxes (D06-S4.5 {@code suspensionDamping}). */
    public static final float REFERENCE_SUSPENSION_DAMPING = 2.3f;

    /** How much lateral force induces body roll, {@code [0,1]} (D06-S4.5). */
    public static final float REFERENCE_ROLL_INFLUENCE = 0.15f;

    /** Metres of suspension travel above the wheel's contact point (D06-S4.5). */
    public static final float REFERENCE_SUSPENSION_REST_LENGTH_M = 0.30f;

    /** Centimetres. Caps how far a wheel travels before the suspension bottoms out (D06-S4.5). */
    public static final float REFERENCE_MAX_SUSPENSION_TRAVEL_CM = 25f;

    /** Newtons. Caps the spring force one wheel may push with, which is what stops a launch. */
    public static final float REFERENCE_MAX_SUSPENSION_FORCE_N = 15_000f;

    /** Every field at its reference value: what a part authoring no {@code handling} block gets. */
    public static final HandlingBlock REFERENCE = new HandlingBlock(
            REFERENCE_DRAG_COEFFICIENT,
            REFERENCE_ROLLING_RESISTANCE,
            REFERENCE_DOWNFORCE_COEFFICIENT,
            REFERENCE_SUSPENSION_COMPRESSION,
            REFERENCE_SUSPENSION_DAMPING,
            REFERENCE_ROLL_INFLUENCE,
            REFERENCE_SUSPENSION_REST_LENGTH_M,
            REFERENCE_MAX_SUSPENSION_TRAVEL_CM,
            REFERENCE_MAX_SUSPENSION_FORCE_N);

    public HandlingBlock {
        requirePositive(dragCoefficient, "dragCoefficient");
        requireNonNegative(rollingResistance, "rollingResistance");
        requireNonNegative(downforceCoefficient, "downforceCoefficient");
        requirePositive(suspensionCompression, "suspensionCompression");
        requirePositive(suspensionDamping, "suspensionDamping");
        requireNonNegative(rollInfluence, "rollInfluence");
        requirePositive(suspensionRestLengthM, "suspensionRestLengthM");
        requirePositive(maxSuspensionTravelCm, "maxSuspensionTravelCm");
        requirePositive(maxSuspensionForceN, "maxSuspensionForceN");
    }

    /** This block with a different chassis aero set. */
    public HandlingBlock withChassisAero(float drag, float rolling, float downforce) {
        return new HandlingBlock(
                drag,
                rolling,
                downforce,
                suspensionCompression,
                suspensionDamping,
                rollInfluence,
                suspensionRestLengthM,
                maxSuspensionTravelCm,
                maxSuspensionForceN);
    }

    /** This block with a different corner set. */
    public HandlingBlock withSuspension(float compression, float damping, float roll) {
        return new HandlingBlock(
                dragCoefficient,
                rollingResistance,
                downforceCoefficient,
                compression,
                damping,
                roll,
                suspensionRestLengthM,
                maxSuspensionTravelCm,
                maxSuspensionForceN);
    }

    private static void requirePositive(float value, String field) {
        if (!(value > 0f)) {
            // NaN fails this, deliberately: a NaN drag coefficient turns every derived speed on the
            // vehicle into a NaN, several systems away from the file that carried it.
            throw new IllegalArgumentException(field + " must be positive, was " + value);
        }
    }

    private static void requireNonNegative(float value, String field) {
        if (!(value >= 0f)) {
            throw new IllegalArgumentException(field + " must not be negative, was " + value);
        }
    }
}
