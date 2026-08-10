/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.pipeline;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.syndicate.model.ExitCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the asset validation CLI (docs/08_asset_pipeline.md#D08-S5.2, #D08-S5.4).
 *
 * <p>Reads {@code assets/}, cross-checks it against itself, writes {@code asset-index.json}, and
 * exits with a code that says whether the content is shippable. It runs before the Blender tool
 * stage in CI so that a broken fixture is attributed to the fixture rather than blamed on the tool
 * (D12-S5.3, D12-E18).
 *
 * <p><b>Strict is the build's mode, lenient is the game's.</b> Without {@code --strict} an
 * {@code ERROR} is reported and the index is still written, which is what a content author wants
 * while they are working. With it, any {@code ERROR} fails the run, which is what CI wants. A
 * {@code FATAL} — a file that is not JSON, or a schema major this build cannot read — fails either
 * way, because there is nothing to write an index from.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * syndicate-pipeline [--assets DIR] [--out FILE] [--strict]
 * </pre>
 */
public final class PipelineMain {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineMain.class);

    /** Where the asset root is looked for when {@code --assets} is not given. */
    public static final String DEFAULT_ASSET_ROOT = "assets";

    /** The index file's name, relative to the asset root (D02-S4.5's build outputs table). */
    public static final String INDEX_FILE_NAME = "asset-index.json";

    private PipelineMain() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.exit(run(args).code());
    }

    /**
     * The whole CLI, minus the exit.
     *
     * <p>Separated so a test can assert an exit code without ending the JVM, which is the same shape
     * {@code ServerMain} uses and the reason DISC-013 could be written as a test at all.
     */
    public static ExitCode run(String[] args) {
        Path assetRoot = Path.of(DEFAULT_ASSET_ROOT);
        Path outputFile = null;
        boolean strict = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--assets" -> {
                    if (++i >= args.length) {
                        LOG.error("--assets needs a directory");
                        return ExitCode.USAGE;
                    }
                    assetRoot = Path.of(args[i]);
                }
                case "--out" -> {
                    if (++i >= args.length) {
                        LOG.error("--out needs a file path");
                        return ExitCode.USAGE;
                    }
                    outputFile = Path.of(args[i]);
                }
                case "--strict" -> strict = true;
                default -> {
                    LOG.error("unknown argument \"{}\"; usage: [--assets DIR] [--out FILE] [--strict]", args[i]);
                    return ExitCode.USAGE;
                }
            }
        }
        if (!Files.isDirectory(assetRoot)) {
            LOG.error("asset root {} does not exist (D03-S4.4 ASSETS_NOT_FOUND)", assetRoot.toAbsolutePath());
            return ExitCode.ASSETS_NOT_FOUND;
        }
        Path target = outputFile == null ? assetRoot.resolve(INDEX_FILE_NAME) : outputFile;

        AssetIndexBuilder builder = new AssetIndexBuilder();
        ObjectNode index = builder.build(assetRoot);
        List<Finding> findings = builder.findings();
        for (Finding finding : findings) {
            switch (finding.severity()) {
                case FATAL, ERROR -> LOG.error("{}", finding);
                case WARN -> LOG.warn("{}", finding);
            }
        }

        boolean anyFatal = findings.stream().anyMatch(f -> f.severity() == Finding.Severity.FATAL);
        long errors = findings.stream()
                .filter(f -> f.severity() == Finding.Severity.ERROR)
                .count();
        if (anyFatal) {
            LOG.error("asset validation failed: {} findings, at least one FATAL", findings.size());
            return ExitCode.ASSETS_INVALID;
        }

        builder.write(index, target);
        LOG.info(
                "wrote {}: {} materials, {} parts, {} vehicles, {} arenas ({} errors, {} warnings)",
                target,
                index.path("materials").size(),
                index.path("parts").size(),
                index.path("vehicles").size(),
                index.path("arenas").size(),
                errors,
                findings.size() - errors);

        if (errors > 0 && strict) {
            LOG.error("strict mode: {} ERROR findings fail the build (D08-S5.4)", errors);
            return ExitCode.ASSETS_INVALID;
        }
        return ExitCode.OK;
    }
}
