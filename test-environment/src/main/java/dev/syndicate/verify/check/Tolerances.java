/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.check;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The named tolerances of docs/14_test_environment.md#D14-S6.4.
 *
 * <p>Named rather than inlined at each assertion, and echoed into every report (D14-R8), because a
 * report is not interpretable without them: "COM off by 0.015 m" is a pass or a failure depending
 * on a number that must therefore travel with the result.
 */
public final class Tolerances {

    /** Fractional mass agreement, from {@code MASS_TOLERANCE_FRAC} (D00-S6.4). */
    public static final String MASS_DELTA_FRAC = "MASS_DELTA_FRAC";

    /** Absolute centre-of-mass agreement, metres. */
    public static final String COM_OFFSET_M = "COM_OFFSET_M";

    /** Relative inertia agreement. */
    public static final String INERTIA_REL = "INERTIA_REL";

    /** Relative velocity agreement, used for gravity, impulse, and momentum checks. */
    public static final String VELOCITY_REL = "VELOCITY_REL";

    /** Relative angular velocity agreement. */
    public static final String ANGULAR_VELOCITY_REL = "ANGULAR_VELOCITY_REL";

    /** Absolute resting-height agreement, metres. */
    public static final String RESTING_POSITION_M = "RESTING_POSITION_M";

    /** Speed below which a settled body counts as at rest, m/s. */
    public static final String RESTING_JITTER_MPS = "RESTING_JITTER_MPS";

    /** Maximum steady-state penetration into the ground, metres. */
    public static final String MAX_PENETRATION_M = "MAX_PENETRATION_M";

    /** Position agreement between two identical seeded runs, metres. */
    public static final String DETERMINISM_POS_M = "DETERMINISM_POS_M";

    /** Upper bound on a plausible shard speed, m/s. */
    public static final String MAX_SCATTER_SPEED_MPS = "MAX_SCATTER_SPEED_MPS";

    /** Speed difference above which two shards count as moving independently, m/s. */
    public static final String VELOCITY_EPS = "VELOCITY_EPS";

    /** Fraction of shard pairs that must move independently. */
    public static final String INDEPENDENT_FRAC = "INDEPENDENT_FRAC";

    /** How far a hull may fail to enclose its source, metres. */
    public static final String HULL_ENCLOSE_M = "HULL_ENCLOSE_M";

    /** Fraction of part volume the shard union must cover. */
    public static final String VOLUME_COVERAGE_FRAC = "VOLUME_COVERAGE_FRAC";

    private final Map<String, Double> values = new LinkedHashMap<>();

    /** The defaults of D14-S6.4. */
    public Tolerances() {
        // Mass agreement is the project-wide constant, not a harness-local number: the tool
        // checks conservation against the same value (D09-S6.2), so the two cannot disagree.
        values.put(MASS_DELTA_FRAC, (double) dev.syndicate.model.SimulationConstants.MASS_TOLERANCE_FRAC);
        values.put(COM_OFFSET_M, 0.02);
        values.put(INERTIA_REL, 0.05);
        values.put(VELOCITY_REL, 0.05);
        values.put(ANGULAR_VELOCITY_REL, 0.08);
        values.put(RESTING_POSITION_M, 0.005);
        values.put(RESTING_JITTER_MPS, 0.05);
        values.put(MAX_PENETRATION_M, 0.02);
        values.put(DETERMINISM_POS_M, 1e-4);
        values.put(MAX_SCATTER_SPEED_MPS, 60.0);
        values.put(VELOCITY_EPS, 0.05);
        values.put(INDEPENDENT_FRAC, 0.8);
        values.put(HULL_ENCLOSE_M, 0.002);
        values.put(VOLUME_COVERAGE_FRAC, 0.9);
    }

    /** The effective value of a named tolerance. */
    public double get(String name) {
        Double value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("unknown tolerance '" + name + "' (D14-S6.4)");
        }
        return value;
    }

    /** Overrides one tolerance. Every override is echoed into the report (D14-R1). */
    public void override(String name, double value) {
        get(name); // rejects an unknown name rather than silently adding one
        values.put(name, value);
    }

    /** Every effective tolerance, for the report's {@code tolerances_applied} block. */
    public Map<String, Double> asMap() {
        return new LinkedHashMap<>(values);
    }
}
