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
 *
 * Two things about the executable host are not obvious and both were silent (DISC-064):
 *
 *  - **Blender's bundled Python builds `sys.path` from `PYTHONHOME` and consults neither
 *    `PYTHONPATH` nor the working directory.** Setting either on the process — which is
 *    what the tasks below used to do — leaves the variable visible in `os.environ` and
 *    absent from `sys.path`, so `import syndicate_fracture` raises `ModuleNotFoundError`.
 *    The tool directory has to be inserted from inside the expression itself.
 *
 *  - **`--python-expr` exits 0 on an uncaught exception** unless `--python-exit-code` is
 *    given. Without it the two failures compose into a task that runs every fixture, fails
 *    every one, and reports success having written nothing.
 *
 * The `bpy` module host needs neither: `python3 -m` honours `PYTHONPATH` and the working
 * directory normally, and propagates the exit code itself.
 */
private val toolDirPath: String = layout.projectDirectory.asFile.absolutePath

private fun blenderCommand(module: String, toolArgs: Array<out String>): List<String> =
    if (blenderExeAvailable) {
        listOf(
            blenderExe, "--background", "--factory-startup",
            // Before --python-expr: it is what makes a raising expression a non-zero exit.
            "--python-exit-code", "1",
            "--python-expr",
            "import sys; sys.path.insert(0, ${'"'}$toolDirPath${'"'}); " +
                "import $module.__main__ as m; sys.exit(m.main())",
            "--",
        ) + toolArgs
    } else {
        listOf("python3", "-m", module) + toolArgs
    }

fun fractureCommand(vararg toolArgs: String): List<String> =
    blenderCommand("syndicate_fracture", toolArgs)

/** The preparation tool's invocation, on whichever host is present — as `fractureCommand`. */
fun prepareCommand(vararg toolArgs: String): List<String> =
    blenderCommand("syndicate_prepare", toolArgs)

/** `ruff check` — CI stage 0 (D12-S5.4). */
val lint = tasks.register<Exec>("lint") {
    group = "verification"
    description = "ruff check every Python package and its tests (D02-S4.6)."
    workingDir = layout.projectDirectory.asFile
    commandLine(
        "ruff",
        "check",
        "syndicate_fracture",
        "syndicate_dissect",
        "syndicate_prepare",
        "syndicate_weapon",
        "tests",
    )
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

tasks.named("check") {
    dependsOn(lint, unitTest)
}

/**
 * The vehicle preparation pipeline of D15, over every model in `art-source/vehicles/`.
 *
 * Two tasks over one tool, because the two things it can do have opposite consequences.
 *
 * `classifyVehicles` runs stages 1 to 6 and writes only a report. It is what to run when a
 * threshold in the cue ensemble changed and the question is what that did to the labelling.
 *
 * `prepareVehicles` runs all nine and writes `assets/vehicles/<vehicleTypeId>/` — its parts, its
 * `manifest.json` and its `assembly.json`, all committed content. Running it is a decision to re-cut the art and belongs in the commit that does so,
 * which is why it is wired into no other task, exactly as `dissectVehicles` is not.
 *
 * Both are strict: D15-R13 makes an under-labelled model a non-zero exit, and the point of
 * running either from the build is to find out that a newly added car needs a `parts.json`
 * before somebody spends an afternoon wondering why its doors are part of the chassis.
 *
 * Neither is wired into `check`, for the same reason `processFixtures` is not: they need a
 * Blender host, take seventeen seconds a car, and a developer without Blender must still build.
 */
fun registerPreparation(taskName: String, taskDescription: String, writeAssets: Boolean) =
    tasks.register(taskName) {
        group = "build"
        description = taskDescription

        val toolDir = layout.projectDirectory.asFile
        val vehiclesDir = rootProject.layout.projectDirectory.dir("art-source/vehicles").asFile
        val reportRoot = rootProject.layout.buildDirectory.dir("prepare-reports").get().asFile
        val assetRoot = rootProject.layout.projectDirectory.dir("assets").asFile
        val materialTable =
            rootProject.layout.projectDirectory.file("assets/materials/materials.json").asFile
        val balanceTable =
            rootProject.layout.projectDirectory.file("assets/balance/classes.json").asFile
        val styleTable =
            rootProject.layout.projectDirectory.file("assets/materials/style.json").asFile

        // Resolved at configuration time into plain strings: a `doLast` that built the command
        // would capture the script object, which the configuration cache cannot serialise
        // (DISC-001).
        val models: List<Pair<String, List<String>>> =
            (vehiclesDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList())
                .map { dir ->
                    val arguments = mutableListOf(
                        "--model", dir.absolutePath,
                        "--vehicle", dir.name,
                        "--strict",
                        "--material-table", materialTable.absolutePath,
                        "--balance-table", balanceTable.absolutePath,
                        "--style-table", styleTable.absolutePath,
                        "--report", File(reportRoot, "${dir.name}.json").absolutePath,
                    )
                    if (writeAssets) {
                        // One root, not two paths. The tool derives
                        // `vehicles/vehicle_<name>_01/parts` from it, so a vehicle's parts land
                        // under the vehicle that owns them without the build knowing the rule
                        // (D08-R14b).
                        arguments += listOf("--assets", assetRoot.absolutePath)
                    }
                    dir.name to prepareCommand(*arguments.toTypedArray())
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
                this@register.logger.warn("SKIPPED :blender-tool:$taskName — $message")
                return@doLast
            }
            for ((name, command) in models) {
                val process =
                    ProcessBuilder(command).directory(toolDir).redirectErrorStream(false).start()
                val document = process.inputStream.bufferedReader().readText()
                val exit = process.waitFor()
                if (exit != 0) {
                    // 65 is D15-R13's "this model needs a parts.json", which is a report about
                    // the content rather than a tool failure — but it still fails the build,
                    // because a car nobody has prepared must not slip through as though it had.
                    throw GradleException("syndicate-prepare on $name exited $exit\n$document")
                }
                this@register.logger.lifecycle("$taskName: $name")
            }
        }
    }

registerPreparation(
    "classifyVehicles",
    "Classifies art-source/vehicles/ and reports; writes no assets (D15-S5.1 stages 1-6).",
    writeAssets = false,
)

registerPreparation(
    "prepareVehicles",
    "Turns art-source/vehicles/ into assets/vehicles/<id>/parts (D15-S5.1, all 9 stages).",
    writeAssets = true,
)

/**
 * Stage 9 of D15-S5.1, over whatever is in `assets/`: opens every mesh a `part.json` names and
 * checks it contains the nodes and morph targets that manifest promises, then checks the
 * assembly and the parts agree about slots, masses and the power budget.
 *
 * The asset gate in `asset-pipeline` is the authority on content validity and this does not
 * replace it. What it adds is the half the JVM cannot see: the *inside of the .glb*. A
 * `part.json` promising four morph targets over a mesh carrying none passes every JSON check
 * ever written and fails at the moment somebody drives the car.
 */
tasks.register<Exec>("verifyPreparedAssets") {
    group = "verification"
    description = "Checks assets/parts and assets/vehicles against their own meshes (D15-S5.1)."
    workingDir = layout.projectDirectory.asFile
    commandLine(
        "python3", "tools/verify_prepared.py",
        rootProject.layout.projectDirectory.dir("assets").asFile.absolutePath,
    )
    val available = bpyModuleAvailable
    onlyIf { task ->
        if (!available) {
            task.logger.warn("SKIPPED :blender-tool:verifyPreparedAssets — needs the bpy module")
        }
        available
    }
}
