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

/**
 * The pre-push procedure of CLAUDE.md §8.1, as one task.
 *
 * <p>That procedure is four paragraphs of prose describing something entirely mechanical: stage
 * everything, run the generators, copy the tracked files somewhere clean, run the four stages CI
 * runs. It is written as prose because a person had to read it once, and it has now been skipped
 * twice with a runner-hour's cost each time — DISC-005 (an untracked directory that built perfectly
 * in the working tree and failed on a clean clone) and DISC-055 (a memory entry written *after* the
 * index was regenerated, so the tree that was verified no longer existed by the time it was
 * committed).
 *
 * <p>Both have the same shape, and it is the shape a task can close: **the last thing you change is
 * the thing you do not re-check.** Reproducing from a fresh copy of the *staged* tree is what
 * catches it, because that copy is made after the last edit by construction.
 *
 * <p>This task does the copying and the ordering. It deliberately does **not** `git add` for you:
 * staging is a decision about what you intend to push, and a verification task that silently staged
 * whatever was lying around would verify a tree you never chose.
 */
val verifyBeforePush by tasks.registering {
    group = "verification"
    description = "Reproduces the CI pipeline against the tracked tree, exactly as CLAUDE.md §8.1 does."

    val projectDir = layout.projectDirectory.asFile
    val workDir = layout.buildDirectory.dir("verify-before-push").get().asFile
    val gradlew = File(projectDir, "gradlew").absolutePath

    doLast {
        fun run(vararg command: String): Pair<Int, String> {
            val output = java.io.ByteArrayOutputStream()
            val result = providers.exec {
                workingDir = if (command.first() == "git") projectDir else workDir
                commandLine(*command)
                isIgnoreExitValue = true
                standardOutput = output
                errorOutput = output
            }
            return result.result.get().exitValue to output.toString()
        }

        // Step 1: refuse to verify a tree that is not the tree you would push. Unstaged changes are
        // invisible to `git ls-files -z | tar`, so verifying with them present measures a tree that
        // does not exist on either side of the push.
        val (_, status) = run("git", "status", "--porcelain")
        val unstaged = status.lines().filter { it.isNotBlank() && (it[1] != ' ' || it.startsWith("??")) }
        if (unstaged.isNotEmpty()) {
            throw GradleException(
                "there are unstaged or untracked changes; `git add -A` first, because what is not " +
                    "staged is not what CI will see (CLAUDE.md §8.1, DISC-005):\n" +
                    unstaged.joinToString("\n")
            )
        }

        // Step 2: a clean copy of the TRACKED tree. Not a `cp -r` of the working directory: build
        // outputs, .gradle caches and untracked scratch files all mask real failures.
        workDir.deleteRecursively()
        workDir.mkdirs()
        val (tarCode, tarOut) = run(
            "sh", "-c", "cd '${projectDir.absolutePath}' && git ls-files -z | xargs -0 tar -cf - | tar -xf - -C '${workDir.absolutePath}'"
        )
        if (tarCode != 0) {
            throw GradleException("could not copy the tracked tree: $tarOut")
        }
        logger.lifecycle("verifying the tracked tree in $workDir")

        // Step 3: the four stages .github/workflows/ci.yml runs, in its order, with its environment.
        val stages = listOf(
            listOf(gradlew, "fastChecks"),
            listOf(gradlew, "assemble"),
            listOf(gradlew, "test", "-Ptags=unit,integration"),
            listOf("python3", "-m", "pytest", "blender-tool/tests/unit", "-q"),
        )
        for (stage in stages) {
            logger.lifecycle("→ ${stage.joinToString(" ")}")
            val (code, out) = run(*stage.toTypedArray())
            if (code != 0) {
                throw GradleException("stage failed: ${stage.joinToString(" ")}\n$out")
            }
        }
        logger.lifecycle("all four CI stages green against the tracked tree; safe to push")
    }
}
