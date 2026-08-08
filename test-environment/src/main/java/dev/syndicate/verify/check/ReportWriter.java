/*
 * Syndicate — modular vehicular combat.
 * Implementation of the blueprint suite in docs/ (docs/00_master_index.md#D00-S4.2).
 */
package dev.syndicate.verify.check;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the verification report of docs/14_test_environment.md#D14-S4.4.
 *
 * <p>{@code physics_data} is emitted even when the run failed, populated as far as it got (D14-R8).
 * That is the field that makes a failing report diagnosable without a re-run, which matters most
 * for the failures that only reproduce on CI.
 */
public final class ReportWriter {

    /** Semver of the report schema. Consumers reject unknown majors (D14-R8). */
    public static final String SCHEMA_VERSION = "1.0.0";

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ReportWriter() {
        throw new AssertionError("no instances");
    }

    /** Assembles the report document. */
    public static Map<String, Object> build(
            String assetName,
            Path assetDir,
            String mode,
            long seed,
            List<Check> checks,
            Map<String, Object> physicsData,
            Tolerances tolerances,
            int exitCode,
            long durationMs) {

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", SCHEMA_VERSION);
        report.put("asset", assetName);
        report.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        report.put("mode", mode);
        report.put("harnessVersion", "0.1.0");
        report.put("seed", seed);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("assetDir", assetDir.toString());
        target.put("manifest", assetDir.resolve("fracture_manifest.json").toString());
        report.put("target", target);

        List<Map<String, Object>> checkJson = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int warnings = 0;
        int skipped = 0;
        for (Check check : checks) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", check.id());
            entry.put("name", check.name());
            entry.put("category", check.category().name());
            entry.put("status", check.status().json());
            entry.put("expected", check.expected());
            entry.put("actual", check.actual());
            entry.put("expectedValue", check.expectedValue());
            entry.put("actualValue", check.actualValue());
            entry.put("tolerance", check.tolerance());
            entry.put("delta", check.delta());
            entry.put("details", check.details());
            entry.put("durationMs", check.durationMs());
            checkJson.add(entry);
            switch (check.status()) {
                case PASS -> passed++;
                case FAIL -> failed++;
                case WARNING -> warnings++;
                case SKIPPED -> skipped++;
            }
        }
        report.put("checks", checkJson);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", checks.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("warnings", warnings);
        summary.put("skipped", skipped);
        summary.put("durationMs", durationMs);
        report.put("summary", summary);

        report.put("physics_data", physicsData);
        report.put("tolerances_applied", tolerances.asMap());
        // Mirrored into the document so an archived report is self-contained (D14-R8).
        report.put("exit_code", exitCode);
        return report;
    }

    /** Writes the report, creating parent directories as needed (D14-S4.2). */
    public static void write(Map<String, Object> report, Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, MAPPER.writeValueAsString(report) + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write report to " + path, e);
        }
    }

    /** The one-line summary the headless runner prints when not verbose (D14-S5.13). */
    public static String oneLine(Map<String, Object> report, String assetName) {
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        int failed = (int) summary.get("failed");
        int total = (int) summary.get("total");
        String firstFailure = "";
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        for (Map<String, Object> check : checks) {
            if ("fail".equals(check.get("status"))) {
                firstFailure = "  " + check.get("id");
                break;
            }
        }
        return (failed == 0 ? "PASS " : "FAIL ") + (total - failed) + "/" + total + firstFailure + "  " + assetName;
    }
}
