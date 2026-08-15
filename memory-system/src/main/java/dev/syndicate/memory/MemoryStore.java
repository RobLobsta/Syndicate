/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads every entry under {@code .agent-memory/} (docs/13_persistent_memory_system.md#D13-S4.1).
 *
 * <p>Also records any file that violates the permitted structure (D13-R2), because lint rule L12
 * needs to report those and the loader is the only place that sees them.
 */
public final class MemoryStore {

    private final Path root;
    private final Map<MemoryCategory, List<MemoryEntry>> byCategory = new EnumMap<>(MemoryCategory.class);
    /** The one subdirectory a category may hold (D13-S4.1.1). */
    public static final String ARCHIVE_DIRECTORY = "archive";

    private final List<String> structureViolations = new ArrayList<>();

    private MemoryStore(Path root) {
        this.root = root;
        for (MemoryCategory category : MemoryCategory.values()) {
            byCategory.put(category, new ArrayList<>());
        }
    }

    /** Loads the store, sorting entries by filename so output order is stable (D13-S5.5). */
    public static MemoryStore load(Path root) throws IOException {
        MemoryStore store = new MemoryStore(root);
        if (!Files.isDirectory(root)) {
            throw new IOException("memory root not found: " + root.toAbsolutePath()
                    + " — recreate the structure of D13-S4.1 and record the loss (D13-E8)");
        }

        try (Stream<Path> top = Files.list(root)) {
            for (Path path : top.sorted().toList()) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path)) {
                    if (!"INDEX.md".equals(name)) {
                        store.structureViolations.add(name + ": only INDEX.md may sit at the memory root (D13-R2)");
                    }
                    continue;
                }
                MemoryCategory category = MemoryCategory.byDirectory(name);
                if (category == null) {
                    store.structureViolations.add(name + "/: not one of the five categories (D13-R1)");
                    continue;
                }
                store.loadCategory(path, category);
            }
        }
        return store;
    }

    private void loadCategory(Path directory, MemoryCategory category) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (Files.isDirectory(file)) {
                    if (ARCHIVE_DIRECTORY.equals(name)) {
                        // D13-R2b: archived entries are kept and searchable but are not indexed, not
                        // linted, and not read at session start. Skipping them here is what makes
                        // that true of every tool built on this store.
                        continue;
                    }
                    structureViolations.add(category.directory() + "/" + name
                            + ": only an 'archive/' subdirectory is permitted below a category (D13-R2, D13-R2a)");
                    continue;
                }
                if (".gitkeep".equals(name)) {
                    continue; // D13-R2's single permitted exception
                }
                if (!name.endsWith(".md")) {
                    structureViolations.add(category.directory() + "/" + name + ": only .md entries are permitted "
                            + "(no binaries, images, or attachments — D13-R2)");
                    continue;
                }
                byCategory.get(category).add(MemoryEntry.parse(file, category));
            }
        }
        byCategory
                .get(category)
                .sort(Comparator.comparing(entry -> entry.file().getFileName().toString()));
    }

    public Path root() {
        return root;
    }

    /** Entries in one category, ascending by filename. */
    public List<MemoryEntry> entries(MemoryCategory category) {
        return List.copyOf(byCategory.get(category));
    }

    /** Every entry, grouped by category in declaration order. */
    public List<MemoryEntry> allEntries() {
        List<MemoryEntry> all = new ArrayList<>();
        for (MemoryCategory category : MemoryCategory.values()) {
            all.addAll(byCategory.get(category));
        }
        return all;
    }

    /** Files or directories that break the permitted structure (lint L12). */
    public List<String> structureViolations() {
        return List.copyOf(structureViolations);
    }

    /** The committed {@code INDEX.md}, or null when absent. */
    public Path indexFile() {
        return root.resolve("INDEX.md");
    }
}
