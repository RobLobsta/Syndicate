import dev.syndicate.build.SourceTrackingCheckTask
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
 * Catches source files that `.gitignore` silently excludes — including `build-logic`'s own,
 * which is exactly how this repository shipped a tree that would not configure from a clean
 * clone, twice. Run from the root because it spans every module's sources at once (DISC-005).
 */
val checkSourcesTracked = tasks.register<SourceTrackingCheckTask>("checkSourcesTracked") {
    repositoryRoot.set(layout.projectDirectory.asFile.absolutePath)
    // `build-logic/src` plus every module's `src`. Listed explicitly rather than globbed,
    // because a glob that walks `src/**` would itself skip the ignored files it is looking
    // for — Gradle's file trees honour nothing here, but the directory list must be built
    // from the module layout rather than from what happens to be visible.
    sources.from(
        layout.projectDirectory.asFile
            .listFiles { f: File -> f.isDirectory }
            .orEmpty()
            .map { File(it, "src") }
            .filter { it.isDirectory },
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
        checkSourcesTracked,
        "checkLayering",
        ":memory-system:lintMemory",
        ":game-core:checkHeadlessSafety",
        ":blender-tool:lint",
    )
    dependsOn(jvmModules.map { ":$it:spotlessCheck" })
}

tasks.named("check") {
    dependsOn(validateDocs, checkVersionCatalog, checkSourcesTracked)
}
