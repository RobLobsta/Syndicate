/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.memory;

/**
 * The five memory categories of docs/13_persistent_memory_system.md#D13-S4.1.
 *
 * <p>An entry belongs to exactly one; the prefix and the directory must agree (lint rule L2).
 */
public enum MemoryCategory {
    DECISIONS("decisions", "DEC", "Decisions"),
    DISCOVERIES("discoveries", "DISC", "Discoveries"),
    PROGRESS("progress", "PROG", "Progress"),
    SPEC_DEVIATIONS("spec_deviations", "DEV", "Spec Deviations"),
    SESSION_SUMMARIES("session_summaries", "SESS", "Session Summaries");

    private final String directory;
    private final String prefix;
    private final String displayName;

    MemoryCategory(String directory, String prefix, String displayName) {
        this.directory = directory;
        this.prefix = prefix;
        this.displayName = displayName;
    }

    /** The directory name under {@code .agent-memory/}. */
    public String directory() {
        return directory;
    }

    /** The entry ID prefix, e.g. {@code DEC}. */
    public String prefix() {
        return prefix;
    }

    /** The heading used in {@code INDEX.md} (D13-S4.3). */
    public String displayName() {
        return displayName;
    }

    /** The category whose directory matches, or null. */
    public static MemoryCategory byDirectory(String directory) {
        for (MemoryCategory category : values()) {
            if (category.directory.equals(directory)) {
                return category;
            }
        }
        return null;
    }
}
