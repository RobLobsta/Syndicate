/**
 * Conventions for the runnable modules of D02-S4.5: `game-client`,
 * `game-server-headless`, `asset-pipeline`, `test-environment`, `memory-system`.
 *
 * Adds the logging binding (applications bind, libraries do not — D02-S4.1) and the
 * shadow packaging that produces the artifacts of D02-S4.8.
 */

plugins {
    id("syndicate.java-conventions")
    application
    id("com.gradleup.shadow")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "runtimeOnly"(libs.findLibrary("logback-classic").get())
}

tasks.named<JavaExec>("run") {
    defaultCharacterEncoding = "UTF-8"
    // Applications are launched by developers with `--args="..."`; without this they
    // inherit no stdin and the admin console (D03-S5.7) cannot be used interactively.
    standardInput = System.`in`
}

tasks.named<Jar>("jar") {
    // The shadow jar is the deliverable; the thin jar exists only for the classpath.
    archiveClassifier.set("thin")
}
