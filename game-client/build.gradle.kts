plugins {
    id("syndicate.application-conventions")
}

description = "Window, GL context, render systems, camera, HUD, input, audio (D02-S4.5)."

dependencies {
    implementation(projects.gameCore)
    implementation(projects.sharedModels)

    implementation(libs.gdx.backend.lwjgl3)
    implementation(libs.gdx.gltf)

    // Menu type, rasterised at the size it is drawn at. The shell falls back to the built-in
    // bitmap font if the native is unavailable, so this cannot stop the game starting (DEC-072).
    implementation(libs.gdx.freetype)
    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })

    // The sound bank's manifest (`assets/audio/audio.json`) is content, and the client is the
    // only process that reads it — D03-R13 does not load audio banks headless. Jackson is
    // already the project's JSON reader everywhere else; a second one would be worse.
    implementation(libs.bundles.jackson)

    // A gamepad is a first-class input device here, not a fallback: see
    // `dev.syndicate.client.input`. `core` is the API the systems compile against; `desktop`
    // is the backend and is a runtime concern, so nothing in this module can accidentally
    // import a backend type.
    implementation(libs.gdx.controllers.core)
    runtimeOnly(libs.gdx.controllers.desktop)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.syndicate.client.ClientMain")
    applicationName = "syndicate-client"
}

// D02-S4.8 / D02-E12: the distribution enumerates `assets/` explicitly. `art-source/`
// and `fixtures/` are never shipped, and a CI check greps the zip to prove it.
distributions {
    named("main") {
        contents {
            from(rootProject.layout.projectDirectory.dir("assets")) {
                into("assets")
            }
        }
    }
}

/*
 * Packaging (D02-S4.8).
 *
 * Two artifacts, for two different questions.
 *
 *   ./gradlew :game-client:distZip
 *       A zip holding `bin/syndicate-client.bat`, `lib/*.jar` and `assets/`. Runs on any
 *       machine with a JRE 17+, and it is built the same on every host — so a Linux CI
 *       box can produce the thing a Windows player unzips. This is the portable one.
 *
 *   ./gradlew :game-client:packageWindows        (must be run ON Windows)
 *       A self-contained `build/package/Syndicate/Syndicate.exe` with a cut-down JRE
 *       inside it and no Java installation required. jpackage can only target the host
 *       it runs on — there is no cross-compilation — which is why this task checks the
 *       OS and says so rather than producing something subtly wrong on Linux.
 *
 * `app-image` rather than `msi`: an msi additionally needs the WiX toolset installed, and
 * an installer is a distribution decision that has not been made. The app-image is a
 * folder you can copy anywhere and double-click.
 */
val packageWindows by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained Windows app image with jpackage (run this on Windows)."
    dependsOn(tasks.named("installDist"))

    val installDir = layout.buildDirectory.dir("install/syndicate-client")
    val outputDir = layout.buildDirectory.dir("package")
    val appVersion = providers.gradleProperty("version").get()

    // jpackage refuses a version with a qualifier, and this project's is `0.1.0-SNAPSHOT`.
    val numericVersion = appVersion.substringBefore('-')

    doFirst {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            throw GradleException(
                "packageWindows must run on Windows: jpackage builds only for its host OS. " +
                    "On this machine use `:game-client:distZip`, which produces a portable " +
                    "zip that runs on Windows with a JRE installed.",
            )
        }
        outputDir.get().asFile.deleteRecursively()
        outputDir.get().asFile.mkdirs()
    }

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "Syndicate",
        "--app-version", numericVersion,
        "--vendor", "Syndicate",
        "--input", installDir.get().dir("lib").asFile.absolutePath,
        "--main-jar", "game-client-$appVersion-thin.jar",
        "--main-class", "dev.syndicate.client.ClientMain",
        "--dest", outputDir.get().asFile.absolutePath,
        // Bullet and glTF both allocate outside the Java heap; the default heap is a
        // fraction of physical memory and on a 32 GB machine that is wildly more than
        // this needs. Pinned so the packaged game behaves the same on every machine.
        "--java-options", "-Xmx2g",
        // The content directory is copied in beside the launcher afterwards, and
        // `ClientRuntime.resolveAssetRoot` finds it from there whatever the working
        // directory turns out to be when a player double-clicks the exe.
        "--java-options", "-Dsyndicate.packaged=true",
    )

    doLast {
        copy {
            from(rootProject.layout.projectDirectory.dir("assets"))
            into(outputDir.get().dir("Syndicate/assets"))
        }
        logger.lifecycle("Windows app image: ${outputDir.get().dir("Syndicate").asFile}")
    }
}

// Renders one audition take per vehicle so the engines can be *heard* before they ship
// (D15-R38a15). Every defect this synthesiser has had was reported by ear first; a test suite
// only checks the questions somebody already thought to ask.
//
//   ./gradlew :game-client:showcaseAudio [-Pout=build/audio-showcase]
tasks.register<JavaExec>("showcaseAudio") {
    group = "verification"
    description = "Renders an audition take per vehicle to WAV for listening."
    mainClass.set("dev.syndicate.client.audio.ShowcaseRenderer")
    classpath = sourceSets["test"].runtimeClasspath
    args(providers.gradleProperty("out").getOrElse("build/audio-showcase"))
}
