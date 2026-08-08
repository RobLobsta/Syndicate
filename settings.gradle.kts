// Syndicate — Gradle settings (docs/02_technical_architecture.md#D02-S4.4, #D02-S5.5)

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provisions the Java 17 toolchain (D02-R1) on machines that only have a newer JDK,
    // so AC-D02-1 ("clean clone, only a JDK installed") holds without a manual install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // gdx-gltf is not published to Maven Central. The content filter keeps JitPack
        // from being consulted for anything else, so a typo'd coordinate cannot silently
        // resolve to an arbitrary GitHub build.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.mgsx-dev.gdx-gltf") }
        }
    }
}

// `api(projects.sharedModels)` rather than a stringly-typed path, so a renamed module
// is a compile error in the build script instead of a runtime resolution failure.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "syndicate"

// The eight modules of D02-S4.5. AC-D02-2 asserts this list exactly.
include(
    "shared-models",
    "game-core",
    "game-client",
    "game-server-headless",
    "asset-pipeline",
    "test-environment",
    "memory-system",
    "blender-tool",
)
