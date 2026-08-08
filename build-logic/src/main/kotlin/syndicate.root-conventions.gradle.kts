import dev.syndicate.build.ValidateDocsTask
import dev.syndicate.build.VersionCatalogCheckTask

/**
 * Repository-wide checks, applied only to the root project.
 *
 * These live in a convention plugin rather than directly in the root build script so the
 * root gets `build-logic` on its classpath the same way every other module does.
 */

plugins {
    base
}

val validateDocs = tasks.register<ValidateDocsTask>("validateDocs") {
    docsDir.set(layout.projectDirectory.dir("docs"))
}

val checkVersionCatalog = tasks.register<VersionCatalogCheckTask>("checkVersionCatalog") {
    buildScripts.from(
        layout.projectDirectory.file("settings.gradle.kts"),
        layout.projectDirectory.file("build.gradle.kts"),
        layout.projectDirectory.file("build-logic/build.gradle.kts"),
    )
    buildScripts.from(
        layout.projectDirectory.asFileTree.matching {
            include("*/build.gradle.kts")
        },
    )
}

/**
 * Aggregates the per-module layering checks so `:checkLayering` is one command, as
 * D02-S5.6 and the CI stage table (D12-S5.4) name it.
 */
val jvmModules = listOf(
    "shared-models",
    "game-core",
    "game-client",
    "game-server-headless",
    "asset-pipeline",
    "test-environment",
    "memory-system",
)

tasks.register("checkLayering") {
    group = "verification"
    description = "Runs the layering check for every JVM module (D02-S5.6)."
    dependsOn(jvmModules.map { ":$it:checkLayering" })
}

/**
 * CI stage 0: everything that can fail in under a minute, before a single class is
 * compiled. Deliberately first and deliberately cheap (D12-R12).
 */
tasks.register("fastChecks") {
    group = "verification"
    description = "CI stage 0: formatting, layering, docs, memory lint, ruff (D12-S5.4)."
    dependsOn(
        validateDocs,
        checkVersionCatalog,
        "checkLayering",
        ":memory-system:lintMemory",
        ":game-core:checkHeadlessSafety",
        ":blender-tool:lint",
    )
    dependsOn(jvmModules.map { ":$it:spotlessCheck" })
}

tasks.named("check") {
    dependsOn(validateDocs, checkVersionCatalog)
}
