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
