package dev.syndicate.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Asserts no build script carries a hard-coded dependency version
 * (docs/02_technical_architecture.md#D02-S5.5, AC-D02-5).
 *
 * The catalogue is only a single source of truth while nothing bypasses it. One inline
 * `"com.example:thing:1.2.3"` is invisible in review and produces a build where two
 * modules resolve different versions of the same library — which surfaces as a
 * `NoSuchMethodError` at runtime, far from the line that caused it.
 */
@CacheableTask
abstract class VersionCatalogCheckTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScripts: ConfigurableFileCollection

    init {
        group = "verification"
        description = "Asserts dependency versions live only in the version catalog (AC-D02-5)."
    }

    @TaskAction
    fun check() {
        val violations = mutableListOf<String>()

        for (script in buildScripts.files.filter { it.isFile }.sortedBy { it.path }) {
            script.readLines().forEachIndexed { index, raw ->
                val line = raw.substringBefore("//").trim()
                if (line.isEmpty()) return@forEachIndexed

                COORDINATE.findAll(line).forEach { match ->
                    violations += "${rel(script)}:${index + 1}: inline coordinate " +
                        "'${match.value}' — declare it in gradle/libs.versions.toml"
                }
                // `settings.gradle.kts` is exempt from the plugin-version rule: its
                // `pluginManagement` block is evaluated before the catalogue exists, so
                // the foojay resolver's version has nowhere else it could live (D02-E2).
                if (script.name != "settings.gradle.kts" && VERSION_ARGUMENT.containsMatchIn(line)) {
                    violations += "${rel(script)}:${index + 1}: inline plugin version — " +
                        "use `version.ref` in gradle/libs.versions.toml"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("AC-D02-5 version catalog violations:")
                    violations.forEach { appendLine("  $it") }
                },
            )
        }
    }

    private fun rel(file: File): String =
        file.relativeToOrSelf(project.rootDir).invariantSeparatorsPath

    private companion object {
        /**
         * A `group:artifact:version` string literal. Requires all three segments, so the
         * two-segment `module = "group:artifact"` form the catalogue itself uses is not
         * flagged, and so a `project(":game-core")` path cannot match.
         */
        val COORDINATE = Regex(""""[a-zA-Z][\w.\-]*(?:\.[\w.\-]+)+:[\w.\-]+:[\w.\-]+"""")

        /** `id("...") version "1.2.3"` in a plugins block. */
        val VERSION_ARGUMENT = Regex("""\bversion\s+"[0-9][\w.\-]*"""")
    }
}
