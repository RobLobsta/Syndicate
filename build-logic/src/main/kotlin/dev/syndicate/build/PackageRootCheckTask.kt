package dev.syndicate.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Asserts every Java source in a module declares a package under the module's root
 * (docs/02_technical_architecture.md#D02-R13).
 *
 * A stray package is not a style problem: `dev.syndicate.core.*` is what
 * [LayeringCheckTask] and the headless check key off, and what a reader uses to know
 * which module a stack frame came from. One file in the wrong package makes both wrong.
 */
@CacheableTask
abstract class PackageRootCheckTask : DefaultTask() {

    @get:Input
    abstract val rootPackage: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    init {
        group = "verification"
        description = "Asserts all sources sit under the module's root package (D02-R13)."
    }

    @TaskAction
    fun check() {
        val root = rootPackage.get()
        val violations = mutableListOf<String>()

        sources.files.filter { it.isDirectory }.forEach { srcDir ->
            srcDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
                val declared = declaredPackage(file)
                when {
                    declared == null ->
                        violations += "${rel(file)}: no package declaration"
                    declared != root && !declared.startsWith("$root.") ->
                        violations += "${rel(file)}: package '$declared' is not under '$root'"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("D02-R13 package root violations in '${project.name}':")
                    violations.sorted().forEach { appendLine("  $it") }
                },
            )
        }
    }

    private fun rel(file: File): String =
        file.relativeToOrSelf(project.rootDir).invariantSeparatorsPath

    /**
     * Reads the `package` line without parsing Java. Comments are the only thing that can
     * fake one, and the licence header is a block comment, so a line-anchored match on a
     * statement ending in `;` is precise enough and cannot be fooled by a `package` word
     * inside javadoc prose.
     */
    private fun declaredPackage(file: File): String? =
        file.useLines { lines ->
            lines.map { it.trim() }
                .firstOrNull { it.startsWith("package ") && it.endsWith(";") }
                ?.removePrefix("package ")
                ?.removeSuffix(";")
                ?.trim()
        }
}
