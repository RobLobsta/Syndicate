/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.core.asset;

import java.util.Objects;

/**
 * One finding from asset validation (docs/08_asset_pipeline.md#D08-S5.4).
 *
 * <p>The {@link #code} is the stable identifier from D08-R16's catalogue — {@code A301},
 * {@code A306} — and is what a report, a test assertion and a bug report all name. Codes are
 * permanent and retired codes are never reused, so a message may be reworded without invalidating
 * anything that referenced the finding.
 *
 * @param code the D08-S5.4 rule code
 * @param severity what it does to a load
 * @param subject which asset or slot path the finding is about
 * @param message a human-readable description; never the thing anything matches on
 */
public record ValidationIssue(String code, Severity severity, String subject, String message) {

    /** What a finding does to a load (D08-R16). */
    public enum Severity {
        /** Never stops anything; surfaced so an outlier is deliberate rather than accidental. */
        WARN,
        /** Fails a strict load; a lenient load substitutes a fallback asset instead (G18). */
        ERROR,
        /** Always fails, in every mode. The asset cannot be interpreted at all. */
        FATAL
    }

    public ValidationIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(message, "message");
    }

    /** An {@code ERROR}, which is the majority of the catalogue. */
    public static ValidationIssue error(String code, String subject, String message) {
        return new ValidationIssue(code, Severity.ERROR, subject, message);
    }

    /** A {@code WARN}. */
    public static ValidationIssue warn(String code, String subject, String message) {
        return new ValidationIssue(code, Severity.WARN, subject, message);
    }

    /** True when this finding fails a strict load (D08-S5.4 {@code handleValidationFailure}). */
    public boolean isBlocking() {
        return severity != Severity.WARN;
    }

    @Override
    public String toString() {
        return code + " " + severity + " " + subject + ": " + message;
    }
}
