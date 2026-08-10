/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline;

/**
 * One validation result (docs/08_asset_pipeline.md#D08-S5.4).
 *
 * <p>The codes are D08-S5.4's and are permanent (D08-R16): a retired code is never reused, so a
 * build log from a year ago still means what it said. The severities are that table's too —
 * {@code FATAL} always fails, {@code ERROR} fails in strict mode and substitutes a fallback
 * otherwise (G18), {@code WARN} never fails anything.
 *
 * @param code the D08-S5.4 rule code, e.g. {@code A202}
 * @param severity how much it matters
 * @param subject the asset id or path the finding is about
 * @param message what is wrong, in a sentence an author can act on
 */
public record Finding(String code, Severity severity, String subject, String message) {

    /** D08-R16's three severities. */
    public enum Severity {
        /** Never fails a build. */
        WARN,
        /** Fails a strict build; substitutes a fallback asset otherwise (G18). */
        ERROR,
        /** Always fails, strict or not: the file could not be read as content at all. */
        FATAL
    }

    public static Finding fatal(String code, String subject, String message) {
        return new Finding(code, Severity.FATAL, subject, message);
    }

    public static Finding error(String code, String subject, String message) {
        return new Finding(code, Severity.ERROR, subject, message);
    }

    public static Finding warn(String code, String subject, String message) {
        return new Finding(code, Severity.WARN, subject, message);
    }

    /** Whether this finding fails a strict build. */
    public boolean isBlocking() {
        return severity != Severity.WARN;
    }

    @Override
    public String toString() {
        return code + " " + severity + " " + subject + ": " + message;
    }
}
