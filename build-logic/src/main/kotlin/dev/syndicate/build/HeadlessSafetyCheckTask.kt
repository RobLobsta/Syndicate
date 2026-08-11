package dev.syndicate.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Asserts no `game-core` source imports a package that would require a display
 * (docs/02_technical_architecture.md#D02-R9, G17).
 *
 * G17 is structural, not aspirational: `game-core` is the module every runtime mode
 * shares, so a rendering import that compiles fine on a developer's laptop is a crash on
 * a dedicated server with no GL context — discovered at deploy time, not at build time.
 *
 * Scanning imports rather than bytecode is deliberate. A fully-qualified reference in a
 * method body would slip through, but it is also visible in review in a way an import
 * list is not, and bytecode scanning would need the compiled classes, moving this check
 * out of CI stage 0 where D12-S5.4 puts it.
 */
@CacheableTask
abstract class HeadlessSafetyCheckTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * Declared so a dependency change re-runs the check, and so the task's inputs describe
     * what it actually guards. Unused by the scan itself.
     */
    @get:Classpath
    @get:Optional
    abstract val runtimeClasspath: ConfigurableFileCollection

    /**
     * The directory violation paths are reported relative to.
     *
     * Read from a property rather than from `project.rootDir`, because with the configuration
     * cache on a project read at execution time fails the build — and this task only reaches
     * the reporting path when it has a violation to report, so the failure would have arrived
     * disguised as the first real violation anyone hit. See DISC-021.
     */
    @get:Internal
    abstract val reportRoot: DirectoryProperty

    init {
        group = "verification"
        description = "Asserts game-core imports nothing that needs a display (D02-R9, G17)."
    }

    @TaskAction
    fun check() {
        val violations = mutableListOf<String>()

        sources.files.filter { it.isDirectory }.forEach { srcDir ->
            srcDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
                file.useLines { lines ->
                    lines.map { it.trim() }
                        .filter { it.startsWith("import ") && it.endsWith(";") }
                        .map { it.removePrefix("import ").removePrefix("static ").removeSuffix(";").trim() }
                        .forEach { imported ->
                            val banned = ModuleRules.bannedCorePackages.firstOrNull { prefix ->
                                imported == prefix || imported.startsWith("$prefix.")
                            }
                            if (banned != null && !isAllowlisted(imported)) {
                                violations += "${rel(file)}: import '$imported' matches banned prefix '$banned'"
                            }
                        }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("D02-R9 headless safety violations (G17):")
                    violations.sorted().forEach { appendLine("  $it") }
                    appendLine(
                        "Allowlisted mesh-data types: " +
                            ModuleRules.allowedGraphicsTypes.joinToString(", "),
                    )
                },
            )
        }
    }

    /**
     * The allowlist matches a type or anything nested inside it, so an inner enum of an
     * allowed mesh-data type does not need its own entry. A wildcard import of an allowed
     * type's *package* is not allowlisted — it would pull in the banned siblings too.
     */
    private fun isAllowlisted(imported: String): Boolean =
        ModuleRules.allowedGraphicsTypes.any { imported == it || imported.startsWith("$it.") }

    private fun rel(file: File): String =
        file.relativeToOrSelf(reportRoot.get().asFile).invariantSeparatorsPath
}
