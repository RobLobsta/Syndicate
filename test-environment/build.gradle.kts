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
    implementation(libs.gdx.gltf)
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
