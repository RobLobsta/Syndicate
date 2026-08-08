/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lint rules L1–L15 of docs/13_persistent_memory_system.md#D13-S5.8.
 *
 * <p>Every failure names the file, so a violation is fixable without re-reading D13. This runs in
 * CI stage 0 (D12-S5.4) alongside the doc validator, because both fail in under a second and both
 * catch the class of breakage that is cheapest to fix immediately and most expensive to discover
 * three sessions later.
 */
public final class MemoryLinter {

    /** Front-matter fields, in the order D13-R4 fixes (rule L3). */
    private static final List<String> REQUIRED_FIELDS = List.of("Date", "Category", "Related Docs", "Status");

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DISPOSITION = Pattern.compile(
            "Blueprint Disposition:\\s*[`*_]*(DOC_SHOULD_CHANGE|IMPL_SHOULD_CHANGE|BOTH_VALID|UNRESOLVED)");
    private static final Pattern REQUIREMENT_CITATION = Pattern.compile("\\(D\\d\\d-R\\d+\\)");

    /**
     * Credential shapes for rule L14. Deliberately narrow: a scanner that fires on any long
     * alphanumeric string trains people to add exclusions, which defeats it.
     */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("(?i)(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_-]{12,}"));

    private final MemoryStore store;
    private final BlueprintIndex blueprints;
    private final List<String> failures = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public MemoryLinter(MemoryStore store, BlueprintIndex blueprints) {
        this.store = store;
        this.blueprints = blueprints;
    }

    /** Runs every rule. Returns the failures; an empty list means the memory system is conformant. */
    public List<String> lint() throws IOException {
        // L12 — nothing outside the permitted structure.
        store.structureViolations().forEach(violation -> fail(violation, "L12"));

        List<MemoryEntry> all = store.allEntries();
        Map<String, MemoryEntry> byId = new HashMap<>();
        Set<String> duplicates = new HashSet<>();

        for (MemoryEntry entry : all) {
            if (byId.putIfAbsent(entry.id(), entry) != null) {
                duplicates.add(entry.id());
            }
        }
        duplicates.forEach(id -> fail("duplicate entry ID '" + id + "' under .agent-memory/", "L11"));

        for (MemoryEntry entry : all) {
            lintEntry(entry, byId);
        }
        lintSupersessions(all, byId);
        lintIndexFreshness();
        return List.copyOf(failures);
    }

    private void lintEntry(MemoryEntry entry, Map<String, MemoryEntry> byId) {
        String name = relativeName(entry);
        String stem = entry.file().getFileName().toString().replace(".md", "");

        // L1 — filename stem matches the heading ID.
        if (!stem.equals(entry.id())) {
            fail(name + ": filename stem '" + stem + "' does not match heading ID '" + entry.id() + "'", "L1");
        }

        // L2 — ID form, and prefix matches the directory.
        if (!MemoryEntry.ID_PATTERN.matcher(entry.id()).matches()) {
            fail(name + ": ID '" + entry.id() + "' does not match " + MemoryEntry.ID_PATTERN.pattern(), "L2");
        } else if (!entry.id().startsWith(entry.directoryCategory().prefix() + "-")) {
            fail(
                    name + ": prefix of '" + entry.id() + "' does not match directory '"
                            + entry.directoryCategory().directory() + "'",
                    "L2");
        }

        // L3 — all front-matter fields present, in order, with valid values.
        List<String> declared = entry.fieldOrder();
        if (!declared.equals(REQUIRED_FIELDS)) {
            fail(name + ": front matter is " + declared + ", expected exactly " + REQUIRED_FIELDS + " in order", "L3");
        }
        if (entry.title().isBlank()) {
            fail(name + ": missing title on the '# ID: Title' heading", "L3");
        } else if (entry.title().length() > 80) {
            fail(name + ": title is " + entry.title().length() + " characters, limit is 80", "L3");
        }
        if (!entry.declaredCategory().equals(entry.directoryCategory().directory())) {
            fail(
                    name + ": Category is '" + entry.declaredCategory() + "' but the file is in '"
                            + entry.directoryCategory().directory() + "/'",
                    "L3");
        }
        if (!isValidStatus(entry.status())) {
            fail(name + ": Status '" + entry.status() + "' is not active | superseded (by ID) | resolved", "L3");
        }

        // L4 — the date parses and is not in the future.
        if (!DATE.matcher(entry.date()).matches()) {
            fail(name + ": Date '" + entry.date() + "' is not YYYY-MM-DD", "L4");
        } else {
            try {
                if (LocalDate.parse(entry.date()).isAfter(LocalDate.now())) {
                    fail(name + ": Date '" + entry.date() + "' is in the future", "L4");
                }
            } catch (DateTimeParseException e) {
                fail(name + ": Date '" + entry.date() + "' is not a real date", "L4");
            }
        }

        lintHeadings(entry, name);
        lintCitations(entry, name);

        // L7 — word count.
        int words = entry.proseWordCount();
        if (words > 500) {
            fail(name + ": " + words + " words exceeds the 500-word limit; split it (D13-S5.7)", "L7");
        }

        // L8 — a supersession names an entry that exists, in the same category.
        String supersededBy = entry.supersededBy();
        if (supersededBy != null) {
            MemoryEntry target = byId.get(supersededBy);
            if (target == null) {
                fail(name + ": superseded by '" + supersededBy + "', which does not exist", "L8");
            } else if (target.directoryCategory() != entry.directoryCategory()) {
                fail(name + ": superseded by '" + supersededBy + "', which is in a different category", "L8");
            }
        }

        // L10 — deviation entries declare a disposition and cite a requirement.
        if (entry.directoryCategory() == MemoryCategory.SPEC_DEVIATIONS) {
            String impact = entry.section("Impact");
            if (!DISPOSITION.matcher(impact).find()) {
                fail(name + ": no 'Blueprint Disposition:' line with a permitted value in ## Impact", "L10");
            }
            String whole = String.join("\n", entry.lines());
            if (!REQUIREMENT_CITATION.matcher(whole).find()) {
                fail(name + ": does not cite a requirement number such as (D06-R14) (D13-R14)", "L10");
            }
        }

        // L14 — secret scan.
        List<String> lines = entry.lines();
        for (int i = 0; i < lines.size(); i++) {
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(lines.get(i)).find()) {
                    // Never echo the matched text; that would copy the secret into the build log.
                    fail(name + ":" + (i + 1) + ": credential-shaped string; remove it and rotate (D13-E9)", "L14");
                }
            }
        }

        // L15 — progress entries carry a status table, and blocked rows name a blocker.
        if (entry.directoryCategory() == MemoryCategory.PROGRESS) {
            lintProgressEntry(entry, name);
        }
    }

    /** L5 — required headings present and in order; extras only after {@code ## Impact}. */
    private void lintHeadings(MemoryEntry entry, String name) {
        List<String> headings = entry.headings();
        int cursor = 0;
        for (String required : MemoryEntry.REQUIRED_HEADINGS) {
            int found = headings.subList(cursor, headings.size()).indexOf(required);
            if (found < 0) {
                fail(name + ": missing or out-of-order required heading '## " + required + "'", "L5");
                return;
            }
            cursor += found + 1;
        }
        for (String extra : headings.subList(cursor, headings.size())) {
            if (!MemoryEntry.OPTIONAL_HEADINGS.contains(extra)) {
                fail(
                        name + ": heading '## " + extra + "' is not permitted; D13-R7 allows only "
                                + MemoryEntry.OPTIONAL_HEADINGS,
                        "L5");
            }
        }
    }

    /** L6 — every {@code Related Docs} citation resolves to a declared blueprint section. */
    private void lintCitations(MemoryEntry entry, String name) {
        if (entry.citesNothing()) {
            if (entry.directoryCategory() != MemoryCategory.SESSION_SUMMARIES) {
                fail(name + ": 'Related Docs: none' is only permitted for session summaries (D13-R5)", "L6");
            }
            return;
        }
        List<MemoryEntry.Citation> citations = entry.citations();
        if (citations.isEmpty()) {
            fail(name + ": Related Docs contains no resolvable citation and is not 'none'", "L6");
            return;
        }
        for (MemoryEntry.Citation citation : citations) {
            if (!blueprints.isDeclared(citation.sectionId())) {
                fail(name + ": dangling citation '" + citation.sectionId() + "'", "L6");
                continue;
            }
            String declaringFile = blueprints.fileOf(citation.sectionId());
            if (!declaringFile.equals(citation.file())) {
                fail(
                        name + ": '" + citation.sectionId() + "' cited as " + citation.file() + " but declared in "
                                + declaringFile,
                        "L6");
            }
        }
    }

    /** L15 — {@code Status of Work} table, valid states, and a named blocker on every blocked row. */
    private void lintProgressEntry(MemoryEntry entry, String name) {
        String details = entry.section("Details");
        if (!details.contains("**Status of Work:**")) {
            fail(name + ": progress entries need a '**Status of Work:**' table (D13-S5.9)", "L15");
            return;
        }
        Set<String> validStates = Set.of("not_started", "in_progress", "done", "blocked");
        for (String line : details.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|") || trimmed.startsWith("|---") || trimmed.startsWith("| Area")) {
                continue;
            }
            String[] cells = trimmed.split("\\|", -1);
            if (cells.length < 4) {
                continue;
            }
            String state = cells[2].trim();
            if (state.isEmpty()) {
                continue;
            }
            if (!validStates.contains(state)) {
                fail(name + ": invalid state '" + state + "'; D13-R26 permits " + validStates, "L15");
            } else if ("blocked".equals(state) && cells[3].trim().isEmpty()) {
                fail(name + ": a 'blocked' row must name what it is blocked on (D13-R26): " + cells[1].trim(), "L15");
            }
        }
    }

    /** L9 — if A says it supersedes B, then B's status must say superseded by A. */
    private void lintSupersessions(List<MemoryEntry> all, Map<String, MemoryEntry> byId) {
        Pattern supersedes = Pattern.compile("Supersedes:\\s*([A-Z]+-\\d{3,})");
        for (MemoryEntry entry : all) {
            var matcher = supersedes.matcher(entry.section("Details"));
            while (matcher.find()) {
                String olderId = matcher.group(1);
                MemoryEntry older = byId.get(olderId);
                if (older == null) {
                    fail(relativeName(entry) + ": claims to supersede '" + olderId + "', which does not exist", "L9");
                } else if (!entry.id().equals(older.supersededBy())) {
                    fail(
                            relativeName(older) + ": is superseded by '" + entry.id()
                                    + "' but its Status does not say so",
                            "L9");
                }
            }
            // D13-E14: an UNRESOLVED deviation older than 30 days is a warning, not a failure.
            if (entry.directoryCategory() == MemoryCategory.SPEC_DEVIATIONS
                    && entry.section("Impact").contains("UNRESOLVED")
                    && DATE.matcher(entry.date()).matches()
                    && LocalDate.parse(entry.date()).isBefore(LocalDate.now().minusDays(30))) {
                warnings.add(relativeName(entry) + ": UNRESOLVED deviation older than 30 days (D13-E14)");
            }
        }
    }

    /** L13 — the committed index equals a freshly generated one, apart from the Generated line. */
    private void lintIndexFreshness() throws IOException {
        var indexFile = store.indexFile();
        if (!Files.isRegularFile(indexFile)) {
            fail("INDEX.md: missing; run :memory-system:regenerateIndex", "L13");
            return;
        }
        String committed = Files.readString(indexFile, StandardCharsets.UTF_8);
        String fresh = IndexGenerator.render(store, LocalDate.now());
        if (!IndexGenerator.withoutGeneratedLine(committed).equals(IndexGenerator.withoutGeneratedLine(fresh))) {
            fail(
                    "INDEX.md: differs from a freshly generated index; it is generated, never hand-edited "
                            + "(D13-R8) — run :memory-system:regenerateIndex",
                    "L13");
        }
    }

    private static boolean isValidStatus(String status) {
        return "active".equals(status)
                || "resolved".equals(status)
                || status.matches("^superseded \\(by [A-Z]+-\\d{3,}(, [A-Z]+-\\d{3,})*\\)$");
    }

    private String relativeName(MemoryEntry entry) {
        return store.root().relativize(entry.file()).toString();
    }

    private void fail(String message, String rule) {
        failures.add("[" + rule + "] " + message);
    }

    /** Non-blocking observations, printed after the failures. */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
