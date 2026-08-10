plugins {
    id("syndicate.application-conventions")
}

description = "CLI that validates assets/ against schemas/, resolves references, emits asset-index.json (D02-S4.5)."

dependencies {
    implementation(projects.sharedModels)
    implementation(libs.bundles.jackson)
    implementation(libs.json.schema.validator)
}

application {
    mainClass.set("dev.syndicate.pipeline.PipelineMain")
    applicationName = "syndicate-pipeline"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("syndicate-pipeline")
    archiveClassifier.set("")
}

/**
 * `asset-index.json` from `assets/` (D02-S4.8, D08-S5.2).
 *
 * Strict, because this is the build's copy of the gate and D08-S5.4 says an ERROR fails a strict
 * build. It is deliberately NOT wired into `check` yet: the shipped parts declare meshes that do
 * not exist (PROG-013 — the art is one model per car, not one per part), so a strict run reports
 * A107 for every one of them and would fail every build until the split lands. Run it by hand, or
 * wire it in once the parts have their own meshes.
 */
tasks.register<JavaExec>("buildIndex") {
    group = "build"
    description = "Validates assets/ and writes assets/asset-index.json (D08-S5.2)."
    mainClass.set("dev.syndicate.pipeline.PipelineMain")
    classpath = sourceSets["main"].runtimeClasspath
    args("--assets", rootProject.layout.projectDirectory.dir("assets").asFile.absolutePath, "--strict")
}
