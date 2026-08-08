/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.check;

/**
 * One check's result (docs/14_test_environment.md#D14-S4.3, #D14-S4.4).
 *
 * <p>Check ids are globally unique and permanent (D14-R6). Retiring a check retires its id; reports
 * are archived and diffed over time, so a reused id would silently compare two different
 * assertions.
 *
 * <p>The scalar fields are what let an agent reason about *how far off* a failure is rather than
 * only that it failed — D14-R8 requires them whenever the check is scalar-valued, which is why
 * they are boxed: {@code null} means "this check is not a measurement", not "zero".
 */
public record Check(
        String id,
        String name,
        Category category,
        Status status,
        String expected,
        String actual,
        Double expectedValue,
        Double actualValue,
        Double tolerance,
        Double delta,
        String details,
        long durationMs) {

    /** The check categories of D14-S4.3, in the order D14-R4 ranks their exit codes. */
    public enum Category {
        ASSET,
        PHYSICS,
        PROGRESSION,
        VEHICLE,
        GOLDEN
    }

    /** Exactly one of these, per D14-R8. */
    public enum Status {
        PASS("pass"),
        FAIL("fail"),
        WARNING("warning"),
        SKIPPED("skipped");

        private final String json;

        Status(String json) {
            this.json = json;
        }

        /** The lowercase spelling the report schema requires. */
        public String json() {
            return json;
        }
    }

    /** True when this check should drive a non-zero exit (D14-S5.9). */
    public boolean isBlocking() {
        return status == Status.FAIL;
    }
}
