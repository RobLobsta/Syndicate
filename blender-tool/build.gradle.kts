/**
 * `blender-tool` is a Python project, not a JVM one (D02-S4.5). It is wired into Gradle
 * with `Exec` tasks so CI has a single entry point (D02-R11, D02-S4.6).
 *
 * Nothing here applies the Java conventions: this project has no sources, no toolchain,
 * and no layering edges.
 *
 * Availability of every external tool is resolved at configuration time into a plain
 * value. An `onlyIf` that called a helper function would capture a script object
 * reference, which the configuration cache cannot serialise.
 */

plugins {
    base
}

description = "Headless Blender fracture/morph/mass/export/verify tool (D09). Python, not JVM."

/**
 * Blender is located in the order of D02-R12: `-Pblender.exe` → `SYNDICATE_BLENDER_EXE`
 * → `blender` on PATH. The env var is read through a provider so the configuration cache
 * invalidates when it changes (D02-E10).
 */
val blenderExe: String = providers.gradleProperty("blender.exe")
    .orElse(providers.environmentVariable("SYNDICATE_BLENDER_EXE"))
    .orElse("blender")
    .get()

/** CI declares this; a developer machine does not (D02-E4, D12-E1). */
val blenderRequired: Boolean = providers.environmentVariable("SYNDICATE_REQUIRE_BLENDER")
    .map { it == "1" }
    .orElse(false)
    .get()

fun onPath(executable: String): Boolean =
    if (executable.contains('/') || executable.contains('\\')) {
        File(executable).canExecute()
    } else {
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .any { File(it, executable).canExecute() }
    }

val ruffAvailable: Boolean = onPath("ruff")
val pythonAvailable: Boolean = onPath("python3")

// `python3` existing does not mean pytest is importable, and the failure mode of assuming
// it is — a red build on a machine that never opted into the Python toolchain — is exactly
// what D02-E4 says to avoid for Blender.
val pytestAvailable: Boolean = pythonAvailable
    && providers.exec {
        commandLine("python3", "-c", "import pytest")
        isIgnoreExitValue = true
    }.result.get().exitValue == 0

val blenderAvailable: Boolean = onPath(blenderExe)

/** `ruff check` — CI stage 0 (D12-S5.4). */
val lint = tasks.register<Exec>("lint") {
    group = "verification"
    description = "ruff check syndicate_fracture tests (D02-S4.6)."
    workingDir = layout.projectDirectory.asFile
    commandLine("ruff", "check", "syndicate_fracture", "tests")
    // Hoisted into a local, and the spec uses the TASK's logger. An `onlyIf` lambda that
    // reads a script-level property or an unqualified `logger` captures the script object
    // itself, which the configuration cache cannot serialise.
    val available = ruffAvailable
    onlyIf { task ->
        if (!available) {
            task.logger.warn("SKIPPED :blender-tool:lint — ruff is not on PATH")
        }
        available
    }
}

/** Pure-Python unit tests that do not need Blender (D02-S4.6, D09-S12). */
val unitTest = tasks.register<Exec>("unitTest") {
    group = "verification"
    description = "pytest tests/unit — pure Python, no Blender (D02-S4.6)."
    workingDir = layout.projectDirectory.asFile
    commandLine("python3", "-m", "pytest", "tests/unit", "-q")
    val available = pytestAvailable
    onlyIf { task ->
        if (!available) {
            task.logger.warn("SKIPPED :blender-tool:unitTest — pytest is not importable by python3")
        }
        available
    }
}

/**
 * In-Blender tests. Skipped with a warning on developer machines, fatal on CI, which
 * declares `SYNDICATE_REQUIRE_BLENDER=1` (D02-E4).
 */
tasks.register("blenderTest") {
    group = "verification"
    description = "Runs tests/blender/ inside headless Blender (D02-S4.6)."
    val exe = blenderExe
    val required = blenderRequired
    val available = blenderAvailable
    doLast {
        if (!available) {
            val message = "Blender not found; tried '$exe' (D02-R12 lookup order)"
            if (required) {
                throw GradleException("$message — SYNDICATE_REQUIRE_BLENDER=1")
            }
            this@register.logger.warn("SKIPPED: $message")
            return@doLast
        }
        throw GradleException(
            "blender-tool is not implemented yet (docs/09_blender_destruction_tool.md); " +
                "see .agent-memory/progress/ for current state",
        )
    }
}

tasks.named("check") {
    dependsOn(lint, unitTest)
}
