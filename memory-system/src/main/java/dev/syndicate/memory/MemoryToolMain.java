/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * CLI for the {@code .agent-memory} tooling (docs/13_persistent_memory_system.md#D13-R19).
 *
 * <pre>
 *   syndicate-memory regenerate &lt;memoryRoot&gt; &lt;docsDir&gt;
 *   syndicate-memory lint       &lt;memoryRoot&gt; &lt;docsDir&gt;
 * </pre>
 *
 * <p>Invoked through {@code :memory-system:regenerateIndex} and {@code :memory-system:lintMemory}.
 * The assistant may also apply the algorithms by hand; the lint is what enforces correctness either
 * way, which is why it is a CI gate and not a convenience.
 */
public final class MemoryToolMain {

    private MemoryToolMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IOException e) {
            System.err.println("memory tool failed: " + e.getMessage());
            System.exit(70);
        }
    }

    static int run(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: syndicate-memory <regenerate|lint> <memoryRoot> <docsDir>");
            return 64;
        }
        String command = args[0];
        Path memoryRoot = Path.of(args[1]);
        Path docsDir = Path.of(args[2]);

        MemoryStore store = MemoryStore.load(memoryRoot);
        BlueprintIndex blueprints = BlueprintIndex.scan(docsDir);

        return switch (command) {
            case "regenerate" -> regenerate(store, memoryRoot);
            case "lint" -> lint(store, blueprints);
            default -> {
                System.err.println("unknown command '" + command + "'; expected regenerate or lint");
                yield 64;
            }
        };
    }

    private static int regenerate(MemoryStore store, Path memoryRoot) throws IOException {
        String index = IndexGenerator.render(store, LocalDate.now());
        Files.writeString(memoryRoot.resolve("INDEX.md"), index, StandardCharsets.UTF_8);
        System.out.println(
                "regenerateIndex: wrote INDEX.md with " + store.allEntries().size() + " entries");
        return 0;
    }

    private static int lint(MemoryStore store, BlueprintIndex blueprints) throws IOException {
        MemoryLinter linter = new MemoryLinter(store, blueprints);
        List<String> failures = linter.lint();

        linter.warnings().forEach(warning -> System.out.println("lintMemory WARNING: " + warning));

        if (failures.isEmpty()) {
            System.out.println("lintMemory: OK - " + store.allEntries().size() + " entries, " + blueprints.size()
                    + " blueprint IDs available for citation");
            return 0;
        }
        System.err.println("lintMemory failed (docs/13_persistent_memory_system.md#D13-S5.8):");
        failures.forEach(failure -> System.err.println("  - " + failure));
        return 1;
    }
}
