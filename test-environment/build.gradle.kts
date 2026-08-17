plugins {
    id("syndicate.application-conventions")
}

description = "Verification harness: asset checks, physics checks, destruction progression, JSON report (D14)."

dependencies {
    implementation(projects.gameCore)
    implementation(projects.sharedModels)

    // The harness has two modes (D14-S5.11 visual, #D14-S5.13 headless), so unlike every
    // other module it legitimately carries both backends.
    implementation(libs.gdx.backend.headless)
    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.json.schema.validator)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.syndicate.verify.VerifyMain")
    applicationName = "syndicate-verify"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("syndicate-verify")
    archiveClassifier.set("")
}

/**
 * `:test-environment:verifyFixtures` of D14-S7.3 step 2: run the harness over every processed
 * fixture and fail the stage if any asset failed.
 *
 * Every command line is resolved at configuration time; a `doLast` that reached back into
 * `providers` or the project would capture the script object, which the configuration cache
 * cannot serialise (DISC-001).
 */
val verifyFixtures = tasks.register("verifyFixtures") {
    group = "verification"
    description = "Runs the harness over build/fixtures-out/ and aggregates the reports (D14-S7.3)."
    dependsOn(":blender-tool:processFixtures")
    // The runtime classpath is captured as a value below, which does not carry a task dependency
    // with it — so without this the task happily runs the harness classes from the last build. It
    // did exactly that once: a hull fix was verified against the pre-fix jar and reported failing.
    dependsOn(tasks.named("classes"))

    val fixturesOut = rootProject.layout.buildDirectory.dir("fixtures-out").get().asFile
    val reportDir = rootProject.layout.buildDirectory.dir("verify").get().asFile
    val runtime = sourceSets["main"].runtimeClasspath
    val javaLauncher = javaToolchains.launcherFor(java.toolchain)

    inputs.dir(fixturesOut).optional()
    outputs.dir(reportDir)

    doLast {
        val assets = fixturesOut.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        if (assets.isEmpty()) {
            this@register.logger.warn("SKIPPED :test-environment:verifyFixtures — no processed fixtures")
            return@doLast
        }
        val failures = mutableListOf<String>()
        for (asset in assets) {
            val process = ProcessBuilder(
                javaLauncher.get().executablePath.asFile.absolutePath,
                "-cp", runtime.asPath,
                "dev.syndicate.verify.VerifyMain",
                "--headless",
                "--asset", asset.absolutePath,
                "--report", File(reportDir, "${asset.name}.report.json").absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            this@register.logger.lifecycle(
                output.lines().lastOrNull { it.contains("PASS ") || it.contains("FAIL ") }
                    ?: "${asset.name}: exit $code",
            )
            if (code != 0) {
                // The harness's own output, not just its exit code. A non-zero exit here is
                // either a failed check — which the report explains — or the harness dying
                // before it wrote one, which only its stderr explains. Reporting the code
                // alone makes those two indistinguishable, and the second is what a fixture
                // pipeline that has never run is most likely to hit.
                this@register.logger.error("verifyFixtures: ${asset.name} exited $code\n$output")
                failures += "${asset.name} (exit $code, D14-S4.2)"
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException("verifyFixtures failed for: ${failures.joinToString(", ")}")
        }
    }
}

tasks.named("check") {
    // Not wired in: `verifyFixtures` needs Blender to have produced the fixtures, which a
    // developer machine may not have. CI runs it as its own stage (D12-S5.4).
    dependsOn(tasks.named("test"))
}
