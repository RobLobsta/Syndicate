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

val blenderExeAvailable: Boolean = onPath(blenderExe)

/**
 * The `bpy` PyPI module is Blender 4.2 built as a Python extension — the same codebase,
 * reachable without a `blender` executable on PATH. D02-R12's lookup order still applies
 * to the executable; this is a second, equally valid host (see DEV-002), so the fracture
 * tasks below run wherever either one is present.
 */
val bpyModuleAvailable: Boolean = pythonAvailable
    && providers.exec {
        commandLine("python3", "-c", "import bpy")
        isIgnoreExitValue = true
    }.result.get().exitValue == 0

val blenderAvailable: Boolean = blenderExeAvailable || bpyModuleAvailable

/**
 * How to invoke the tool with whichever host is present. The executable is preferred when
 * both exist, because that is the invocation D09-R1 specifies and the one CI pins.
 */
fun fractureCommand(vararg toolArgs: String): List<String> =
    if (blenderExeAvailable) {
        listOf(blenderExe, "--background", "--factory-startup", "--python-expr",
            "import syndicate_fracture.__main__ as m; import sys; sys.exit(m.main())", "--") + toolArgs
    } else {
        listOf("python3", "-m", "syndicate_fracture") + toolArgs
    }

/** The preparation tool's invocation, on whichever host is present — as `fractureCommand`. */
fun prepareCommand(vararg toolArgs: String): List<String> =
    if (blenderExeAvailable) {
        listOf(blenderExe, "--background", "--factory-startup", "--python-expr",
            "import syndicate_prepare.__main__ as m; import sys; sys.exit(m.main())", "--") + toolArgs
    } else {
        listOf("python3", "-m", "syndicate_prepare") + toolArgs
    }

/** `ruff check` — CI stage 0 (D12-S5.4). */
val lint = tasks.register<Exec>("lint") {
    group = "verification"
    description = "ruff check syndicate_fracture tests (D02-S4.6)."
    workingDir = layout.projectDirectory.asFile
    commandLine("ruff", "check", "syndicate_fracture", "syndicate_dissect", "syndicate_prepare", "tests")
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
 * In-Blender tests (D02-S4.6): the fracture stage's mathematical properties, which cannot be
 * asserted without a Blender host and which no other check covers — a fracture can tile,
 * conserve mass, and build valid hulls while not being a Voronoi decomposition at all.
 */
val blenderTest = tasks.register<Exec>("blenderTest") {
    group = "verification"
    description = "Runs tests/blender/ inside a Blender host (D02-S4.6)."
    dependsOn("processFixtures")
    workingDir = layout.projectDirectory.asFile
    commandLine("python3", "-m", "pytest", "tests/blender", "-q")
    environment("PYTHONPATH", layout.projectDirectory.asFile.absolutePath)
    val available = pytestAvailable && blenderAvailable
    onlyIf { task ->
        if (!available) {
            task.logger.warn("SKIPPED :blender-tool:blenderTest — needs pytest and a Blender host")
        }
        available
    }
}

/**
 * The fixture set of D14-S7.1, with the seed and shard count each fixture records there.
 * `test_complex_hollow` was excluded while the fracture could not handle an internal cavity;
 * it processes to exit 0 since the source is convex-decomposed (DEV-004 resolved).
 */
val fixtureSpecs = listOf(
    Triple("test_cube_1m", 1001, 12),
    Triple("test_plate_2x1x0.1", 1002, 16),
    Triple("test_cylinder_r0.5_h1", 1003, 14),
    Triple("test_complex_hollow", 1004, 14),
    Triple("test_sphere_r0.5", 1006, 16),
)

/**
 * `:blender-tool:processFixtures` of D02-R11 / D14-S7.3 step 1: run the tool over every
 * fixture mesh into `build/fixtures-out/`, which `:test-environment:verifyFixtures` then
 * checks inside Bullet.
 */
tasks.register("processFixtures") {
    group = "build"
    description = "Fractures fixtures/meshes/*.glb into build/fixtures-out/ (D14-S7.3)."

    val toolDir = layout.projectDirectory.asFile
    val meshesDir = rootProject.layout.projectDirectory.dir("fixtures/meshes").asFile
    val outRoot = rootProject.layout.buildDirectory.dir("fixtures-out").get().asFile
    val materialTable =
        rootProject.layout.projectDirectory.file("assets/materials/materials.json").asFile

    // Every command line is resolved to plain strings here, at configuration time. A
    // `doLast` that called `fractureCommand(...)` or `providers.exec { }` would capture the
    // script object, which the configuration cache cannot serialise — the same trap as
    // DISC-001, in its other form.
    val invocations: List<Pair<String, List<String>>> = fixtureSpecs.map { (name, seed, shards) ->
        name to fractureCommand(
            "--input", File(meshesDir, "$name.glb").absolutePath,
            "--out", File(outRoot, name).absolutePath,
            "--seed", seed.toString(),
            "--shards", shards.toString(),
            "--damage-morphs", "4",
            "--material-table", materialTable.absolutePath,
        )
    }
    val required = blenderRequired
    val available = blenderAvailable
    val exe = blenderExe

    inputs.dir(meshesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(materialTable).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outRoot)

    doLast {
        if (!available) {
            val message = "Blender not found; tried '$exe' on PATH and the bpy module (D02-R12)"
            if (required) {
                throw GradleException("$message — SYNDICATE_REQUIRE_BLENDER=1")
            }
            this@register.logger.warn("SKIPPED :blender-tool:processFixtures — $message")
            return@doLast
        }
        for ((name, command) in invocations) {
            val process = ProcessBuilder(command)
                .directory(toolDir)
                .redirectErrorStream(false)
                .also { it.environment()["PYTHONPATH"] = toolDir.absolutePath }
                .start()
            // The tool's stdout is one JSON document (D09-R2); its stderr is the log.
            val document = process.inputStream.bufferedReader().readText()
            val diagnostics = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                throw GradleException(
                    "fracture failed for '$name' with exit $code (D09-S4.3):\n$document\n$diagnostics",
                )
            }
            this@register.logger.lifecycle("processFixtures: $name -> ${File(outRoot, name)}")
        }
    }
}

/**
 * Cuts the whole-vehicle source art into per-part meshes (DEV-013's remaining half).
 *
 * Deliberately not wired into `check` or into any other task. It writes into `assets/parts/`,
 * which is committed content: running it is a decision to re-cut the art, and it belongs in the
 * commit that does so rather than in every build. `--dry-run` reports the classification without
 * writing anything, which is the form worth running when the classifier's thresholds change.
 */
tasks.register("dissectVehicles") {
    group = "build"
    description = "Splits art-source/vehicles/* into assets/parts/* (chassis + wheels)."
    val artRoot = rootProject.layout.projectDirectory.dir("art-source/vehicles").asFile
    val partsRoot = rootProject.layout.projectDirectory.dir("assets/parts").asFile
    val projectDir = layout.projectDirectory.asFile
    val required = blenderRequired
    doLast {
        val vehicles = artRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (vehicles.isEmpty()) {
            logger.warn("SKIPPED :blender-tool:dissectVehicles — no models in $artRoot")
            return@doLast
        }
        for (vehicle in vehicles) {
            val result = providers.exec {
                workingDir = projectDir
                commandLine(
                    "python3", "-m", "syndicate_dissect",
                    "--model", vehicle.absolutePath,
                    "--vehicle", vehicle.name,
                    "--out", partsRoot.absolutePath,
                )
                isIgnoreExitValue = true
            }
            val code = result.result.get().exitValue
            if (code != 0) {
                val message = "dissect ${vehicle.name} exited $code"
                if (required) throw GradleException(message) else logger.warn("SKIPPED — $message")
                continue
            }
            logger.lifecycle("dissectVehicles: ${vehicle.name} -> $partsRoot")
        }
    }
}

tasks.named("check") {
    dependsOn(lint, unitTest)
}

/**
 * The vehicle preparation pipeline of D15, over every model in `art-source/vehicles/`.
 *
 * Strict: D15-R13 makes an under-labelled model a non-zero exit, and the point of running this
 * from the build is to find out that a newly added car needs a `parts.json` before somebody
 * spends an afternoon wondering why its doors are part of the chassis.
 *
 * Not wired into `check`, for the same reason `processFixtures` is not: it needs a Blender host,
 * takes seventeen seconds a car, and a developer without Blender must still be able to build.
 */
tasks.register("prepareVehicles") {
    group = "build"
    description = "Runs syndicate-prepare over art-source/vehicles/ (D15-S5.1)."

    val toolDir = layout.projectDirectory.asFile
    val vehiclesDir = rootProject.layout.projectDirectory.dir("art-source/vehicles").asFile
    val reportRoot = rootProject.layout.buildDirectory.dir("prepare-reports").get().asFile

    // Resolved at configuration time into plain strings: a `doLast` that built the command
    // would capture the script object, which the configuration cache cannot serialise (DISC-001).
    val models: List<Pair<String, List<String>>> =
        (vehiclesDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList())
            .map { dir ->
                dir.name to prepareCommand(
                    "--model", dir.absolutePath,
                    "--vehicle", dir.name,
                    "--strict",
                    "--report", File(reportRoot, "${dir.name}.json").absolutePath,
                )
            }
    val required = blenderRequired
    val available = blenderAvailable
    val exe = blenderExe

    inputs.dir(vehiclesDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(reportRoot)

    doLast {
        if (!available) {
            val message = "Blender not found; tried '$exe' on PATH and the bpy module (D02-R12)"
            if (required) {
                throw GradleException("$message — SYNDICATE_REQUIRE_BLENDER=1")
            }
            this@register.logger.warn("SKIPPED :blender-tool:prepareVehicles — $message")
            return@doLast
        }
        for ((name, command) in models) {
            val process = ProcessBuilder(command).directory(toolDir).redirectErrorStream(false).start()
            val document = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                // 65 is D15-R13's "this model needs a parts.json", which is a report about the
                // content rather than a tool failure — but it still fails the build, because a
                // car nobody has prepared must not slip through as though it had been.
                throw GradleException("syndicate-prepare on $name exited $exit\n$document")
            }
            this@register.logger.lifecycle("prepared $name")
        }
    }
}
