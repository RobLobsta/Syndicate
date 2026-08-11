package dev.syndicate.build

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Asserts no `game-core` gameplay code reads cosmetic state
 * (docs/07_damage_destruction_model.md#D07-R18, AC-D07-10, T-D07-14; G6).
 *
 * G6 is one-directional and that direction is the whole of it: health drives morph
 * weights, and morph weights drive nothing. The failure this guards against is not a
 * crash — it is a gameplay rule that quietly depends on how a client chose to draw
 * something, which makes two clients disagree about a hit and cannot be found by playing.
 *
 * The check is a name scan of `game-core` sources, with exactly two exemptions: the
 * component's own declaration, and the append-only registration list of D04-R22 that has
 * to name every component type to give it a stable index. Both are named individually
 * rather than pattern-matched, so a third exemption is a visible edit to this file.
 */
@CacheableTask
abstract class CosmeticIsolationCheckTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * The directory violation paths are reported relative to.
     *
     * A property rather than a read of `project.rootDir` in the task action: with the
     * configuration cache on, touching the project at execution time fails the build — and it
     * fails only on the path that reports a violation, so the bug would surface for the first
     * time on the commit that broke the rule this task guards. See DISC-021.
     */
    @get:Internal
    abstract val reportRoot: DirectoryProperty

    init {
        group = "verification"
        description = "Asserts no game-core system reads cosmetic component state (D07-R18, G6)."
    }

    @TaskAction
    fun check() {
        val violations = mutableListOf<String>()

        val scanned = sources.files
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "java" }
            .filter { it.name !in ModuleRules.cosmeticReferenceExemptions }

        for (file in scanned) {
            file.readLines().forEachIndexed { index, line ->
                for (type in ModuleRules.cosmeticOnlyTypes) {
                    if (line.contains(type)) {
                        violations += "${rel(file)}:${index + 1}: references cosmetic type '$type'"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("D07-R18 cosmetic isolation violations (G6):")
                    violations.sorted().forEach { appendLine("  $it") }
                    appendLine(
                        "Cosmetic state is written by game-client and read by nothing. " +
                            "If a gameplay rule needs this value, it needs the authoritative " +
                            "one it was derived from instead.",
                    )
                },
            )
        }
    }

    private fun rel(file: File): String =
        file.relativeToOrSelf(reportRoot.get().asFile).invariantSeparatorsPath
}
