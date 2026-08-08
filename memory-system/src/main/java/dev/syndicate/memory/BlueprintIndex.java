/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The table of declared blueprint section IDs, reused by memory lint rule L6
 * (docs/13_persistent_memory_system.md#D13-S5.8, docs/00_master_index.md#D00-S5.3).
 *
 * <p>Rule L6 explicitly reuses D00-S5.3's declared-ID table rather than defining its own, so a
 * citation is judged by the same standard whether it appears in a blueprint or in a memory entry.
 */
public final class BlueprintIndex {

    private static final Pattern DECLARATION = Pattern.compile("^<!--\\s*(D\\d\\d-S[\\d.]+)\\s*-->");

    private final Map<String, String> declared = new LinkedHashMap<>();

    private BlueprintIndex() {}

    /** Scans {@code docs/} and records every declared section ID and the file declaring it. */
    public static BlueprintIndex scan(Path docsDir) throws IOException {
        BlueprintIndex index = new BlueprintIndex();
        if (!Files.isDirectory(docsDir)) {
            return index;
        }
        try (Stream<Path> files = Files.list(docsDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(index::scanFile);
        }
        return index;
    }

    private void scanFile(Path file) {
        boolean inFence = false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("```")) {
                    // D00-S4.1 shows an example declaration inside a fence; it is documentation.
                    inFence = !inFence;
                    continue;
                }
                if (inFence) {
                    continue;
                }
                Matcher matcher = DECLARATION.matcher(line);
                if (matcher.find()) {
                    declared.putIfAbsent(matcher.group(1), "docs/" + file.getFileName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** True when the section ID is declared somewhere in {@code docs/}. */
    public boolean isDeclared(String sectionId) {
        return declared.containsKey(sectionId);
    }

    /** The file declaring a section ID, or null. */
    public String fileOf(String sectionId) {
        return declared.get(sectionId);
    }

    /** How many IDs were found. Zero means {@code docs/} was missing or empty. */
    public int size() {
        return declared.size();
    }
}
