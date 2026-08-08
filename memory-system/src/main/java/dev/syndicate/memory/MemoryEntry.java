/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One parsed {@code .agent-memory} entry file (docs/13_persistent_memory_system.md#D13-S4.2).
 *
 * <p>Parsing is tolerant: a malformed entry still yields an object, with the problem recorded, so
 * the linter can report every violation in one pass rather than aborting on the first bad file.
 */
public final class MemoryEntry {

    /** D13-R5: at least three digits, widening past 999. */
    public static final Pattern ID_PATTERN = Pattern.compile("^(DEC|DISC|PROG|DEV|SESS)-\\d{3,}$");

    private static final Pattern HEADING = Pattern.compile("^#\\s+([A-Z]+-\\d{3,}):\\s*(.+)$");
    private static final Pattern FIELD = Pattern.compile("^\\*\\*([A-Za-z ]+):\\*\\*\\s*(.*)$");
    private static final Pattern SUPERSEDED = Pattern.compile("^superseded \\(by ([A-Z]+-\\d{3,})\\)$");
    private static final Pattern CITATION = Pattern.compile("docs/(\\d\\d_[a-z0-9_]+\\.md)#(D\\d\\d-S\\d[\\d.]*)");

    /** The four required headings, in order (D13-R4). */
    static final List<String> REQUIRED_HEADINGS = List.of("Summary", "Details", "Rationale / Context", "Impact");

    /** The only additional headings permitted, and only after {@code ## Impact} (D13-R7). */
    static final List<String> OPTIONAL_HEADINGS = List.of("Reproduction", "Measurements", "References", "Follow-ups");

    private final Path file;
    private final MemoryCategory directoryCategory;
    private final List<String> lines;

    private String id = "";
    private String title = "";
    private String date = "";
    private String declaredCategory = "";
    private String status = "";
    private String relatedDocsRaw = "";
    private final List<String> headings = new ArrayList<>();
    private final Map<String, String> sections = new LinkedHashMap<>();
    private final List<String> fieldOrder = new ArrayList<>();

    private MemoryEntry(Path file, MemoryCategory directoryCategory, List<String> lines) {
        this.file = file;
        this.directoryCategory = directoryCategory;
        this.lines = lines;
    }

    /** Reads and parses an entry file. */
    public static MemoryEntry parse(Path file, MemoryCategory directoryCategory) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        MemoryEntry entry = new MemoryEntry(file, directoryCategory, lines);
        entry.parseContent();
        return entry;
    }

    private void parseContent() {
        String currentHeading = null;
        StringBuilder body = new StringBuilder();

        for (String line : lines) {
            Matcher headingMatcher = HEADING.matcher(line);
            if (headingMatcher.matches()) {
                id = headingMatcher.group(1);
                title = headingMatcher.group(2).trim();
                continue;
            }

            Matcher fieldMatcher = FIELD.matcher(line);
            if (fieldMatcher.matches() && currentHeading == null) {
                String name = fieldMatcher.group(1).trim();
                String value = fieldMatcher.group(2).trim();
                fieldOrder.add(name);
                switch (name) {
                    case "Date" -> date = value;
                    case "Category" -> declaredCategory = value;
                    case "Status" -> status = value;
                    case "Related Docs" -> relatedDocsRaw = value;
                    default -> {
                        // Unknown front-matter fields are reported by the linter, not here.
                    }
                }
                continue;
            }

            if (line.startsWith("## ")) {
                if (currentHeading != null) {
                    sections.put(currentHeading, body.toString().trim());
                }
                currentHeading = line.substring(3).trim();
                headings.add(currentHeading);
                body.setLength(0);
                continue;
            }

            if (currentHeading != null) {
                body.append(line).append('\n');
            }
        }
        if (currentHeading != null) {
            sections.put(currentHeading, body.toString().trim());
        }
    }

    public Path file() {
        return file;
    }

    public MemoryCategory directoryCategory() {
        return directoryCategory;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String date() {
        return date;
    }

    public String declaredCategory() {
        return declaredCategory;
    }

    public String status() {
        return status;
    }

    public String relatedDocsRaw() {
        return relatedDocsRaw;
    }

    public List<String> headings() {
        return List.copyOf(headings);
    }

    public List<String> fieldOrder() {
        return List.copyOf(fieldOrder);
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    /** The body of a section, or an empty string. */
    public String section(String heading) {
        return sections.getOrDefault(heading, "");
    }

    /** The {@code ## Summary} text, which is what lands in {@code INDEX.md} (D13-R5). */
    public String summary() {
        return section("Summary").replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    /** The numeric part of the ID, for monotonic allocation and duplicate detection. */
    public int idNumber() {
        int dash = id.indexOf('-');
        if (dash < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(id.substring(dash + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The entry that supersedes this one, or null when the status is not {@code superseded}. */
    public String supersededBy() {
        Matcher matcher = SUPERSEDED.matcher(status);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Blueprint citations in {@code Related Docs}, in declaration order. */
    public List<Citation> citations() {
        List<Citation> citations = new ArrayList<>();
        Matcher matcher = CITATION.matcher(relatedDocsRaw);
        while (matcher.find()) {
            citations.add(new Citation("docs/" + matcher.group(1), matcher.group(2)));
        }
        return citations;
    }

    /** True when {@code Related Docs} is the literal {@code none}, permitted for SESS entries. */
    public boolean citesNothing() {
        return "none".equalsIgnoreCase(relatedDocsRaw.trim());
    }

    /**
     * Word count excluding fenced code blocks and table rows (D13-R23). Those are excluded because
     * a table is reference data, not prose, and counting it would push well-structured entries over
     * the limit for the wrong reason.
     */
    public int proseWordCount() {
        int words = 0;
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || trimmed.startsWith("|")) {
                continue;
            }
            for (String token : trimmed.split("\\s+")) {
                if (!token.isBlank()) {
                    words++;
                }
            }
        }
        return words;
    }

    /** A blueprint citation: the file it names and the stable section ID. */
    public record Citation(String file, String sectionId) {}
}
