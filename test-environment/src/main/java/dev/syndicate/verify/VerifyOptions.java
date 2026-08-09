/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify;

import dev.syndicate.verify.check.Check;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The CLI contract of docs/14_test_environment.md#D14-S4.2.
 *
 * <p>{@code --headless} is the default when no display is available, which is what makes the same
 * command work on a developer's laptop and in CI without a flag either place has to remember
 * (D14-S4.2).
 *
 * <p>Two subjects, exactly one per run. {@code --asset} is a processed part directory and drives the
 * destruction checks D14 is about. {@code --model} is a single glTF file and drives the source-art
 * checks of {@link dev.syndicate.verify.model.ModelInspector} — a mode that exists because the art
 * that becomes a part has to be checked before there is a part to check.
 */
public record VerifyOptions(
        Path assetDir,
        Path modelPath,
        Path reportPath,
        Set<Check.Category> categories,
        long seed,
        boolean visual,
        boolean verbose,
        boolean failFast,
        Path capturePath,
        int captureTick,
        float captureScatter) {

    /** True when this run inspects a single model file rather than a processed asset directory. */
    public boolean isModelMode() {
        return modelPath != null;
    }

    /** Default harness seed (D14-R1). */
    public static final long DEFAULT_SEED = 1337L;

    /** Thrown for a malformed invocation; maps to the usage exit. */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }

    /** Parses the argument list, applying the defaults of D14-S4.2. */
    public static VerifyOptions parse(String[] args) {
        Path assetDir = null;
        Path modelPath = null;
        Path reportPath = null;
        Path capturePath = null;
        Set<Check.Category> categories = EnumSet.allOf(Check.Category.class);
        long seed = DEFAULT_SEED;
        boolean visual = false;
        boolean headless = false;
        boolean verbose = false;
        boolean failFast = false;
        int captureTick = 18;
        float captureScatter = 1.0f;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--asset" -> assetDir = Path.of(require(args, ++i, "--asset"));
                case "--model" -> modelPath = Path.of(require(args, ++i, "--model"));
                case "--report" -> reportPath = Path.of(require(args, ++i, "--report"));
                case "--capture" -> capturePath = Path.of(require(args, ++i, "--capture"));
                case "--capture-tick" -> captureTick = Integer.parseInt(require(args, ++i, "--capture-tick"));
                case "--capture-scatter" -> captureScatter = Float.parseFloat(require(args, ++i, "--capture-scatter"));
                case "--categories" -> categories = parseCategories(require(args, ++i, "--categories"));
                case "--seed" -> seed = Long.parseLong(require(args, ++i, "--seed"));
                case "--visual" -> visual = true;
                case "--headless" -> headless = true;
                case "--verbose" -> verbose = true;
                case "--fail-fast" -> failFast = true;
                default -> throw new UsageException("unknown argument: " + arg);
            }
        }

        if (visual && headless) {
            throw new UsageException("--visual and --headless are mutually exclusive");
        }
        if (assetDir == null && modelPath == null) {
            throw new UsageException("--asset <dir> or --model <file> is required");
        }
        if (assetDir != null && modelPath != null) {
            throw new UsageException("--asset and --model are mutually exclusive");
        }
        // A capture is a rendered frame, so asking for one implies visual mode even without the
        // flag — the alternative is a run that silently produces no image.
        if (capturePath != null) {
            visual = true;
        }
        if (reportPath == null) {
            Path subject = assetDir != null ? assetDir : modelPath;
            String name = assetDir != null ? subject.getFileName().toString() : stem(subject);
            reportPath = Path.of("build", "verify", name + ".report.json");
        }

        return new VerifyOptions(
                assetDir,
                modelPath,
                reportPath,
                categories,
                seed,
                visual,
                verbose,
                failFast,
                capturePath,
                captureTick,
                captureScatter);
    }

    /** The usage text, printed on a malformed invocation. */
    public static String usage() {
        return """
            syndicate-verify [--visual | --headless]
                             (--asset <dir> | --model <file.gltf|.glb>)
                             [--categories asset,physics,progression]
                             [--report <out.json>]
                             [--seed <long>]
                             [--capture <out.png>] [--capture-tick <n>] [--capture-scatter <f>]
                             [--fail-fast] [--verbose]
            """;
    }

    /** A file name without its extension, for naming a model run's report. */
    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String require(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new UsageException(flag + " requires a value");
        }
        return args[index];
    }

    private static Set<Check.Category> parseCategories(String value) {
        Set<Check.Category> out = EnumSet.noneOf(Check.Category.class);
        for (String part : value.split(",")) {
            String trimmed = part.trim().toUpperCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Check.Category.valueOf(trimmed));
            } catch (IllegalArgumentException e) {
                throw new UsageException("unknown category '" + part.trim() + "'");
            }
        }
        if (out.isEmpty()) {
            throw new UsageException("--categories needs at least one category");
        }
        return out;
    }
}
