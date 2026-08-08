package dev.syndicate.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Asserts a module's internal dependencies obey the layering of
 * docs/02_technical_architecture.md#D02-S5.6 and the "MUST NOT depend on" column of
 * D02-S4.5.
 *
 * The check is per-module rather than whole-graph on purpose: a whole-graph task would
 * have to resolve every project's configurations from the root, which the configuration
 * cache treats as cross-project access. `:checkLayering` on the root aggregates the
 * per-module tasks instead, and the acyclicity assertion of D02-S5.6 comes for free —
 * strict layer ordering makes a cycle unrepresentable.
 */
abstract class LayeringCheckTask : DefaultTask() {

    @get:Input
    abstract val moduleName: Property<String>

    /** Names (without the leading `:`) of the internal projects this module depends on. */
    @get:Input
    abstract val projectDependencies: ListProperty<String>

    init {
        group = "verification"
        description = "Asserts internal dependencies obey the D02-S5.6 layering."
    }

    @TaskAction
    fun check() {
        val module = moduleName.get()
        val layer = ModuleRules.layers[module]
            ?: throw GradleException("no layer declared for '$module' in ModuleRules (D02-S5.6)")
        val forbidden = ModuleRules.forbiddenEdges[module].orEmpty()
        val violations = mutableListOf<String>()

        for (dependency in projectDependencies.get().distinct().sorted()) {
            val dependencyLayer = ModuleRules.layers[dependency]
            if (dependencyLayer == null) {
                violations += "$module -> $dependency: '$dependency' has no declared layer"
                continue
            }
            if (layer <= dependencyLayer) {
                violations += "$module (layer $layer) -> $dependency (layer $dependencyLayer): " +
                    "dependencies must flow strictly downward"
            }
            if (dependency in forbidden) {
                violations += "$module -> $dependency: forbidden by the D02-S4.5 " +
                    "'MUST NOT depend on' column"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("D02-S5.6 layering violations:")
                    violations.forEach { appendLine("  $it") }
                },
            )
        }
    }
}
