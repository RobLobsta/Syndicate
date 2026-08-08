// Included build holding the convention plugins and the repository's guardrail checks.
// Kept out of the main build so a broken convention plugin fails at plugin-resolution
// time with a clear message rather than corrupting every module's configuration.

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
